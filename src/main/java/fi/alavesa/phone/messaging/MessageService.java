package fi.alavesa.phone.messaging;

import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.media.MediaStore;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.ui.TextWrapper;
import fi.alavesa.phone.util.Tasks;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Sending, receiving and the moderation rules. Main thread only; storage goes through {@link Database} and comes
 * back to the main thread before anything else happens.
 *
 * <p>Messages are addressed to player UUIDs. Whoever holds a phone item sees their own messages; a stolen phone
 * shows the thief's conversations, not the owner's.
 */
public final class MessageService {

    public enum SendResult { OK, EMPTY, TOO_LONG, RATE_LIMITED, MUTED, BLOCKED, YOU_BLOCKED, SELF, CANCELLED, NO_PERMISSION, ERROR,
        NOT_YOURS, TOO_OLD, DISABLED }

    /** What happens around a message besides storing it (item state, sound, hint, open dialogs). */
    public interface Notifier {

        /** A message arrived for an online player. */
        void incoming(Player recipient, Message message, String senderName);

        /** The unread count of an online player changed (phone item state, badges). */
        void unreadChanged(Player player);

        /** A message in the conversation between {@code player} and {@code partner} was edited or deleted. */
        void conversationChanged(Player player, UUID partner);

        /** A player joined with messages that arrived while they were offline. */
        void waitingOnJoin(Player player, int unread);
    }

    private final Plugin plugin;
    private final Database db;
    private PhoneConfig cfg;
    private MediaStore attachments;
    private Notifier notifier;
    private RateLimiter limiter;
    private final Map<UUID, Integer> unread = new HashMap<>();
    private final Map<UUID, Set<UUID>> blocks = new HashMap<>();
    private final Map<UUID, Long> mutes;

    public MessageService(Plugin plugin, Database db, PhoneConfig cfg, Map<UUID, Long> mutes) {
        this.plugin = plugin;
        this.db = db;
        this.mutes = new HashMap<>(mutes);
        reload(cfg);
    }

    public void reload(PhoneConfig cfg) {
        this.cfg = cfg;
        this.limiter = new RateLimiter(cfg.rateMessages, cfg.rateSeconds * 1000L);
    }

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    // ---- join / quit ------------------------------------------------------------------------------------------------

    public void join(Player player) {
        UUID id = player.getUniqueId();
        db.rememberPlayer(id, player.getName());
        Tasks.onMain(plugin, db.blocks(id), set -> {
            if (player.isOnline()) blocks.put(id, set);
        });
        Tasks.onMain(plugin, db.unreadCount(id), count -> {
            if (!player.isOnline()) return;
            unread.put(id, count);
            notifier.unreadChanged(player);
            if (count > 0) notifier.waitingOnJoin(player, count);
        });
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        unread.remove(id);
        blocks.remove(id);
        limiter.forget(id);
    }

    // ---- unread -----------------------------------------------------------------------------------------------------

    public int unread(UUID player) {
        return unread.getOrDefault(player, 0);
    }

    /** The database counted again (a conversation was opened and marked read). */
    public void unreadIs(Player player, int count) {
        Integer old = unread.put(player.getUniqueId(), count);
        if (old == null || old != count) notifier.unreadChanged(player);
    }

    // ---- blocks and mutes -------------------------------------------------------------------------------------------

    public boolean hasBlocked(UUID owner, UUID other) {
        Set<UUID> set = blocks.get(owner);
        return set != null && set.contains(other);
    }

    public void setBlocked(Player owner, UUID other, boolean blocked) {
        Set<UUID> set = blocks.computeIfAbsent(owner.getUniqueId(), k -> new HashSet<>());
        if (blocked) set.add(other);
        else set.remove(other);
        db.setBlocked(owner.getUniqueId(), other, blocked);
    }

