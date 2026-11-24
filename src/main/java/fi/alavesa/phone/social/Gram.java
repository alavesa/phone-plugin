package fi.alavesa.phone.social;

import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.media.Gallery;
import fi.alavesa.phone.media.MediaStore;
import fi.alavesa.phone.messaging.MessageService;
import fi.alavesa.phone.messaging.RateLimiter;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.ui.TextWrapper;
import fi.alavesa.phone.util.Tasks;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Minestagram: accounts, posts of photos and videos, likes, comments and reposts. The rules live here (permissions,
 * handles, lengths, rate limits, mutes, who may delete what); storage is {@link Database} and the pictures of posts
 * are copies in {@code plugins/Phone/minestagram/}, so a post stays when its author deletes the photo from Photos.
 *
 * <p>Main thread only; every {@code done} callback runs on the main thread.
 */
public final class Gram {

    public enum Result { OK, NO_PERMISSION, DISABLED, NO_ACCOUNT, BAD_HANDLE, HANDLE_TAKEN, EMPTY, TOO_LONG, RATE_LIMITED, MUTED,
        NOT_FOUND, NOT_YOURS, OWN_POST, ERROR }

    /** Tells a player that something happened to one of their posts (hint, sound). */
    public interface Notifier {
        void activity(Player author, String handle, String kind);
    }

    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_.]{3,16}");

    private final Plugin plugin;
    private final Database db;
    private final Gallery gallery;
    private final MediaStore media;
    private final MessageService messages;
    private PhoneConfig cfg;
    private RateLimiter commentLimiter;
    private final Map<UUID, Long> lastPost = new HashMap<>();
    private Notifier notifier = (author, handle, kind) -> { };

    public Gram(Plugin plugin, Database db, Gallery gallery, MediaStore media, MessageService messages, PhoneConfig cfg) {
        this.plugin = plugin;
        this.db = db;
        this.gallery = gallery;
        this.media = media;
        this.messages = messages;
        reload(cfg);
    }

    public void reload(PhoneConfig cfg) {
        this.cfg = cfg;
        this.commentLimiter = new RateLimiter(cfg.gramCommentsPerMinute, 60_000L);
    }

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    public MediaStore media() {
        return media;
    }

    public boolean enabled() {
        return cfg.gramEnabled;
    }

    public boolean mayUse(Player player) {
        return cfg.gramEnabled && player.hasPermission("phone.gram");
    }

    public boolean moderator(Player player) {
        return player.hasPermission("phone.gram.moderate");
    }

    public static boolean validHandle(String handle) {
        return handle != null && HANDLE.matcher(handle).matches();
    }

    /** A handle made from a Minecraft name: the same characters are allowed, so usually the name itself. */
    public static String suggestHandle(String name) {
        String h = name.replaceAll("[^A-Za-z0-9_.]", "");
        return h.length() < 3 ? (h + "___").substring(0, 3) : h.length() > 16 ? h.substring(0, 16) : h;
    }

    private <T> void main(CompletableFuture<T> future, Consumer<T> onMain, Consumer<Result> done) {
        future.whenComplete((value, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) done.accept(Result.ERROR);
                else onMain.accept(value);
            });
        });
    }

    private Result gate(Player player) {
        if (!cfg.gramEnabled) return Result.DISABLED;
        if (!player.hasPermission("phone.gram")) return Result.NO_PERMISSION;
        if (messages.isMuted(player.getUniqueId())) return Result.MUTED;
        return Result.OK;
    }

    // ---- accounts ---------------------------------------------------------------------------------------------------

    public CompletableFuture<Database.GramAccount> account(UUID id) {
        return db.gramAccount(id);
    }

    public void saveAccount(Player player, String handle, String bio, Consumer<Result> done) {
        Result gate = gate(player);
        if (gate != Result.OK) {
            done.accept(gate);
            return;
        }
        String h = handle == null ? "" : handle.strip();
        if (h.startsWith("@")) h = h.substring(1);
        if (!validHandle(h)) {
            done.accept(Result.BAD_HANDLE);
            return;
        }
        if (bio != null && bio.codePointCount(0, bio.length()) > cfg.gramBioLength) {
            done.accept(Result.TOO_LONG);
            return;
        }
        String cleanBio = TextWrapper.sanitize(bio, cfg.gramBioLength);
        main(db.saveGramAccount(player.getUniqueId(), h, cleanBio, System.currentTimeMillis()),
                ok -> done.accept(ok ? Result.OK : Result.HANDLE_TAKEN), done);
    }

    // ---- posts ------------------------------------------------------------------------------------------------------

    /** Seconds until this player may post again (0 = now). */
    public long postWait(Player player) {
        Long last = lastPost.get(player.getUniqueId());
        if (last == null || player.hasPermission("phone.gram.moderate")) return 0;
        long left = cfg.gramPostCooldownSeconds * 1000L - (System.currentTimeMillis() - last);
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** Publishes picture {@code galleryId} of the player's Photos with a caption. {@code done} gets the new post id or a result. */
    public void post(Player player, long galleryId, String caption, Consumer<Result> done, Consumer<Long> posted) {
        Result gate = gate(player);
        if (gate != Result.OK) {
            done.accept(gate);
            return;
        }
        if (caption != null && caption.codePointCount(0, caption.length()) > cfg.gramCaptionLength) {
            done.accept(Result.TOO_LONG);
            return;
        }
        if (postWait(player) > 0) {
            done.accept(Result.RATE_LIMITED);
            return;
        }
        String text = TextWrapper.sanitize(caption, cfg.gramCaptionLength);
        UUID me = player.getUniqueId();
        main(db.gramAccount(me).thenCombine(gallery.item(galleryId), (acct, item) -> new Object[] {acct, item}), pair -> {
            Database.GramAccount acct = (Database.GramAccount) pair[0];
            Database.GalleryItem item = (Database.GalleryItem) pair[1];
            if (acct == null) {
                done.accept(Result.NO_ACCOUNT);
                return;
            }
            if (item == null || !item.owner().equals(me)) {
                done.accept(Result.NOT_FOUND);
                return;
            }
            lastPost.put(me, System.currentTimeMillis());
            main(db.insertGramPost(me, System.currentTimeMillis(), text, item.frames(), item.fps(), null)
                    .thenCompose(id -> gallery.store().copyTo(galleryId, media, id).thenApply(ok -> ok ? id : -1L)), id -> {
                if (id < 0) done.accept(Result.ERROR);
                else posted.accept(id);
            }, done);
        }, done);
    }

    public void delete(Player player, long postId, Consumer<Result> done) {
        UUID author = moderator(player) ? null : player.getUniqueId();
        main(db.deleteGramPost(postId, author).thenCompose(gone -> gone ? media.delete(postId).thenApply(v -> true)
                : CompletableFuture.completedFuture(false)), gone -> done.accept(gone ? Result.OK : Result.NOT_YOURS), done);
    }

    /** Deletes a post whoever wrote it (/phone gram delete). */
    public void deleteAny(long postId, Consumer<Result> done) {
        main(db.deleteGramPost(postId, null).thenCompose(gone -> gone ? media.delete(postId).thenApply(v -> true)
                : CompletableFuture.completedFuture(false)), gone -> done.accept(gone ? Result.OK : Result.NOT_FOUND), done);
    }

    public void like(Player player, Database.GramPost post, Consumer<Result> done) {
        Result gate = gate(player);
        if (gate != Result.OK) {
            done.accept(gate);
            return;
        }
        boolean like = !post.liked();
        main(db.setGramLike(post.id(), player.getUniqueId(), like, System.currentTimeMillis()), found -> {
            if (!found) {
                done.accept(Result.NOT_FOUND);
                return;
            }
            if (like) tell(post.author(), player, "like");
            done.accept(Result.OK);
        }, done);
    }

    public void repost(Player player, Database.GramPost post, Consumer<Result> done) {
        Result gate = gate(player);
        if (gate != Result.OK) {
            done.accept(gate);
            return;
        }
        if (post.author().equals(player.getUniqueId())) {
            done.accept(Result.OWN_POST);
            return;
        }
        main(db.gramAccount(player.getUniqueId()), acct -> {
            if (acct == null) {
                done.accept(Result.NO_ACCOUNT);
                return;
            }
            main(db.toggleGramRepost(post.id(), player.getUniqueId(), System.currentTimeMillis()), now -> {
                if (now == null) {
                    done.accept(Result.NOT_FOUND);
                    return;
                }
                if (now) tell(post.author(), player, "repost");
                done.accept(Result.OK);
            }, done);
        }, done);
    }

    public void comment(Player player, Database.GramPost post, String typed, Consumer<Result> done) {
        Result gate = gate(player);
        if (gate != Result.OK) {
            done.accept(gate);
            return;
        }
        if (typed != null && typed.codePointCount(0, typed.length()) > cfg.gramCommentLength) {
            done.accept(Result.TOO_LONG);
            return;
        }
        String body = TextWrapper.sanitize(typed, cfg.gramCommentLength);
        if (body.isEmpty()) {
            done.accept(Result.EMPTY);
            return;
        }
        main(db.gramAccount(player.getUniqueId()), acct -> {
            if (acct == null) {
                done.accept(Result.NO_ACCOUNT);
                return;
            }
            if (!commentLimiter.tryAcquire(player.getUniqueId(), System.currentTimeMillis())) {
                done.accept(Result.RATE_LIMITED);
                return;
            }
            main(db.insertGramComment(post.id(), player.getUniqueId(), body, System.currentTimeMillis()), id -> {
                if (id < 0) {
                    done.accept(Result.NOT_FOUND);
                    return;
                }
                tell(post.author(), player, "comment");
                done.accept(Result.OK);
            }, done);
        }, done);
    }

    /** Deletes a comment: its author, the post's author and moderators may. */
    public void deleteComment(Player player, long commentId, Consumer<Result> done) {
        main(db.gramComment(commentId), c -> {
            if (c == null) {
                done.accept(Result.NOT_FOUND);
                return;
            }
            UUID me = player.getUniqueId();
            main(db.gramPost(me, c.postId()), post -> {
                boolean allowed = c.author().equals(me) || moderator(player) || (post != null && post.author().equals(me));
                if (!allowed) {
                    done.accept(Result.NOT_YOURS);
                    return;
                }
                main(db.deleteGramComment(commentId, null), gone -> done.accept(gone ? Result.OK : Result.NOT_FOUND), done);
            }, done);
        }, done);
    }

    private void tell(UUID author, Player actor, String kind) {
        if (author.equals(actor.getUniqueId())) return;
        Player online = Bukkit.getPlayer(author);
        if (online == null) return;
        Tasks.onMain(plugin, db.gramAccount(actor.getUniqueId()), acct -> {
            if (online.isOnline()) notifier.activity(online, acct == null ? actor.getName() : acct.handle(), kind);
        });
    }

    public void quit(Player player) {
        commentLimiter.forget(player.getUniqueId());
    }
}
