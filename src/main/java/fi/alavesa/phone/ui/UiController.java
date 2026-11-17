package fi.alavesa.phone.ui;

import fi.alavesa.phone.camera.CameraService;
import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.messaging.ContactRow;
import fi.alavesa.phone.messaging.ConversationPage;
import fi.alavesa.phone.messaging.Message;
import fi.alavesa.phone.messaging.MessageService;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.resourcepack.PackService;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.util.PostFx;
import fi.alavesa.phone.util.Sounds;
import fi.alavesa.phone.util.Tasks;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The phone's screens as Paper dialogs: which screen a player is on, what the buttons do, and when a screen has to
 * be sent again. Drawing is left to a {@link ScreenRenderer}; this class only decides what to show.
 *
 * <p><b>Buttons.</b> Every button is a custom click action {@code phone:<path>} (for example
 * {@code phone:conv/<uuid>/0}); the client sends it back together with the values of the dialog's inputs and
 * {@link #onClick} routes it. Nothing is kept per dialog on the server, so an old dialog can never dangle.
 *
 * <p><b>Dialogs cannot be updated in place.</b> The only way to change what a player sees is to send a whole new
 * dialog, which resets its text field. Typed text therefore survives only when the client tells us about it: every
 * button on the conversation screen (Send, Refresh, Older, Newer, Block, Back) carries the field's content, and it
 * is put back as the field's initial value. A refresh the player did not ask for (a message arriving while the
 * conversation is open) cannot know the text typed since the last button press; see {@code messaging.auto-refresh}.
 *
 * <p><b>Closing.</b> The client sends nothing when a dialog closes, except that Escape runs the dialog's exit
 * action. Every screen has one (Back or Close), so Escape walks back one screen at a time and the server always
 * knows where the player is.
 */
public final class UiController implements Listener, MessageService.Notifier, CameraService.ResultScreen {

    private enum Screen { NONE, HOME, CONTACTS, CONVERSATION, PHOTO }

    private static final class Session {
        Screen screen = Screen.NONE;
        UUID partner;
        String partnerName = "";
        long cursor;
        String filter = "";
        int contactsPage;
        String notice = "";
        boolean sending;
        long shownTick;
        BukkitTask refresh;
        final Map<UUID, String> drafts = new HashMap<>();
    }

    private static final Key SCREEN_FX = Key.key("phone", "screen");
    private static final String NAMESPACE = "phone";

    private final Plugin plugin;
    private final Database db;
    private final MessageService messages;
    private final PhoneItems items;
    private final PackService pack;
    private final PostFx postFx;
    private final Sounds sounds;
    private final CameraService camera;
    private final Glyphs glyphs;
    private final FontWidths widths;
    private PhoneConfig cfg;
    private Lang lang;
    private ScreenRenderer fancy;
    private ScreenRenderer plain;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public UiController(Plugin plugin, Database db, MessageService messages, PhoneItems items, PackService pack, PostFx postFx,
                        Sounds sounds, CameraService camera, Glyphs glyphs, FontWidths widths, PhoneConfig cfg, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.messages = messages;
        this.items = items;
        this.pack = pack;
        this.postFx = postFx;
        this.sounds = sounds;
        this.camera = camera;
        this.glyphs = glyphs;
        this.widths = widths;
        reload(cfg, lang);
    }

    public void reload(PhoneConfig cfg, Lang lang) {
        this.cfg = cfg;
        this.lang = lang;
        this.fancy = new FancyRenderer(glyphs, widths, lang, cfg.playerHeads);
        this.plain = new PlainRenderer(widths, lang);
    }