    public boolean isMuted(UUID player) {
        Long until = mutes.get(player);
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            mutes.remove(player);
            db.setMute(player, null);
            return false;
        }
        return true;
    }

    /** @param until epoch millis, {@link Long#MAX_VALUE} for a permanent mute, null to unmute */
    public void setMuted(UUID player, Long until) {
        if (until == null) mutes.remove(player);
        else mutes.put(player, until);
        db.setMute(player, until);
    }

    // ---- sending ----------------------------------------------------------------------------------------------------

    /**
     * Validates, stores and delivers a message. {@code done} runs on the main thread with the outcome; for
     * {@link SendResult#OK} the message is already stored and the recipient (if online) notified.
     */
    public void send(Player sender, UUID recipientId, String recipientName, String typed, Consumer<SendResult> done) {
        send(sender, recipientId, recipientName, typed, null, done);
    }

    /** The files of attached pictures (plugins/Phone/attachments); set once at start. */
    public void setAttachments(MediaStore attachments) {
        this.attachments = attachments;
    }

    public CompletableFuture<MediaStore.Media> attachment(long messageId) {
        return attachments == null ? CompletableFuture.completedFuture(null) : attachments.read(messageId);
    }

    /** Same, with a picture ({@code poster}, 128x128 pixels) attached to the message. */
    public void send(Player sender, UUID recipientId, String recipientName, String typed, int[] poster, Consumer<SendResult> done) {
        UUID senderId = sender.getUniqueId();
        if (!sender.hasPermission("phone.message")) {
            done.accept(SendResult.NO_PERMISSION);
            return;
        }
        if (senderId.equals(recipientId)) {
            done.accept(SendResult.SELF);
            return;
        }
        if (typed != null && typed.codePointCount(0, typed.length()) > cfg.maxLength) {
            done.accept(SendResult.TOO_LONG);
            return;
        }
        String body = TextWrapper.sanitize(typed, cfg.maxLength);
        if (body.isEmpty()) {
            done.accept(SendResult.EMPTY);
            return;
        }
        if (isMuted(senderId)) {
            done.accept(SendResult.MUTED);
            return;
        }
        if (hasBlocked(senderId, recipientId)) {
            done.accept(SendResult.YOU_BLOCKED);
            return;
        }
        if (!limiter.tryAcquire(senderId, System.currentTimeMillis())) {
            done.accept(SendResult.RATE_LIMITED);
            return;
        }
        PhoneMessageEvent event = new PhoneMessageEvent(sender, recipientId, recipientName, body);
        Bukkit.getPluginManager().callEvent(event);
        String finalBody = TextWrapper.sanitize(event.getMessage(), cfg.maxLength);
        if (event.isCancelled() || finalBody.isEmpty()) {
            done.accept(SendResult.CANCELLED);
            return;
        }
        // the recipient's block list is in memory while they are online, in the database otherwise
        if (blocks.containsKey(recipientId)) {
            if (hasBlocked(recipientId, senderId)) done.accept(SendResult.BLOCKED);
            else store(sender, recipientId, finalBody, poster, done);
        } else {
            db.isBlocked(recipientId, senderId).whenComplete((blocked, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) done.accept(SendResult.ERROR);
                else if (blocked) done.accept(SendResult.BLOCKED);
                else store(sender, recipientId, finalBody, poster, done);
            }));
        }
    }

    private void store(Player sender, UUID recipientId, String body, int[] poster, Consumer<SendResult> done) {
        long now = System.currentTimeMillis();
        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        boolean photo = poster != null && attachments != null;
        db.insertMessage(senderId, recipientId, body, now, photo).thenCompose(id -> id >= 0 && photo
                ? attachments.write(id, new MediaStore.Media(List.of(poster), 1)).thenApply(v -> id) : CompletableFuture.completedFuture(id)).whenComplete((id, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    done.accept(SendResult.ERROR);
                    return;
                }
                Player recipient = Bukkit.getPlayer(recipientId);
                if (recipient != null) {
                    // offline recipients need nothing here: the row waits unread and join() announces it
                    unread.merge(recipientId, 1, Integer::sum);
                    notifier.unreadChanged(recipient);
                    notifier.incoming(recipient, new Message(id, senderId, recipientId, body, now, false, false, photo), senderName);
                }
                done.accept(SendResult.OK);
            });
        });
    }

    // ---- editing and deleting ---------------------------------------------------------------------------------------

    /** May {@code player} still change this message? {@link SendResult#OK} when so. */
    public SendResult mayChange(Player player, Message message, boolean delete) {
        if (delete ? !cfg.allowDelete : !cfg.allowEdit) return SendResult.DISABLED;
        if (message == null || !message.sender().equals(player.getUniqueId())) return SendResult.NOT_YOURS;
        if (cfg.editWindowMinutes > 0 && System.currentTimeMillis() - message.sentAt() > cfg.editWindowMinutes * 60_000L) {
            return SendResult.TOO_OLD;
        }
        return SendResult.OK;
    }

    /** Replaces the text of one of the player's own messages. {@code done} runs on the main thread. */
    public void edit(Player player, long id, String recipientName, String typed, Consumer<SendResult> done) {
        if (!player.hasPermission("phone.message")) {
            done.accept(SendResult.NO_PERMISSION);
            return;
        }
        if (typed != null && typed.codePointCount(0, typed.length()) > cfg.maxLength) {
            done.accept(SendResult.TOO_LONG);
            return;
        }
        String body = TextWrapper.sanitize(typed, cfg.maxLength);
        if (body.isEmpty()) {
            done.accept(SendResult.EMPTY);
            return;
        }
        if (isMuted(player.getUniqueId())) {
            done.accept(SendResult.MUTED);
            return;
        }
        Tasks.onMain(plugin, db.message(id), message -> {
            SendResult allowed = mayChange(player, message, false);
            if (allowed != SendResult.OK) {
                done.accept(allowed);
                return;
            }
            if (body.equals(message.body())) {
                done.accept(SendResult.OK);
                return;
            }
            PhoneMessageEvent event = new PhoneMessageEvent(player, message.recipient(), recipientName, body, true);
            Bukkit.getPluginManager().callEvent(event);
            String finalBody = TextWrapper.sanitize(event.getMessage(), cfg.maxLength);
            if (event.isCancelled() || finalBody.isEmpty()) {
                done.accept(SendResult.CANCELLED);
                return;
            }
            Tasks.onMain(plugin, db.editMessage(id, player.getUniqueId(), finalBody), changed -> {
                if (changed) touched(player.getUniqueId(), message.recipient());
                done.accept(changed ? SendResult.OK : SendResult.NOT_YOURS);
            });
        });
    }

    /** Deletes one of the player's own messages for both sides. {@code done} runs on the main thread. */
    public void delete(Player player, long id, Consumer<SendResult> done) {
        Tasks.onMain(plugin, db.message(id), message -> {
            SendResult allowed = mayChange(player, message, true);
            if (allowed != SendResult.OK) {
                done.accept(allowed);
                return;
            }
            Tasks.onMain(plugin, db.deleteMessage(id, player.getUniqueId()), removed -> {
                if (removed && message.photo() && attachments != null) attachments.delete(id);
                if (removed) touched(player.getUniqueId(), message.recipient());
                done.accept(removed ? SendResult.OK : SendResult.NOT_YOURS);
            });
        });
    }

    /** The other side of an edited or deleted message: recount their unread messages, refresh what they look at. */
    private void touched(UUID sender, UUID recipientId) {
        Player recipient = Bukkit.getPlayer(recipientId);
        if (recipient == null) return;
        Tasks.onMain(plugin, db.unreadCount(recipientId), count -> {
            if (!recipient.isOnline()) return;
            unreadIs(recipient, count);
            notifier.conversationChanged(recipient, sender);
        });
    }
}