    private Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
    }

    private ScreenRenderer renderer(Player player) {
        return pack.hasPack(player) ? fancy : plain;
    }

    private String clock() {
        return ZonedDateTime.now(cfg.zone).format(cfg.timeFormat);
    }

    /** Time of a message: just the time today, date and time otherwise. */
    private String stamp(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(cfg.zone);
        String time = t.format(cfg.timeFormat);
        return t.toLocalDate().equals(LocalDate.now(cfg.zone)) ? time : t.format(cfg.dateFormat) + " " + time;
    }

    /** May this player use a phone right now? Tells them when not. */
    private boolean mayUse(Player player) {
        if (!player.hasPermission("phone.use")) {
            player.sendMessage(lang.get("error.no-permission"));
            return false;
        }
        if (!items.hasPhone(player)) {
            player.sendMessage(lang.get("error.no-phone"));
            return false;
        }
        return true;
    }

    // ---- dialog plumbing --------------------------------------------------------------------------------------------

    private static ActionButton button(Component label, int width, String path) {
        return ActionButton.builder(label).width(width).action(DialogAction.customClick(Key.key(NAMESPACE, path), null)).build();
    }

    /** Height of what a dialog stacks under its text: 10 px gaps, 20 px inputs (+13 with a label), 20 px button rows 2 px apart. */
    private static int below(int plainInputs, int labelledInputs, int buttons, int columns) {
        int rows = (buttons + columns - 1) / columns;
        return plainInputs * 30 + labelledInputs * 43 + (rows > 0 ? 10 + rows * 22 - 2 : 0);
    }

    private void show(Player player, Session s, Screen screen, Component body, int width, List<DialogInput> inputs,
                      List<ActionButton> buttons, int columns, String exitLabel, String exitPath) {
        if (s.refresh != null) {
            s.refresh.cancel();
            s.refresh = null;
        }
        DialogBase base = DialogBase.builder(lang.get("ui.title"))
                .canCloseWithEscape(true)
                .pause(false)
                // the dialog stays until the server answers with the next one: no flash of the world in between
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(body, width)))
                .inputs(inputs)
                .build();
        ActionButton exit = button(lang.get(exitLabel), 120, exitPath);
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons, exit, columns)));
        s.screen = screen;
        s.shownTick = Bukkit.getCurrentTick();
        if (cfg.screenEffect && pack.hasPack(player)) postFx.set(player, SCREEN_FX, true);
        player.showDialog(dialog);
    }

    /** Closes whatever phone screen is open. {@code closeDialog}: also tell the client (it may already be closed). */
    public void close(Player player, boolean closeDialog) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.screen == Screen.NONE) return;
        if (s.refresh != null) s.refresh.cancel();
        s.refresh = null;
        s.screen = Screen.NONE;
        s.sending = false;
        postFx.set(player, SCREEN_FX, false);
        if (closeDialog) player.closeDialog();
    }

    public boolean isOpen(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.screen != Screen.NONE;
    }

    // ---- home -------------------------------------------------------------------------------------------------------

    public void openHome(Player player) {
        if (!mayUse(player)) return;
        if (camera.isActive(player)) camera.exit(player);
        Session s = session(player);
        ScreenRenderer r = renderer(player);
        int unread = messages.unread(player.getUniqueId());
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission("phone.message")) {
            buttons.add(button(r.appButton("messages", lang.get("ui.button.messages"), unread), 106, "contacts/0"));
        }
        if (cfg.cameraEnabled && player.hasPermission("phone.camera")) {
            buttons.add(button(r.appButton("camera", lang.get("ui.button.camera"), 0), 106, "camera"));
        }
        if (buttons.isEmpty()) {
            player.sendMessage(lang.get("error.no-apps"));
            return;
        }
        Component body = r.home(new Views.Home(clock(), player.getName(), unread), below(0, 0, buttons.size(), 2));
        show(player, s, Screen.HOME, body, r.bodyWidth(), List.of(), buttons, 2, "ui.button.close", "close");
    }

    // ---- contacts ---------------------------------------------------------------------------------------------------

    public void openContacts(Player player, int page) {
        if (!mayUse(player)) return;
        if (!player.hasPermission("phone.message")) {
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        Session s = session(player);
        String filter = s.filter;
        CompletableFuture<Database.KnownPlayer> found = filter.isEmpty()
                ? CompletableFuture.completedFuture(null) : db.findPlayer(filter);
        CompletableFuture<List<ContactRow>> rows = db.contacts(player.getUniqueId());
        Tasks.onMain(plugin, rows.thenCombine(found, (list, known) -> Map.entry(list, java.util.Optional.ofNullable(known))), result -> {
            if (!player.isOnline()) return;
            showContacts(player, s, page, result.getKey(), result.getValue().orElse(null));
        });
    }

    private void showContacts(Player player, Session s, int page, List<ContactRow> rows, Database.KnownPlayer found) {
        UUID me = player.getUniqueId();
        Map<UUID, Views.Contact> all = new LinkedHashMap<>();
        int totalUnread = 0;
        // 1. people with a conversation, newest first
        for (ContactRow row : rows) {
            Player other = Bukkit.getPlayer(row.partner());
            boolean online = other != null && player.canSee(other);
            totalUnread += row.unread();
            if (!online && !cfg.showOfflineContacts && row.unread() == 0) continue;
            String name = other != null ? other.getName() : row.name() != null ? row.name() : lang.plain("ui.contacts.unknown");
            all.put(row.partner(), new Views.Contact(row.partner(), name, online, row.lastBody(), row.lastFromMe(), stamp(row.lastTime()), row.unread()));
        }
        // 2. everyone else who is online, by name
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player other : online) {
            if (other.getUniqueId().equals(me) || all.containsKey(other.getUniqueId()) || !player.canSee(other)) continue;
            all.put(other.getUniqueId(), new Views.Contact(other.getUniqueId(), other.getName(), true, "", false, "", 0));
        }
        // 3. an offline player found by exact name through the search box
        if (found != null && !found.id().equals(me) && !all.containsKey(found.id())) {
            all.put(found.id(), new Views.Contact(found.id(), found.name(), false, "", false, "", 0));
        }
        List<Views.Contact> list = new ArrayList<>();
        String needle = s.filter.toLowerCase(Locale.ROOT);
        for (Views.Contact c : all.values()) {
            if (needle.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(needle)) list.add(c);
        }
        int per = cfg.contactsPerPage;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int index = Math.floorMod(page, pages);
        List<Views.Contact> shown = list.subList(index * per, Math.min(list.size(), index * per + per));
        s.contactsPage = index;

        ScreenRenderer r = renderer(player);
        int w = r.contactButtonWidth();
        List<ActionButton> buttons = new ArrayList<>();
        for (Views.Contact c : shown) {
            ActionButton.Builder b = ActionButton.builder(r.contactButton(c)).width(w)
                    .action(DialogAction.customClick(Key.key(NAMESPACE, "conv/" + c.id() + "/0"), null));
            if (!c.preview().isEmpty()) b.tooltip(Component.text(c.preview()));
            buttons.add(b.build());
        }
        buttons.add(button(lang.get("ui.button.search"), w, "search"));
        if (!s.filter.isEmpty()) buttons.add(button(lang.get("ui.button.clear-search"), w, "clearsearch"));
        if (pages > 1) buttons.add(button(lang.get("ui.button.next-page"), w, "contacts/" + (index + 1)));
        List<DialogInput> inputs = List.of(DialogInput.text("q", lang.get("ui.contacts.search-label"))
                .width(Math.min(w, 200)).labelVisible(true).initial(s.filter).maxLength(16).build());
        Component body = r.contacts(new Views.Contacts(clock(), shown, index, pages, s.filter, totalUnread), below(0, 1, buttons.size(), 1));
        show(player, s, Screen.CONTACTS, body, r.bodyWidth(), inputs, buttons, 1, "ui.button.back", "home");
    }

    // ---- conversation -----------------------------------------------------------------------------------------------

    /**
     * @param cursor 0 = latest messages, a positive message id = the page before it, a negative id = the page after it
     */
    public void openConversation(Player player, UUID partner, long cursor) {
        if (!mayUse(player)) return;
        if (!player.hasPermission("phone.message")) {
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        if (partner.equals(player.getUniqueId())) return;
        Session s = session(player);
        ScreenRenderer r = renderer(player);
        Player online = Bukkit.getPlayer(partner);
        CompletableFuture<String> name = online != null ? CompletableFuture.completedFuture(online.getName()) : db.playerName(partner);
        CompletableFuture<ConversationPage> page = db.loadPage(player.getUniqueId(), partner, cursor, cfg.messagesPerPage,
                cfg.maxLinesPerPage, r::messageLines);
        Tasks.onMain(plugin, page.thenCombine(name, (p, n) -> Map.entry(p, n == null ? lang.plain("ui.contacts.unknown") : n)), result -> {
            if (!player.isOnline()) return;
            ConversationPage loaded = result.getKey();
            // opening the conversation marked its messages read: badge and phone item follow
            messages.unreadIs(player, loaded.unreadTotal());
            s.partner = partner;
            s.partnerName = result.getValue();
            s.cursor = cursor;
            showConversation(player, s, r, loaded);
        });
    }

    private void showConversation(Player player, Session s, ScreenRenderer r, ConversationPage page) {
        UUID me = player.getUniqueId();
        UUID partner = s.partner;
        Player other = Bukkit.getPlayer(partner);
        boolean online = other != null && player.canSee(other);
        boolean blocked = messages.hasBlocked(me, partner);
        List<Views.Bubble> bubbles = new ArrayList<>();
        for (Message m : page.messages()) {
            bubbles.add(new Views.Bubble(m.body(), m.sender().equals(me), stamp(m.sentAt()), m.read()));
        }
        String notice = s.notice;
        s.notice = "";

        List<ActionButton> buttons = new ArrayList<>();
        List<DialogInput> inputs = new ArrayList<>();
        int w = 70;
        buttons.add(button(lang.get("ui.button.send"), w, "send/" + partner));
        buttons.add(button(lang.get("ui.button.refresh"), w, "refresh/" + partner));
        buttons.add(button(lang.get(blocked ? "ui.button.unblock" : "ui.button.block"), w, "block/" + partner));
        if (page.hasOlder()) buttons.add(button(lang.get("ui.button.older"), w, "conv/" + partner + "/" + page.messages().getFirst().id()));
        if (page.hasNewer()) buttons.add(button(lang.get("ui.button.newer"), w, "conv/" + partner + "/-" + page.messages().getLast().id()));
        inputs.add(DialogInput.text("msg", lang.get("ui.conversation.input-label"))
                .width(200).labelVisible(false).initial(s.drafts.getOrDefault(partner, "")).maxLength(cfg.maxLength).build());

        Views.Conversation view = new Views.Conversation(clock(), partner, s.partnerName, online, bubbles, page.hasOlder(),
                page.hasNewer(), blocked, notice);
        Component body = r.conversation(view, below(1, 0, buttons.size(), 3));
        show(player, s, Screen.CONVERSATION, body, r.bodyWidth(), inputs, buttons, 3, "ui.button.back", "contacts/" + s.contactsPage);
    }

    /** Remembers what is in the text field; called for every button of the conversation screen. */
    private void keepDraft(Session s, DialogResponseView view) {
        if (s.screen != Screen.CONVERSATION || s.partner == null || view == null) return;
        String typed = view.getText("msg");
        if (typed == null || typed.isBlank()) s.drafts.remove(s.partner);
        else s.drafts.put(s.partner, typed.length() > cfg.maxLength ? typed.substring(0, cfg.maxLength) : typed);
    }

    private void send(Player player, Session s, UUID partner, DialogResponseView view) {
        if (s.sending) return;      // a double click before the next dialog arrived
        keepDraft(s, view);
        String typed = view == null ? null : view.getText("msg");
        s.sending = true;
        messages.send(player, partner, s.partnerName, typed, result -> {
            s.sending = false;
            if (!player.isOnline()) return;
            if (result == MessageService.SendResult.OK) {
                s.drafts.remove(partner);
                sounds.play(player, Sounds.Cue.SEND);
            } else {
                s.notice = lang.plain("ui.send-error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        "max", cfg.maxLength, "seconds", cfg.rateSeconds);
            }
            if (s.screen == Screen.CONVERSATION && partner.equals(s.partner)) openConversation(player, partner, 0);
        });
    }

    // ---- camera -----------------------------------------------------------------------------------------------------

    @Override
    public void show(Player player, int zoom, String location) {
        Session s = session(player);
        ScreenRenderer r = renderer(player);
        List<ActionButton> buttons = List.of(
                button(lang.get("ui.button.print"), 106, "print"),
                button(lang.get("ui.button.retake"), 106, "retake"));
        Component body = r.photoResult(new Views.PhotoResult(clock(), zoom, location), below(0, 0, 2, 2));
        show(player, s, Screen.PHOTO, body, r.bodyWidth(), List.of(), buttons, 2, "ui.button.close", "camclose");
    }

    // ---- clicks -----------------------------------------------------------------------------------------------------

    @EventHandler
    public void onClick(PlayerCustomClickEvent e) {
        Key id = e.getIdentifier();
        if (!id.namespace().equals(NAMESPACE)) return;
        if (!(e.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        Session s = session(player);
        String[] path = id.value().split("/");
        DialogResponseView view = e.getDialogResponseView();
        try {
            route(player, s, path, view);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            // a hand-made packet with a broken path: ignore it
            close(player, true);
        }
    }

    private void route(Player player, Session s, String[] path, DialogResponseView view) {
        String action = path[0];
        if (action.equals("close")) {
            close(player, true);
            return;
        }
        if (action.equals("camclose")) {
            close(player, true);
            camera.exit(player);
            return;
        }
        if (!player.hasPermission("phone.use") || !items.hasPhone(player)) {
            // the phone was dropped, taken or stored while the screen was open
            close(player, true);
            camera.exit(player);
            player.sendMessage(lang.get("error.no-phone"));
            return;
        }
        switch (action) {
            case "home" -> openHome(player);
            case "contacts" -> {
                keepDraft(s, view);
                openContacts(player, Integer.parseInt(path[1]));
            }
            case "search" -> {
                s.filter = TextWrapper.sanitize(view == null ? "" : view.getText("q"), 16);
                openContacts(player, 0);
            }
            case "clearsearch" -> {
                s.filter = "";
                openContacts(player, 0);
            }
            case "conv" -> {
                keepDraft(s, view);
                openConversation(player, UUID.fromString(path[1]), Long.parseLong(path[2]));
            }
            case "refresh" -> {
                keepDraft(s, view);
                openConversation(player, UUID.fromString(path[1]), 0);
            }
            case "send" -> send(player, s, UUID.fromString(path[1]), view);
            case "block" -> {
                keepDraft(s, view);
                UUID partner = UUID.fromString(path[1]);
                messages.setBlocked(player, partner, !messages.hasBlocked(player.getUniqueId(), partner));
                openConversation(player, partner, s.cursor);
            }
            case "camera" -> {
                close(player, true);
                camera.enter(player);
            }
            case "print" -> {
                close(player, true);
                camera.print(player);
            }
            case "retake" -> {
                close(player, true);
                camera.discard(player);
            }
            default -> close(player, true);
        }
    }

    // ---- incoming messages ------------------------------------------------------------------------------------------

    @Override
    public void incoming(Player recipient, Message message, String senderName) {
        sounds.play(recipient, Sounds.Cue.NOTIFY);
        Session s = sessions.get(recipient.getUniqueId());
        boolean watching = s != null && s.screen == Screen.CONVERSATION && message.sender().equals(s.partner);
        boolean onList = s != null && (s.screen == Screen.HOME || s.screen == Screen.CONTACTS);
        if (watching || onList) {
            // the screen is stale now; re-send it once, a little later, so a burst of messages is one refresh
            if (cfg.autoRefresh && s.refresh == null) {
                s.refresh = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    s.refresh = null;
                    if (!recipient.isOnline()) return;
                    switch (s.screen) {
                        case CONVERSATION -> openConversation(recipient, s.partner, 0);
                        case CONTACTS -> openContacts(recipient, s.contactsPage);
                        case HOME -> openHome(recipient);
                        default -> { }
                    }
                }, cfg.refreshDelayTicks);
            }
            if (watching) return;
        }
        hint(recipient, lang.get("message.hint", Lang.ph("player", senderName)));
    }

    @Override
    public void unreadChanged(Player player) {
        if (camera.isActive(player)) return;        // the viewfinder look stays until the camera closes
        items.sync(player, messages.unread(player.getUniqueId()) > 0 ? PhoneItems.State.UNREAD : PhoneItems.State.NORMAL);
    }

    @Override
    public void waitingOnJoin(Player player, int unread) {
        // after the join messages and the pack prompt
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || messages.unread(player.getUniqueId()) == 0) return;
            sounds.play(player, Sounds.Cue.NOTIFY);
            hint(player, lang.get("message.waiting", Lang.ph("count", messages.unread(player.getUniqueId()))));
        }, 60L);
    }

    private void hint(Player player, Component text) {
        switch (cfg.hint) {
            case ACTIONBAR -> player.sendActionBar(text);
            case CHAT -> player.sendMessage(text);
            case NONE -> { }
        }
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------------

    /**
     * Safety net for a dialog that closed without telling us (the client was sent another screen by someone else):
     * a player who presses a movement key has no screen open. Only cosmetic state hangs on this - the screen tint.
     */
    @EventHandler
    public void onInput(PlayerInputEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.screen == Screen.NONE || Bukkit.getCurrentTick() - s.shownTick < 20) return;
        var in = e.getInput();
        if (in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump()) close(e.getPlayer(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null && s.refresh != null) s.refresh.cancel();
        postFx.forget(e.getPlayer());
    }

    /** Closes every open phone screen (plugin disable, reload). */
    public void closeAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) close(p, true);
        }
    }
}
