package fi.alavesa.phone.ui;

import fi.alavesa.phone.camera.CameraService;
import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.media.Gallery;
import fi.alavesa.phone.media.MediaStore;
import fi.alavesa.phone.messaging.ContactRow;
import fi.alavesa.phone.messaging.ConversationPage;
import fi.alavesa.phone.messaging.Message;
import fi.alavesa.phone.messaging.MessageService;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.resourcepack.PackService;
import fi.alavesa.phone.social.Gram;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.storage.PlayerSettings;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
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
import org.bukkit.Location;
import org.bukkit.World;
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
 * {@link #onClick} routes it. Nothing is kept per dialog on the server, so an old dialog can never dangle. Buttons are
 * laid out in grids of 1, 2, 3 or 5 columns ({@link Grid}); on the drawn phone every row is filled up and a last row
 * with the home indicator closes the phone (it goes to the home screen).
 *
 * <p><b>Dialogs cannot be updated in place.</b> The only way to change what a player sees is to send a whole new
 * dialog, which resets its text field. Typed text therefore survives only when the client tells us about it: every
 * button on the conversation screen carries the field's content, and it is put back as the field's initial value. A
 * refresh the player did not ask for (a message arriving while the conversation is open) cannot know the text typed
 * since the last button press; see {@code messaging.auto-refresh}. Videos play the same way: one dialog per frame.
 *
 * <p><b>Closing.</b> The client sends nothing when a dialog closes, except that Escape runs the dialog's exit
 * action. Every screen has one (Back or Close), so Escape walks back one screen at a time and the server always
 * knows where the player is.
 *
 * <p><b>Lock.</b> With passcodes on, a phone that is opened from nothing shows the lock screen first; the session
 * stays unlocked while a screen is open (and while the camera is in use) and locks again when the phone closes.
 */
public final class UiController implements Listener, MessageService.Notifier, CameraService.ResultScreen, Gram.Notifier {

    private enum Screen { NONE, LOCK, HOME, CONTACTS, CONVERSATION, OWN_MESSAGES, EDIT_MESSAGE, SETTINGS, PHOTOS, SHARE,
        GRAM_FEED, GRAM_COMMENTS, GRAM_ACCOUNT, GRAM_COMPOSE, GRAM_SEARCH, GRAM_ACTIVITY, GRAM_DELETE }

    private static final class Session {
        Screen screen = Screen.NONE;
        boolean unlocked;
        int wrongCodes;
        long lockedUntil;
        UUID partner;
        String partnerName = "";
        /** How many of the newest messages are scrolled out below the conversation window. */
        int skip;
        String filter = "";
        int contactsPage;
        String notice = "";
        boolean sending;
        long shownTick;
        BukkitTask refresh;
        /** The video being shown (playing or paused) on the current screen; null when no video is on it. */
        Playback pb;
        final Map<UUID, String> drafts = new HashMap<>();
        // Photos
        int photoIndex;
        boolean fromCamera;
        long confirmDelete = -1;
        // Minestagram: the feed or profile being looked at, and what Back returns to
        UUID gramProfile;
        int gramIndex;
        Database.GramPost gramPost;
        int commentsSkip;
        String commentDraft = "";
        int composeIndex;
        String captionDraft = "";
        String gramQuery = "";
        long confirmPostDelete = -1;
        Runnable gramBack;
    }

    /**
     * A video on a screen: one dialog per frame while it plays; paused it is just the frame it stopped on, so the dialog
     * (and its scroll position) stays put. {@code buttons} builds the button grid for the playing / paused state.
     */
    private static final class Playback {
        BukkitTask task;
        MediaStore.Media media;
        /** The frame to send next / the frame on screen. */
        int at, shown;
        int step, interval;
        java.util.function.Function<int[], Component> render;
        java.util.function.Function<Boolean, List<ActionButton>> buttons;
        Screen screen;
        String exitLabel, exitPath;
        ScreenRenderer r;
        boolean paused = true;
    }

    private static final Key SCREEN_FX = Key.key("phone", "screen");
    private static final String NAMESPACE = "phone";
    private static final int COMMENTS_PER_PAGE = 4;

    private final Plugin plugin;
    private final Database db;
    private final MessageService messages;
    private final PhoneItems items;
    private final PackService pack;
    private final PlayerSettings settings;
    private final PostFx postFx;
    private final Sounds sounds;
    private final CameraService camera;
    private final Gallery gallery;
    private final Gram gram;
    private final Glyphs glyphs;
    private final FontWidths widths;
    private PhoneConfig cfg;
    private Lang lang;
    private ScreenRenderer fancy;
    private ScreenRenderer plain;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public UiController(Plugin plugin, Database db, MessageService messages, PhoneItems items, PackService pack, PlayerSettings settings,
                        PostFx postFx, Sounds sounds, CameraService camera, Gallery gallery, Gram gram, Glyphs glyphs, FontWidths widths,
                        PhoneConfig cfg, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.messages = messages;
        this.items = items;
        this.pack = pack;
        this.settings = settings;
        this.postFx = postFx;
        this.sounds = sounds;
        this.camera = camera;
        this.gallery = gallery;
        this.gram = gram;
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

    /** "now", "5m", "3h", "2d", or the date for older things. */
    private String age(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - millis));
        if (d.toMinutes() < 1) return lang.plain("ui.gram.now");
        if (d.toHours() < 1) return d.toMinutes() + "m";
        if (d.toDays() < 1) return d.toHours() + "h";
        if (d.toDays() < 7) return d.toDays() + "d";
        return Instant.ofEpochMilli(millis).atZone(cfg.zone).format(cfg.dateFormat);
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

    private static ActionButton button(Component label, int width, String path, Component tooltip) {
        ActionButton.Builder b = ActionButton.builder(label).width(width).action(DialogAction.customClick(Key.key(NAMESPACE, path), null));
        if (tooltip != null) b.tooltip(tooltip);
        return b.build();
    }

    private static DialogInput field(String key, Component label, boolean labelVisible, int width, String initial, int maxLength) {
        return DialogInput.text(key, label).width(width).labelVisible(labelVisible).initial(initial).maxLength(maxLength).build();
    }

    /** Height of the inputs a dialog stacks between its text and its buttons: 10 px gap + 20 px field (+13 with a label). */
    private static int inputs(int plainInputs, int labelledInputs) {
        return plainInputs * 30 + labelledInputs * 43;
    }

    /**
     * The buttons of a screen: a grid of 1, 2, 3 or 5 columns (the widths the pack's row bands exist for). On the
     * drawn phone {@link #finish} fills the last row with blank buttons and adds the home-indicator row.
     */
    private final class Grid {
        final ScreenRenderer r;
        final int columns;
        final List<ActionButton> out = new ArrayList<>();

        Grid(ScreenRenderer r, int columns) {
            this.r = r;
            this.columns = columns;
        }

        boolean first() {
            return out.size() % columns == 0;
        }

        Grid add(Views.Btn b) {
            Component tip = b.tooltip() == null ? null : Component.text(b.tooltip());
            out.add(button(r.button(b, columns, first()), r.buttonWidth(columns), b.path(), tip));
            return this;
        }

        Grid add(String icon, String label, Views.Tone tone, String path) {
            return add(new Views.Btn(icon, label, tone, path, null));
        }

        /** A button with a label of its own (contacts, accounts ...); {@code label} gets "is first in its row". */
        Grid add(java.util.function.Function<Boolean, Component> label, String path, String tooltip) {
            out.add(button(label.apply(first()), r.buttonWidth(columns), path, tooltip == null ? null : Component.text(tooltip)));
            return this;
        }

        List<ActionButton> finish() {
            if (!r.framed()) {
                if (out.isEmpty()) out.add(button(r.homeBar(), r.buttonWidth(1), "home", null));
                return out;
            }
            if (out.isEmpty()) add(Views.Btn.blank());
            while (out.size() % columns != 0) add(Views.Btn.blank());
            out.add(button(r.homeBar(), r.buttonWidth(1), "home", null));
            return out;
        }
    }

    private void show(Player player, Session s, Screen screen, Component body, List<DialogInput> inputs, Grid grid,
                      String exitLabel, String exitPath) {
        stopPlayback(s);
        showFrame(player, s, screen, body, inputs, grid.finish(), exitLabel, exitPath, grid.r);
    }

    private void showFrame(Player player, Session s, Screen screen, Component body, List<DialogInput> inputs, List<ActionButton> buttons,
                           String exitLabel, String exitPath, ScreenRenderer r) {
        if (s.refresh != null) {
            s.refresh.cancel();
            s.refresh = null;
        }
        DialogBase base = DialogBase.builder(lang.get("ui.title"))
                .canCloseWithEscape(true)
                .pause(false)
                // the dialog stays until the server answers with the next one: no flash of the world in between
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(body, r.bodyWidth())))
                .inputs(inputs)
                .build();
        ActionButton exit = button(lang.get(exitLabel), 120, exitPath, null);
        Dialog dialog = Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons, exit, columnsOf(buttons, r))));
        s.screen = screen;
        s.shownTick = Bukkit.getCurrentTick();
        if (cfg.screenEffect && pack.hasPack(player)) postFx.set(player, SCREEN_FX, true);
        player.showDialog(dialog);
    }

    /** Columns of a finished grid: read back from the width of its first button. */
    private static int columnsOf(List<ActionButton> buttons, ScreenRenderer r) {
        int w = buttons.getFirst().width();
        for (int c : new int[] {1, 2, 3, 5}) {
            if (r.buttonWidth(c) == w) return c;
        }
        return 1;
    }

    private void stopPlayback(Session s) {
        if (s.pb != null && s.pb.task != null) s.pb.task.cancel();
        s.pb = null;
    }

    private void sendFrame(Player player, Session s, Playback pb, int index) {
        pb.shown = index;
        showFrame(player, s, pb.screen, pb.render.apply(pb.media.frames().get(index)), List.of(), pb.buttons.apply(pb.paused), pb.exitLabel,
                pb.exitPath, pb.r);
    }

    /** Plays from {@code pb.at}; at the end it stops on the first frame, paused. */
    private void runPlayback(Player player, Session s, Playback pb) {
        if (pb.task != null) pb.task.cancel();
        pb.paused = false;
        int frames = pb.media.frames().size();
        pb.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || s.pb != pb) {
                if (pb.task != null) pb.task.cancel();
                return;
            }
            if (pb.at >= frames) {
                pb.task.cancel();
                pb.task = null;
                pb.paused = true;
                pb.at = 0;
                sendFrame(player, s, pb, 0);
                return;
            }
            int i = pb.at;
            pb.at = i + pb.step;
            sendFrame(player, s, pb, i);
        }, 0L, pb.interval);
    }

    /** Pause button: stops on the frame that is on screen; the dialog is sent once more with the Play button. */
    private void pausePlayback(Player player, Session s) {
        Playback pb = s.pb;
        if (pb == null || pb.paused) return;
        if (pb.task != null) pb.task.cancel();
        pb.task = null;
        pb.paused = true;
        pb.at = pb.shown;
        sendFrame(player, s, pb, pb.shown);
    }

    /** Play button: carries on from the frame it stopped on (or from the start when it had ended). */
    private void resumePlayback(Player player, Session s) {
        Playback pb = s.pb;
        if (pb == null || !pb.paused) return;
        runPlayback(player, s, pb);
    }

    /** Sets a video up on the current screen, paused on its first frame; {@code autoplay} starts it at once. */
    private Playback newPlayback(Player player, Session s, MediaStore.Media media, Screen screen, String exitLabel, String exitPath,
                                 ScreenRenderer r, java.util.function.Function<int[], Component> render,
                                 java.util.function.Function<Boolean, List<ActionButton>> buttons) {
        Playback pb = new Playback();
        pb.media = media;
        pb.step = Math.max(1, Math.round(media.frames().size() / (float) (media.seconds() * cfg.playbackFps)));
        pb.interval = Math.max(1, 20 / cfg.playbackFps);
        pb.render = render;
        pb.buttons = buttons;
        pb.screen = screen;
        pb.exitLabel = exitLabel;
        pb.exitPath = exitPath;
        pb.r = r;
        s.pb = pb;
        return pb;
    }

    /** Closes whatever phone screen is open; the phone is locked again. {@code closeDialog}: also tell the client. */
    public void close(Player player, boolean closeDialog) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.screen == Screen.NONE) return;
        if (s.refresh != null) s.refresh.cancel();
        s.refresh = null;
        stopPlayback(s);
        s.screen = Screen.NONE;
        s.sending = false;
        postFx.set(player, SCREEN_FX, false);
        if (closeDialog) player.closeDialog();
    }

    public boolean isOpen(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.screen != Screen.NONE;
    }

    // ---- lock screen ------------------------------------------------------------------------------------------------

    /**
     * True when the phone may show its apps. Otherwise the lock screen (or the first-time passcode set-up) has been
     * shown instead and the caller must stop.
     */
    private boolean unlocked(Player player, Session s) {
        if (!cfg.passcodeEnabled) return true;
        // a phone opened from nothing starts locked; switching screens (or coming back from the camera) does not
        if (s.screen == Screen.NONE && !camera.isActive(player)) s.unlocked = false;
        if (s.unlocked) return true;
        if (!settings.loaded(player)) {
            player.sendMessage(lang.get("error.not-ready"));
            return false;
        }
        showLock(player, s, settings.hasPasscode(player.getUniqueId()) ? Views.LockMode.LOCKED : Views.LockMode.SETUP);
        return false;
    }

    /** Lets the next screen through without the passcode (the admin command /phone open). */
    public void unlockFor(Player player) {
        session(player).unlocked = true;
        // unlocked() resets the flag for a closed phone: mark it as "open" until the screen arrives
        if (session(player).screen == Screen.NONE) session(player).screen = Screen.LOCK;
    }

    private String date() {
        ZonedDateTime now = ZonedDateTime.now(cfg.zone);
        return now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + now.format(cfg.dateFormat);
    }

    private void showLock(Player player, Session s, Views.LockMode mode) {
        ScreenRenderer r = renderer(player);
        long wait = (s.lockedUntil - System.currentTimeMillis() + 999) / 1000;
        String notice = s.notice;
        s.notice = "";
        if (mode == Views.LockMode.LOCKED && wait > 0) notice = lang.plain("ui.lock.locked-out", "seconds", wait);

        List<DialogInput> inputs = new ArrayList<>();
        Grid grid = new Grid(r, 1);
        int w = r.buttonWidth(1);
        switch (mode) {
            case LOCKED -> {
                inputs.add(field("pin", lang.get("ui.lock.field"), true, w, "", 4));
                grid.add(null, lang.plain("ui.button.sign-in"), Views.Tone.ACCENT, "unlock");
            }
            case SETUP -> {
                inputs.add(field("new", lang.get("ui.lock.field-new"), true, w, "", 4));
                inputs.add(field("again", lang.get("ui.lock.field-again"), true, w, "", 4));
                grid.add(null, lang.plain("ui.button.set-passcode"), Views.Tone.ACCENT, "setcode");
            }
            case CHANGE -> {
                inputs.add(field("old", lang.get("ui.lock.field-old"), true, w, "", 4));
                inputs.add(field("new", lang.get("ui.lock.field-new"), true, w, "", 4));
                inputs.add(field("again", lang.get("ui.lock.field-again"), true, w, "", 4));
                grid.add(null, lang.plain("ui.button.save"), Views.Tone.ACCENT, "changecode");
            }
        }
        Component body = r.lock(new Views.Lock(clock(), date(), player.getName(), mode, notice), inputs(0, inputs.size()));
        boolean change = mode == Views.LockMode.CHANGE;
        show(player, s, change ? Screen.SETTINGS : Screen.LOCK, body, inputs, grid,
                change ? "ui.button.back" : "ui.button.close", change ? "settings" : "close");
    }

    private void unlock(Player player, Session s, DialogResponseView view) {
        long now = System.currentTimeMillis();
        if (now < s.lockedUntil) {
            showLock(player, s, Views.LockMode.LOCKED);
            return;
        }
        if (settings.checkPasscode(player.getUniqueId(), view == null ? null : view.getText("pin"))) {
            s.unlocked = true;
            s.wrongCodes = 0;
            openHome(player);
            return;
        }
        s.wrongCodes++;
        if (s.wrongCodes >= cfg.passcodeAttempts) {
            s.wrongCodes = 0;
            s.lockedUntil = now + cfg.passcodeLockoutSeconds * 1000L;
        } else {
            s.notice = lang.plain("ui.lock.wrong", "left", cfg.passcodeAttempts - s.wrongCodes);
        }
        showLock(player, s, Views.LockMode.LOCKED);
    }

    /** First-time set-up ({@code change} false) or the Settings app ({@code change} true, old code required). */
    private void setCode(Player player, Session s, DialogResponseView view, boolean change) {
        UUID id = player.getUniqueId();
        String fresh = view == null ? null : view.getText("new");
        String again = view == null ? null : view.getText("again");
        Views.LockMode mode = change ? Views.LockMode.CHANGE : Views.LockMode.SETUP;
        if (!change && settings.hasPasscode(id)) {      // a stale set-up dialog: the code exists already
            showLock(player, s, Views.LockMode.LOCKED);
            return;
        }
        if (change && !settings.checkPasscode(id, view == null ? null : view.getText("old"))) {
            s.notice = lang.plain("ui.lock.wrong-old");
        } else if (!PlayerSettings.validCode(fresh)) {
            s.notice = lang.plain("ui.lock.not-four-digits");
        } else if (!fresh.equals(again)) {
            s.notice = lang.plain("ui.lock.mismatch");
        } else {
            settings.setPasscode(id, fresh);
            s.unlocked = true;
            player.sendMessage(lang.get("passcode.set"));
            if (change) openSettings(player);
            else openHome(player);
            return;
        }
        showLock(player, s, mode);
    }

    // ---- home -------------------------------------------------------------------------------------------------------

    public void openHome(Player player) {
        if (!mayUse(player)) return;
        if (camera.isActive(player)) camera.exit(player);
        Session s = session(player);
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        int unread = messages.unread(player.getUniqueId());
        Grid grid = new Grid(r, 1);
        if (player.hasPermission("phone.message")) {
            grid.add(first -> r.appButton("messages", lang.plain("ui.button.messages"), unread, first), "contacts/0", null);
        }
        if (cfg.cameraEnabled && player.hasPermission("phone.camera")) {
            grid.add(first -> r.appButton("camera", lang.plain("ui.button.camera"), 0, first), "camera", null);
            grid.add(first -> r.appButton("photos", lang.plain("ui.button.photos"), 0, first), "photos/0", null);
        }
        if (gram.mayUse(player)) grid.add(first -> r.appButton("gram", lang.plain("ui.button.gram"), 0, first), "gram", null);
        grid.add(first -> r.appButton("settings", lang.plain("ui.button.settings"), 0, first), "settings", null);
        Component body = r.home(new Views.Home(clock(), date(), player.getName(), unread, 0), 0);
        show(player, s, Screen.HOME, body, List.of(), grid, "ui.button.close", "close");
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    public void openSettings(Player player) {
        if (!mayUse(player)) return;
        Session s = session(player);
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        Grid grid = new Grid(r, 1);
        if (cfg.passcodeEnabled) grid.add("lock", lang.plain("ui.button.change-passcode"), Views.Tone.NORMAL, "passcode");
        grid.add(null, lang.plain("ui.button.resend-pack"), Views.Tone.NORMAL, "repack");
        String packLine = lang.plain(pack.hasPack(player) ? "ui.settings.pack-on" : "ui.settings.pack-off");
        Component body = r.settings(new Views.Settings(clock(), player.getName(), packLine), 0);
        show(player, s, Screen.SETTINGS, body, List.of(), grid, "ui.button.back", "home");
    }

    // ---- contacts ---------------------------------------------------------------------------------------------------

    public void openContacts(Player player, int page) {
        if (!mayUse(player)) return;
        if (!player.hasPermission("phone.message")) {
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        Session s = session(player);
        if (!unlocked(player, s)) return;
        String filter = s.filter;
        CompletableFuture<Database.KnownPlayer> found = filter.isEmpty()
                ? CompletableFuture.completedFuture(null) : db.findPlayer(filter);
        CompletableFuture<List<ContactRow>> rows = db.contacts(player.getUniqueId());
        Tasks.onMain(plugin, rows.thenCombine(found, (list, known) -> Map.entry(list, java.util.Optional.ofNullable(known))), result -> {
            if (!player.isOnline()) return;
            showContacts(player, s, page, result.getKey(), result.getValue().orElse(null));
        });
    }

    /** Everyone to show in the contact list: conversations first, then who is online, then a search hit. */
    private List<Views.Contact> contactList(Player player, List<ContactRow> rows, Database.KnownPlayer found, String filter) {
        UUID me = player.getUniqueId();
        Map<UUID, Views.Contact> all = new LinkedHashMap<>();
        for (ContactRow row : rows) {
            Player other = Bukkit.getPlayer(row.partner());
            boolean online = other != null && player.canSee(other);
            if (!online && !cfg.showOfflineContacts && row.unread() == 0) continue;
            String name = other != null ? other.getName() : row.name() != null ? row.name() : lang.plain("ui.contacts.unknown");
            all.put(row.partner(), new Views.Contact(row.partner(), name, online, row.lastBody(), row.lastFromMe(), stamp(row.lastTime()), row.unread()));
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player other : online) {
            if (other.getUniqueId().equals(me) || all.containsKey(other.getUniqueId()) || !player.canSee(other)) continue;
            all.put(other.getUniqueId(), new Views.Contact(other.getUniqueId(), other.getName(), true, "", false, "", 0));
        }
        if (found != null && !found.id().equals(me) && !all.containsKey(found.id())) {
            all.put(found.id(), new Views.Contact(found.id(), found.name(), false, "", false, "", 0));
        }
        List<Views.Contact> list = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT);
        for (Views.Contact c : all.values()) {
            if (needle.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(needle)) list.add(c);
        }
        return list;
    }

    private void showContacts(Player player, Session s, int page, List<ContactRow> rows, Database.KnownPlayer found) {
        int totalUnread = 0;
        for (ContactRow row : rows) totalUnread += row.unread();
        List<Views.Contact> list = contactList(player, rows, found, s.filter);
        int per = cfg.contactsPerPage;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int index = Math.floorMod(page, pages);
        List<Views.Contact> shown = list.subList(index * per, Math.min(list.size(), index * per + per));
        s.contactsPage = index;

        ScreenRenderer r = renderer(player);
        Grid grid = new Grid(r, 1);
        for (Views.Contact c : shown) {
            grid.add(first -> r.contactButton(c, first), "conv/" + c.id() + "/0", c.preview().isEmpty() ? null : c.preview());
        }
        grid.add("search", lang.plain("ui.button.search"), Views.Tone.NORMAL, "search");
        if (!s.filter.isEmpty()) grid.add(null, lang.plain("ui.button.clear-search"), Views.Tone.DIM, "clearsearch");
        if (pages > 1) grid.add("right", lang.plain("ui.button.next-page"), Views.Tone.DIM, "contacts/" + (index + 1));
        List<DialogInput> inputs = List.of(field("q", lang.get("ui.contacts.search-label"), true, r.buttonWidth(1), s.filter, 16));
        Component body = r.contacts(new Views.Contacts(clock(), shown, index, pages, s.filter, totalUnread), inputs(0, 1));
        show(player, s, Screen.CONTACTS, body, inputs, grid, "ui.button.back", "home");
    }

    // ---- conversation -----------------------------------------------------------------------------------------------

    /** @param skip how many of the newest messages to scroll past (0 = the end of the conversation) */
    public void openConversation(Player player, UUID partner, int skip) {
        if (!mayUse(player)) return;
        if (!player.hasPermission("phone.message")) {
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        if (partner.equals(player.getUniqueId())) return;
        Session s = session(player);
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        Player online = Bukkit.getPlayer(partner);
        CompletableFuture<String> name = online != null ? CompletableFuture.completedFuture(online.getName()) : db.playerName(partner);
        CompletableFuture<ConversationPage> page = db.loadPage(player.getUniqueId(), partner, Math.max(0, skip), cfg.messagesPerPage,
                cfg.maxLinesPerPage, m -> r.messageLines(m.body(), m.photo()));
        Tasks.onMain(plugin, page.thenCombine(name, (p, n) -> Map.entry(p, n == null ? lang.plain("ui.contacts.unknown") : n)), result -> {
            if (!player.isOnline()) return;
            ConversationPage loaded = result.getKey();
            if (loaded.messages().isEmpty() && skip > 0) {
                openConversation(player, partner, 0);       // scrolled past the beginning
                return;
            }
            // opening the conversation marked its messages read: badge and phone item follow
            messages.unreadIs(player, loaded.unreadTotal());
            s.partner = partner;
            s.partnerName = result.getValue();
            s.skip = Math.max(0, skip);
            // pictures sent in messages: their thumbnails are read from disk before the screen is drawn
            Map<Long, CompletableFuture<MediaStore.Media>> files = new HashMap<>();
            for (Message m : loaded.messages()) if (m.photo()) files.put(m.id(), messages.attachment(m.id()));
            if (files.isEmpty()) {
                showConversation(player, s, r, loaded, Map.of());
                return;
            }
            Tasks.onMain(plugin, CompletableFuture.allOf(files.values().toArray(CompletableFuture[]::new)).thenApply(v -> {
                Map<Long, int[]> thumbs = new HashMap<>();
                files.forEach((id, f) -> {
                    MediaStore.Media media = f.join();
                    if (media != null) thumbs.put(id, Thumb.of(media.poster()));
                });
                return thumbs;
            }), thumbs -> {
                if (player.isOnline() && s.screen != Screen.NONE && partner.equals(s.partner)) showConversation(player, s, r, loaded, thumbs);
            });
        });
    }

    private Views.Bubble bubble(UUID me, Message m) {
        return bubble(me, m, null);
    }

    private Views.Bubble bubble(UUID me, Message m, int[] thumb) {
        return new Views.Bubble(m.body(), m.sender().equals(me), stamp(m.sentAt()), m.read(), m.edited(), m.photo(), thumb);
    }

    private void showConversation(Player player, Session s, ScreenRenderer r, ConversationPage page, Map<Long, int[]> thumbs) {
        UUID me = player.getUniqueId();
        UUID partner = s.partner;
        Player other = Bukkit.getPlayer(partner);
        boolean online = other != null && player.canSee(other);
        boolean blocked = messages.hasBlocked(me, partner);
        List<Views.Bubble> bubbles = new ArrayList<>();
        for (Message m : page.messages()) bubbles.add(bubble(me, m, thumbs.get(m.id())));
        String notice = s.notice;
        s.notice = "";

        Grid grid = new Grid(r, 3);
        grid.add(null, lang.plain("ui.button.send"), Views.Tone.ACCENT, "send/" + partner);
        grid.add(null, lang.plain("ui.button.refresh"), Views.Tone.NORMAL, "refresh/" + partner);
        if (cfg.allowEdit || cfg.allowDelete) grid.add("pencil", lang.plain("ui.button.edit"), Views.Tone.NORMAL, "mine/" + partner);
        // scrolling: the window moves scroll-step messages at a time
        if (page.hasOlder()) grid.add("up", lang.plain("ui.button.older"), Views.Tone.DIM, "conv/" + partner + "/" + (s.skip + cfg.scrollStep));
        if (page.hasNewer()) grid.add("down", lang.plain("ui.button.newer"), Views.Tone.DIM, "conv/" + partner + "/" + Math.max(0, s.skip - cfg.scrollStep));
        grid.add(null, lang.plain(blocked ? "ui.button.unblock" : "ui.button.block"), Views.Tone.DANGER, "block/" + partner);
        List<DialogInput> inputs = List.of(field("msg", lang.get("ui.conversation.input-label"), false, r.buttonWidth(1),
                s.drafts.getOrDefault(partner, ""), cfg.maxLength));

        Views.Conversation view = new Views.Conversation(clock(), partner, s.partnerName, online, bubbles, page.hasOlder(),
                page.hasNewer(), blocked, notice);
        Component body = r.conversation(view, inputs(1, 0));
        show(player, s, Screen.CONVERSATION, body, inputs, grid, "ui.button.back", "contacts/" + s.contactsPage);
    }

    /** Remembers what is in the text field; called for every button of the conversation screen. */
    private void keepDraft(Session s, DialogResponseView view) {
        if (s.screen != Screen.CONVERSATION || s.partner == null || view == null) return;
        String typed = view.getText("msg");
        if (typed == null || typed.isBlank()) s.drafts.remove(s.partner);
        else s.drafts.put(s.partner, typed.length() > cfg.maxLength ? typed.substring(0, cfg.maxLength) : typed);
    }

    private String error(MessageService.SendResult result) {
        return lang.plain("ui.send-error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                "max", cfg.maxLength, "seconds", cfg.rateSeconds, "minutes", cfg.editWindowMinutes);
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
                s.notice = error(result);
            }
            if (s.screen == Screen.CONVERSATION && partner.equals(s.partner)) openConversation(player, partner, 0);
        });
    }

    // ---- editing and deleting own messages --------------------------------------------------------------------------

    private void openOwnMessages(Player player, Session s, UUID partner) {
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.ownMessages(player.getUniqueId(), partner, cfg.editableMessages), list -> {
            if (!player.isOnline()) return;
            String notice = s.notice;
            s.notice = "";
            Grid grid = new Grid(r, 1);
            int offered = 0;
            for (Message m : list) {
                if (messages.mayChange(player, m, !cfg.allowEdit) != MessageService.SendResult.OK) continue;
                Views.Bubble b = bubble(player.getUniqueId(), m);
                grid.add(first -> r.ownMessageButton(b, first), "editmsg/" + partner + "/" + m.id(), null);
                offered++;
            }
            Component body = r.ownMessages(new Views.OwnMessages(clock(), s.partnerName, offered, notice), 0);
            show(player, s, Screen.OWN_MESSAGES, body, List.of(), grid, "ui.button.back", "conv/" + partner + "/" + s.skip);
        });
    }

    private void openEditMessage(Player player, Session s, UUID partner, long id) {
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.message(id), m -> {
            if (!player.isOnline()) return;
            if (m == null || !m.sender().equals(player.getUniqueId())) {
                s.notice = error(MessageService.SendResult.NOT_YOURS);
                openOwnMessages(player, s, partner);
                return;
            }
            String notice = s.notice;
            s.notice = "";
            Grid grid = new Grid(r, 2);
            List<DialogInput> inputs = new ArrayList<>();
            if (cfg.allowEdit) {
                inputs.add(field("edit", lang.get("ui.conversation.input-label"), false, r.buttonWidth(1), m.body(), cfg.maxLength));
                grid.add(null, lang.plain("ui.button.save"), Views.Tone.ACCENT, "save/" + partner + "/" + id);
            }
            if (cfg.allowDelete) grid.add("trash", lang.plain("ui.button.delete"), Views.Tone.DANGER, "del/" + partner + "/" + id);
            Views.EditMessage view = new Views.EditMessage(clock(), s.partnerName, bubble(player.getUniqueId(), m), notice);
            Component body = r.editMessage(view, inputs(inputs.size(), 0));
            show(player, s, Screen.EDIT_MESSAGE, body, inputs, grid, "ui.button.back", "mine/" + partner);
        });
    }

    private void saveEdit(Player player, Session s, UUID partner, long id, DialogResponseView view) {
        if (s.sending) return;
        s.sending = true;
        messages.edit(player, id, s.partnerName, view == null ? null : view.getText("edit"), result -> {
            s.sending = false;
            if (!player.isOnline()) return;
            if (result == MessageService.SendResult.OK) {
                openConversation(player, partner, s.skip);
            } else {
                s.notice = error(result);
                openEditMessage(player, s, partner, id);
            }
        });
    }

    private void deleteMessage(Player player, Session s, UUID partner, long id) {
        if (s.sending) return;
        s.sending = true;
        messages.delete(player, id, result -> {
            s.sending = false;
            if (!player.isOnline()) return;
            if (result != MessageService.SendResult.OK) s.notice = error(result);
            openConversation(player, partner, s.skip);
        });
    }

    // ---- Photos -----------------------------------------------------------------------------------------------------

    /** The picture taken a moment ago: the Photos app opens on it, Back returns to the viewfinder. */
    @Override
    public void developed(Player player, long galleryId) {
        Session s = session(player);
        s.fromCamera = true;
        openPhotos(player, 0);
    }

    public void openPhotos(Player player, int index) {
        if (!mayUse(player)) return;
        Session s = session(player);
        if (!unlocked(player, s)) return;
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, gallery.list(player.getUniqueId()), list -> {
            if (!player.isOnline()) return;
            if (list.isEmpty()) {
                showPhotos(player, s, r, list, 0, null);
                return;
            }
            int at = Math.floorMod(index, list.size());
            Tasks.onMain(plugin, gallery.media(list.get(at).id()), media -> {
                if (player.isOnline()) showPhotos(player, s, r, list, at, media);
            });
        });
    }

    private String photoInfo(Database.GalleryItem item, MediaStore.Media media) {
        String when = stamp(item.takenAt());
        String what = item.video() ? lang.plain("ui.photos.video", "seconds", String.format(Locale.ROOT, "%.1f", item.frames() / (double) item.fps())) : "";
        String from = item.fromName() == null ? "" : lang.plain("ui.photos.from", "player", item.fromName());
        StringBuilder sb = new StringBuilder(when);
        if (!what.isEmpty()) sb.append("  ").append(what);
        if (!from.isEmpty()) sb.append("  ").append(from);
        return sb.toString();
    }

    private void showPhotos(Player player, Session s, ScreenRenderer r, List<Database.GalleryItem> list, int at, MediaStore.Media media) {
        s.photoIndex = at;
        String notice = s.notice;
        s.notice = "";
        Database.GalleryItem item = list.isEmpty() ? null : list.get(at);
        boolean video = item != null && item.video();
        Grid grid = new Grid(r, 3);
        if (item != null) {
            boolean many = list.size() > 1;
            grid.add(null, lang.plain("ui.button.prev"), many ? Views.Tone.NORMAL : Views.Tone.DIM, many ? "photos/" + (at - 1) : "nop");
            if (video && media != null) grid.add("play", lang.plain("ui.button.play"), Views.Tone.NORMAL, "photoplay/" + at);
            else grid.add(null, (at + 1) + "/" + list.size(), Views.Tone.DIM, "nop");
            grid.add(null, lang.plain("ui.button.next"), many ? Views.Tone.NORMAL : Views.Tone.DIM, many ? "photos/" + (at + 1) : "nop");
            grid.add(null, lang.plain(video ? "ui.button.keep" : "ui.button.print"), Views.Tone.PRINT, "photoprint/" + item.id());
            grid.add(null, lang.plain("ui.button.share"), Views.Tone.ACCENT, "photoshare/" + item.id());
            boolean confirm = s.confirmDelete == item.id();
            grid.add(null, lang.plain(confirm ? "ui.button.confirm-delete" : "ui.button.delete"), Views.Tone.DANGER, "photodel/" + item.id());
        } else if (cfg.cameraEnabled && player.hasPermission("phone.camera")) {
            grid.add("camera", lang.plain("ui.button.camera"), Views.Tone.NORMAL, "camera");
        }
        if (item == null || s.confirmDelete != item.id()) s.confirmDelete = -1;
        int[] photo = media == null ? null : media.poster();
        if (item != null && media == null) notice = lang.plain("ui.photos.missing");
        String info = item == null ? "" : photoInfo(item, media);
        Component body = r.photos(new Views.Photos(clock(), photo, video, at, list.size(), info, notice), 0);
        boolean back = s.fromCamera && camera.isActive(player);
        show(player, s, Screen.PHOTOS, body, List.of(), grid, back ? "ui.button.camera" : "ui.button.back", back ? "camback" : "home");
    }

    /** Plays a video inside the Photos screen: the screen is sent again for every frame, with Pause and Play to hold it. */
    private void playPhoto(Player player, Session s, int index) {
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, gallery.list(player.getUniqueId()), list -> {
            if (!player.isOnline() || list.isEmpty()) return;
            int at = Math.floorMod(index, list.size());
            Database.GalleryItem item = list.get(at);
            Tasks.onMain(plugin, gallery.media(item.id()), media -> {
                if (!player.isOnline() || media == null) return;
                String info = photoInfo(item, media);
                boolean back = s.fromCamera && camera.isActive(player);
                stopPlayback(s);
                Playback pb = newPlayback(player, s, media, Screen.PHOTOS, back ? "ui.button.camera" : "ui.button.back", back ? "camback" : "home", r,
                        frame -> r.photos(new Views.Photos(clock(), frame, true, at, list.size(), info, ""), 0), paused -> {
                            Grid grid = new Grid(r, 3);
                            grid.add(null, lang.plain("ui.button.prev"), Views.Tone.DIM, "photos/" + (at - 1));
                            if (paused) grid.add("play", lang.plain("ui.button.play"), Views.Tone.ACCENT, "pbplay");
                            else grid.add("pause", lang.plain("ui.button.pause"), Views.Tone.ACCENT, "pbpause");
                            grid.add(null, lang.plain("ui.button.next"), Views.Tone.DIM, "photos/" + (at + 1));
                            grid.add(null, lang.plain("ui.button.keep"), Views.Tone.PRINT, "photoprint/" + item.id());
                            grid.add(null, lang.plain("ui.button.share"), Views.Tone.ACCENT, "photoshare/" + item.id());
                            grid.add(null, lang.plain("ui.button.delete"), Views.Tone.DANGER, "photodel/" + item.id());
                            return grid.finish();
                        });
                runPlayback(player, s, pb);
            });
        });
    }

    private void printPhoto(Player player, Session s, long id) {
        Tasks.onMain(plugin, gallery.item(id), item -> {
            if (!player.isOnline() || item == null || !item.owner().equals(player.getUniqueId())) return;
            Tasks.onMain(plugin, gallery.media(id), media -> {
                if (!player.isOnline()) return;
                if (media == null) {
                    s.notice = lang.plain("ui.photos.missing");
                } else {
                    World world = item.world() == null ? null : Bukkit.getWorld(item.world());
                    Location where = world == null ? player.getLocation() : new Location(world, item.x(), item.y(), item.z());
                    if (camera.printMedia(player, media.frames(), media.fps(), item.takenAt(), where)) {
                        s.notice = lang.plain(media.video() ? "ui.photos.video-saved" : "ui.photos.printed");
                    }
                }
                openPhotos(player, s.photoIndex);
            });
        });
    }

    private void deletePhoto(Player player, Session s, long id) {
        if (s.confirmDelete != id) {
            s.confirmDelete = id;
            s.notice = lang.plain("ui.photos.confirm-delete");
            openPhotos(player, s.photoIndex);
            return;
        }
        s.confirmDelete = -1;
        Tasks.onMain(plugin, gallery.delete(id, player.getUniqueId()), gone -> {
            if (!player.isOnline()) return;
            s.notice = lang.plain(gone ? "ui.photos.deleted" : "ui.photos.missing");
            openPhotos(player, s.photoIndex);
        });
    }

    // ---- sharing a picture ------------------------------------------------------------------------------------------

    private void openShare(Player player, Session s, long id) {
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.contacts(player.getUniqueId()), rows -> {
            if (!player.isOnline()) return;
            Grid grid = new Grid(r, 1);
            if (gram.mayUse(player)) grid.add("plus", lang.plain("ui.share.gram"), Views.Tone.ACCENT, "sharegram/" + id);
            if (player.hasPermission("phone.message")) {
                int n = 0;
                for (Views.Contact c : contactList(player, rows, null, "")) {
                    if (n++ >= 5) break;
                    grid.add(first -> r.contactButton(new Views.Contact(c.id(), c.name(), c.online(), "", false, "", 0), first),
                            "sendphoto/" + id + "/" + c.id(), lang.plain("ui.share.send-to", "player", c.name()));
                }
            }
            String notice = s.notice;
            s.notice = "";
            show(player, s, Screen.SHARE, r.share(new Views.Share(clock(), notice), 0), List.of(), grid, "ui.button.back",
                    "photos/" + s.photoIndex);
        });
    }

    /** Sends a picture to another player: a copy goes into their Photos and a message tells them. */
    private void sendPhoto(Player player, Session s, long id, UUID to) {
        if (s.sending) return;
        s.sending = true;
        Tasks.onMain(plugin, db.playerName(to), name -> {
            String toName = name == null ? lang.plain("ui.contacts.unknown") : name;
            Tasks.onMain(plugin, gallery.media(id), media -> {
                if (media == null) {
                    s.sending = false;
                    s.notice = lang.plain("ui.photos.missing");
                    if (player.isOnline()) openPhotos(player, s.photoIndex);
                    return;
                }
                // the picture rides in the message itself (it shows in the conversation) and a copy goes into their Photos
                messages.send(player, to, toName, lang.plain("ui.share.message"), media.poster(), result -> {
                    if (result != MessageService.SendResult.OK) {
                        s.sending = false;
                        s.notice = error(result);
                        openShare(player, s, id);
                        return;
                    }
                    Tasks.onMain(plugin, gallery.sendCopy(id, to, player.getName()), copy -> {
                        s.sending = false;
                        if (!player.isOnline()) return;
                        s.notice = copy >= 0 ? lang.plain("ui.share.sent", "player", toName)
                                : copy == -1 ? lang.plain("ui.share.full", "player", toName) : lang.plain("ui.photos.missing");
                        openPhotos(player, s.photoIndex);
                    });
                });
            });
        });
    }

    // ---- Minestagram ------------------------------------------------------------------------------------------------

    private String gramError(Gram.Result result) {
        return lang.plain("ui.gram.error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                "max", cfg.gramCaptionLength, "seconds", cfg.gramPostCooldownSeconds);
    }

    /** Opens Minestagram: the account set-up the first time, else the feed. */
    public void openGram(Player player) {
        if (!mayUse(player)) return;
        if (camera.isActive(player)) camera.exit(player);
        Session s = session(player);
        if (!unlocked(player, s)) return;
        if (!gram.mayUse(player)) {
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        Tasks.onMain(plugin, gram.account(player.getUniqueId()), acct -> {
            if (!player.isOnline()) return;
            if (acct == null) openAccount(player, s, true, Gram.suggestHandle(player.getName()), "");
            else openFeed(player, s, null, 0);
        });
    }

    private void openAccount(Player player, Session s, boolean create, String handle, String bio) {
        ScreenRenderer r = renderer(player);
        String notice = s.notice;
        s.notice = "";
        int w = r.buttonWidth(1);
        List<DialogInput> inputs = List.of(field("handle", lang.get("ui.gram.handle-label"), true, w, handle, 16),
                field("bio", lang.get("ui.gram.bio-label"), true, w, bio, cfg.gramBioLength));
        Grid grid = new Grid(r, 1).add(null, lang.plain(create ? "ui.button.create" : "ui.button.save"), Views.Tone.ACCENT, "gramsave/" + create);
        Component body = r.account(new Views.Account(clock(), create, notice), inputs(0, 2));
        show(player, s, Screen.GRAM_ACCOUNT, body, inputs, grid, "ui.button.back", create ? "home" : "gramme");
    }

    private void saveAccount(Player player, Session s, boolean create, DialogResponseView view) {
        String handle = view == null ? "" : view.getText("handle");
        String bio = view == null ? "" : view.getText("bio");
        gram.saveAccount(player, handle, bio, result -> {
            if (!player.isOnline()) return;
            if (result == Gram.Result.OK) {
                openFeed(player, s, create ? null : player.getUniqueId(), 0);
            } else {
                s.notice = gramError(result);
                openAccount(player, s, create, handle == null ? "" : handle, bio == null ? "" : bio);
            }
        });
    }

    /**
     * The feed ({@code profile} null) or one account's page, at entry {@code index}. The account's own header comes
     * from the database too.
     */
    private void openFeed(Player player, Session s, UUID profile, int index) {
        ScreenRenderer r = renderer(player);
        UUID me = player.getUniqueId();
        CompletableFuture<Database.GramAccount> acct = profile == null ? CompletableFuture.completedFuture(null) : gram.account(profile);
        Tasks.onMain(plugin, db.gramFeed(me, profile, index).thenCombine(acct, java.util.AbstractMap.SimpleImmutableEntry::new), result -> {
            if (!player.isOnline()) return;
            Database.GramPage page = result.getKey();
            Database.GramPost post = page.post();
            if (post == null) {
                showFeed(player, s, r, profile, result.getValue(), page, null);
                return;
            }
            Tasks.onMain(plugin, gram.media().read(post.id()), media -> {
                if (player.isOnline()) showFeed(player, s, r, profile, result.getValue(), page, media);
            });
        });
    }

    private Views.Post postView(Database.GramPost p, int[] photo) {
        return new Views.Post(p.author(), p.handle(), age(p.createdAt()), p.caption(), photo, p.frames() > 1, p.likes(), p.comments(),
                p.reposts(), p.liked(), p.reposted(), p.reposter());
    }

    private static String count(int n) {
        return n > 99 ? "99+" : String.valueOf(n);
    }

    /**
     * The feed's buttons. A video post gets a Pause / Play button in the middle of the first row; the repost / delete
     * button moves to the second row (where "new post" is on photo posts), because the grid is five columns wide.
     */
    private Grid feedGrid(ScreenRenderer r, Session s, UUID me, UUID profile, Database.GramPage page, boolean video, boolean paused,
                          Player player) {
        Database.GramPost post = page.post();
        String where = profile == null ? "gramf/" : "gramp/" + profile + "/";
        Grid grid = new Grid(r, 5);
        boolean many = page.total() > 1;
        Views.Btn deleteOrRepost = null;
        if (post != null) {
            boolean own = post.author().equals(me);
            grid.add("left", "", many && page.index() > 0 ? Views.Tone.NORMAL : Views.Tone.DIM, many ? where + (page.index() - 1) : "nop");
            grid.add(new Views.Btn(post.liked() ? "heart_full" : "heart", count(post.likes()), post.liked() ? Views.Tone.ACTIVE : Views.Tone.NORMAL,
                    "gramlike", lang.plain(post.liked() ? "ui.gram.unlike" : "ui.gram.like")));
            grid.add(new Views.Btn("comment", count(post.comments()), Views.Tone.NORMAL, "gramcom/0", lang.plain("ui.gram.comments")));
            if (own || (gram.moderator(player) && s.confirmPostDelete == post.entryId())) {
                boolean confirm = s.confirmPostDelete == post.entryId();
                deleteOrRepost = new Views.Btn("trash", confirm ? "?" : "", Views.Tone.DANGER, "gramdel",
                        lang.plain(confirm ? "ui.gram.confirm-delete" : "ui.gram.delete"));
            } else {
                deleteOrRepost = new Views.Btn("repost", count(post.reposts()), post.reposted() ? Views.Tone.ACCENT : Views.Tone.NORMAL, "gramrp",
                        lang.plain(post.reposted() ? "ui.gram.unrepost" : "ui.gram.repost"));
            }
            if (video) {
                grid.add(new Views.Btn(paused ? "play" : "pause", "", Views.Tone.ACCENT, paused ? "pbplay" : "pbpause",
                        lang.plain(paused ? "ui.button.play" : "ui.button.pause")));
            } else {
                grid.add(deleteOrRepost);
            }
            grid.add("right", "", many && page.index() < page.total() - 1 ? Views.Tone.NORMAL : Views.Tone.DIM,
                    many ? where + (page.index() + 1) : "nop");
        }
        boolean ownProfile = profile != null && profile.equals(me);
        grid.add(new Views.Btn("home", "", profile == null ? Views.Tone.NORMAL : Views.Tone.DIM, "gramf/0", lang.plain("ui.gram.feed")));
        grid.add(new Views.Btn("search", "", Views.Tone.DIM, "gramsearch", lang.plain("ui.gram.search")));
        if (video && deleteOrRepost != null) grid.add(deleteOrRepost);
        else grid.add(new Views.Btn("plus", "", Views.Tone.DIM, "gramnew/0", lang.plain("ui.gram.new-post")));
        grid.add(new Views.Btn("heart", "", Views.Tone.DIM, "gramact", lang.plain("ui.gram.activity")));
        grid.add(new Views.Btn("profile", "", ownProfile ? Views.Tone.NORMAL : Views.Tone.DIM, "gramme",
                lang.plain(ownProfile ? "ui.gram.edit" : "ui.gram.profile")));
        return grid;
    }

    private void showFeed(Player player, Session s, ScreenRenderer r, UUID profile, Database.GramAccount acct, Database.GramPage page,
                          MediaStore.Media media) {
        UUID me = player.getUniqueId();
        s.gramProfile = profile;
        s.gramIndex = page.index();
        s.gramPost = page.post();
        String notice = s.notice;
        s.notice = "";
        Database.GramPost post = page.post();
        boolean video = post != null && media != null && media.video();
        if (post != null && s.confirmPostDelete != post.entryId()) s.confirmPostDelete = -1;
        Grid grid = feedGrid(r, s, me, profile, page, video, true, player);
        boolean ownProfile = profile != null && profile.equals(me);
        Views.Profile header = profile == null ? null
                : new Views.Profile(profile, acct == null ? "?" : acct.handle(), acct == null ? "" : acct.bio(), acct == null ? 0 : acct.posts(), ownProfile);
        int[] photo = media == null ? null : media.poster();
        Views.Feed view = new Views.Feed(clock(), post == null ? null : postView(post, photo), page.index(), page.total(), header, notice);
        Component body = r.feed(view, 0);
        String exit = profile == null ? "home" : "gramf/0";
        s.gramBack = () -> openFeed(player, s, profile, page.index());
        show(player, s, Screen.GRAM_FEED, body, List.of(), grid, "ui.button.back", exit);
        // a video waits on its first frame with a Play button; it only plays by itself when autoplay is on. Every frame is a
        // new dialog, which puts the scroll position back at the top - so by default nothing moves until you press Play.
        if (video) {
            Playback pb = newPlayback(player, s, media, Screen.GRAM_FEED, "ui.button.back", exit, r,
                    frame -> r.feed(new Views.Feed(clock(), postView(post, frame), page.index(), page.total(), header, ""), 0),
                    paused -> feedGrid(r, s, me, profile, page, true, paused, player).finish());
            if (cfg.gramAutoplay) runPlayback(player, s, pb);
        }
    }

    /** Runs {@code action} on the post on screen, then shows the same screen again. */
    private void onPost(Player player, Session s, java.util.function.BiConsumer<Database.GramPost, java.util.function.Consumer<Gram.Result>> action) {
        Database.GramPost post = s.gramPost;
        if (post == null) {
            reopenGram(player, s);
            return;
        }
        action.accept(post, result -> {
            if (!player.isOnline()) return;
            if (result != Gram.Result.OK) s.notice = gramError(result);
            reopenGram(player, s);
        });
    }

    private void reopenGram(Player player, Session s) {
        if (s.gramBack != null) s.gramBack.run();
        else openFeed(player, s, null, 0);
    }

    private void deletePost(Player player, Session s) {
        Database.GramPost post = s.gramPost;
        if (post == null) return;
        if (s.confirmPostDelete != post.entryId()) {
            s.confirmPostDelete = post.entryId();
            s.notice = lang.plain("ui.gram.confirm-delete");
            reopenGram(player, s);
            return;
        }
        s.confirmPostDelete = -1;
        gram.delete(player, post.id(), result -> {
            if (!player.isOnline()) return;
            s.notice = result == Gram.Result.OK ? lang.plain("ui.gram.deleted") : gramError(result);
            openFeed(player, s, s.gramProfile, s.gramIndex);
        });
    }

    private void openComments(Player player, Session s, int skip) {
        Database.GramPost post = s.gramPost;
        if (post == null) {
            reopenGram(player, s);
            return;
        }
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.gramComments(post.id(), Math.max(0, skip), COMMENTS_PER_PAGE + 1), list -> {
            if (!player.isOnline()) return;
            s.commentsSkip = Math.max(0, skip);
            boolean older = list.size() > COMMENTS_PER_PAGE;
            List<Database.GramComment> page = new ArrayList<>(list.subList(0, Math.min(COMMENTS_PER_PAGE, list.size())));
            java.util.Collections.reverse(page);        // oldest of the page on top, like a chat
            List<Views.Comment> shown = new ArrayList<>();
            for (Database.GramComment c : page) shown.add(new Views.Comment(c.handle(), c.body(), age(c.at())));
            String notice = s.notice;
            s.notice = "";
            Grid grid = new Grid(r, 3);
            grid.add(null, lang.plain("ui.button.post-comment"), Views.Tone.ACCENT, "gramsay");
            grid.add("up", lang.plain("ui.button.earlier"), older ? Views.Tone.NORMAL : Views.Tone.DIM, older ? "gramcom/" + (s.commentsSkip + COMMENTS_PER_PAGE) : "nop");
            boolean newer = s.commentsSkip > 0;
            grid.add("down", lang.plain("ui.button.later"), newer ? Views.Tone.NORMAL : Views.Tone.DIM,
                    newer ? "gramcom/" + Math.max(0, s.commentsSkip - COMMENTS_PER_PAGE) : "nop");
            boolean canDelete = post.author().equals(player.getUniqueId()) || gram.moderator(player);
            for (Database.GramComment c : page) canDelete |= c.author().equals(player.getUniqueId());
            if (canDelete && !page.isEmpty()) grid.add("trash", lang.plain("ui.button.delete"), Views.Tone.DANGER, "gramdelc");
            List<DialogInput> inputs = List.of(field("comment", lang.get("ui.gram.comment-label"), false, r.buttonWidth(1), s.commentDraft,
                    cfg.gramCommentLength));
            Views.Comments view = new Views.Comments(clock(), post.handle(), post.caption(), shown, older, newer, notice);
            show(player, s, Screen.GRAM_COMMENTS, r.comments(view, inputs(1, 0)), inputs, grid, "ui.button.back", "gramback");
        });
    }

    private void sayComment(Player player, Session s, DialogResponseView view) {
        Database.GramPost post = s.gramPost;
        String typed = view == null ? "" : view.getText("comment");
        if (post == null || s.sending) return;
        s.sending = true;
        gram.comment(player, post, typed, result -> {
            s.sending = false;
            if (!player.isOnline()) return;
            if (result == Gram.Result.OK) {
                s.commentDraft = "";
                sounds.play(player, Sounds.Cue.SEND);
            } else {
                s.commentDraft = typed == null ? "" : typed;
                s.notice = gramError(result);
            }
            openComments(player, s, 0);
        });
    }

    /** The comments on this page that the player may delete, one button each. */
    private void openDeleteComment(Player player, Session s) {
        Database.GramPost post = s.gramPost;
        if (post == null) return;
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.gramComments(post.id(), s.commentsSkip, COMMENTS_PER_PAGE), list -> {
            if (!player.isOnline()) return;
            boolean all = post.author().equals(player.getUniqueId()) || gram.moderator(player);
            Grid grid = new Grid(r, 1);
            for (Database.GramComment c : list) {
                if (!all && !c.author().equals(player.getUniqueId())) continue;
                grid.add(first -> r.activityButton(c.author(), "trash", c.handle() + ": " + c.body(), age(c.at()), first),
                        "gramdelcid/" + c.id(), c.body());
            }
            Views.Comments view = new Views.Comments(clock(), post.handle(), post.caption(), List.of(), false, false,
                    lang.plain("ui.gram.pick-delete"));
            show(player, s, Screen.GRAM_DELETE, r.comments(view, 0), List.of(), grid, "ui.button.back", "gramcom/" + s.commentsSkip);
        });
    }

    private void openCompose(Player player, Session s, int index, DialogResponseView view) {
        if (view != null && s.screen == Screen.GRAM_COMPOSE) {
            String typed = view.getText("caption");
            s.captionDraft = typed == null ? "" : typed;
        }
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, gallery.list(player.getUniqueId()), list -> {
            if (!player.isOnline()) return;
            int at = list.isEmpty() ? 0 : Math.floorMod(index, list.size());
            CompletableFuture<MediaStore.Media> media = list.isEmpty() ? CompletableFuture.completedFuture(null) : gallery.media(list.get(at).id());
            Tasks.onMain(plugin, media, m -> {
                if (!player.isOnline()) return;
                s.composeIndex = at;
                String notice = s.notice;
                s.notice = "";
                Grid grid = new Grid(r, 3);
                List<DialogInput> inputs = new ArrayList<>();
                if (!list.isEmpty()) {
                    boolean many = list.size() > 1;
                    grid.add(null, lang.plain("ui.button.prev"), many ? Views.Tone.NORMAL : Views.Tone.DIM, many ? "gramnew/" + (at - 1) : "nop");
                    grid.add(null, lang.plain("ui.button.publish"), Views.Tone.ACCENT, "grampost/" + list.get(at).id());
                    grid.add(null, lang.plain("ui.button.next"), many ? Views.Tone.NORMAL : Views.Tone.DIM, many ? "gramnew/" + (at + 1) : "nop");
                    inputs.add(field("caption", lang.get("ui.gram.caption-label"), true, r.buttonWidth(1), s.captionDraft, cfg.gramCaptionLength));
                } else if (cfg.cameraEnabled && player.hasPermission("phone.camera")) {
                    grid.add("camera", lang.plain("ui.button.camera"), Views.Tone.NORMAL, "camera");
                }
                boolean video = !list.isEmpty() && list.get(at).video();
                Views.Compose cv = new Views.Compose(clock(), m == null ? null : m.poster(), video, at, list.size(), notice);
                show(player, s, Screen.GRAM_COMPOSE, r.compose(cv, inputs(0, inputs.size())), inputs, grid, "ui.button.back", "gramback");
            });
        });
    }

    private void publish(Player player, Session s, long galleryId, DialogResponseView view) {
        String caption = view == null ? "" : view.getText("caption");
        if (s.sending) return;
        s.sending = true;
        s.captionDraft = caption == null ? "" : caption;
        gram.post(player, galleryId, caption, result -> {
            s.sending = false;
            if (!player.isOnline()) return;
            s.notice = result == Gram.Result.RATE_LIMITED ? lang.plain("ui.gram.error.wait", "seconds", gram.postWait(player)) : gramError(result);
            if (result == Gram.Result.NO_ACCOUNT) openGram(player);
            else openCompose(player, s, s.composeIndex, null);
        }, id -> {
            s.sending = false;
            if (!player.isOnline()) return;
            s.captionDraft = "";
            sounds.play(player, Sounds.Cue.SEND);
            s.notice = lang.plain("ui.gram.posted");
            openFeed(player, s, null, 0);
        });
    }

    private void openSearch(Player player, Session s, DialogResponseView view) {
        if (view != null && s.screen == Screen.GRAM_SEARCH) s.gramQuery = TextWrapper.sanitize(view.getText("q"), 16).replace("@", "");
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.searchGram(s.gramQuery, 6), list -> {
            if (!player.isOnline()) return;
            Grid grid = new Grid(r, 1);
            for (Database.GramAccount a : list) {
                grid.add(first -> r.accountButton(a.id(), a.handle(), a.posts(), first), "gramp/" + a.id() + "/0", a.bio().isEmpty() ? null : a.bio());
            }
            grid.add("search", lang.plain("ui.button.search"), Views.Tone.ACCENT, "gramsearch");
            List<DialogInput> inputs = List.of(field("q", lang.get("ui.gram.search-label"), true, r.buttonWidth(1), s.gramQuery, 16));
            String notice = s.notice;
            s.notice = "";
            show(player, s, Screen.GRAM_SEARCH, r.search(new Views.Search(clock(), s.gramQuery, list.size(), notice), inputs(0, 1)), inputs,
                    grid, "ui.button.back", "gramback");
        });
    }

    private void openActivity(Player player, Session s) {
        ScreenRenderer r = renderer(player);
        Tasks.onMain(plugin, db.gramActivity(player.getUniqueId(), 6), list -> {
            if (!player.isOnline()) return;
            Grid grid = new Grid(r, 1);
            for (Database.GramActivity a : list) {
                String icon = switch (a.kind()) {
                    case "like" -> "heart_full";
                    case "comment" -> "comment";
                    default -> "repost";
                };
                String text = lang.plain("ui.gram.did-" + a.kind(), "player", a.handle(), "text", a.text());
                grid.add(first -> r.activityButton(a.who(), icon, text, age(a.at()), first), "grampost-open/" + a.postId(),
                        a.text().isEmpty() ? null : a.text());
            }
            show(player, s, Screen.GRAM_ACTIVITY, r.activity(new Views.Activity(clock(), list.size()), 0), List.of(), grid, "ui.button.back",
                    "gramback");
        });
    }

    /** Opens one post by id (from the activity list) as a page of its author's profile. */
    private void openPost(Player player, Session s, long postId) {
        Tasks.onMain(plugin, db.gramPost(player.getUniqueId(), postId), post -> {
            if (!player.isOnline()) return;
            if (post == null) {
                s.notice = lang.plain("ui.gram.error.not-found");
                openActivity(player, s);
                return;
            }
            ScreenRenderer r = renderer(player);
            Tasks.onMain(plugin, gram.account(post.author()).thenCombine(gram.media().read(post.id()), java.util.AbstractMap.SimpleImmutableEntry::new), both -> {
                if (!player.isOnline()) return;
                showFeed(player, s, r, post.author(), both.getKey(), new Database.GramPage(post, 0, 1), both.getValue());
                s.gramBack = () -> openPost(player, s, postId);
            });
        });
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

    /**
     * Runs a button path as if the player had clicked it, without input values (/phone click: command blocks,
     * scripts and testing without a mouse).
     */
    public void click(Player player, String path) {
        try {
            route(player, session(player), path.split("/"), null);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            close(player, true);
        }
    }

    private void route(Player player, Session s, String[] path, DialogResponseView view) {
        String action = path[0];
        if (action.equals("nop")) return;
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
        // the lock screen's own buttons; everything after them needs an unlocked phone
        switch (action) {
            case "unlock" -> {
                unlock(player, s, view);
                return;
            }
            case "setcode" -> {
                setCode(player, s, view, false);
                return;
            }
            default -> { }
        }
        if (cfg.passcodeEnabled && !s.unlocked) {
            // a click from a dialog that outlived its session (or a hand-made packet): back to the lock screen
            if (s.screen == Screen.NONE) s.screen = Screen.LOCK;
            unlocked(player, s);
            return;
        }
        if (!action.startsWith("photo") && !action.equals("camback")) s.fromCamera = s.fromCamera && action.equals("photos");
        switch (action) {
            case "home" -> openHome(player);
            case "settings" -> openSettings(player);
            case "passcode" -> showLock(player, s, Views.LockMode.CHANGE);
            case "changecode" -> setCode(player, s, view, true);
            case "repack" -> {
                if (!pack.send(player)) player.sendMessage(lang.get("command.no-pack"));
                openSettings(player);
            }
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
                openConversation(player, UUID.fromString(path[1]), Integer.parseInt(path[2]));
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
                openConversation(player, partner, s.skip);
            }
            case "mine" -> {
                keepDraft(s, view);
                openOwnMessages(player, s, UUID.fromString(path[1]));
            }
            case "editmsg" -> openEditMessage(player, s, UUID.fromString(path[1]), Long.parseLong(path[2]));
            case "save" -> saveEdit(player, s, UUID.fromString(path[1]), Long.parseLong(path[2]), view);
            case "del" -> deleteMessage(player, s, UUID.fromString(path[1]), Long.parseLong(path[2]));
            case "camera" -> {
                // the dialog goes, the session stays unlocked for as long as the camera is in use
                close(player, true);
                if (items.holdsPhone(player)) camera.enter(player);
                else player.sendMessage(lang.get("camera.hold-phone"));
            }
            case "camback" -> {
                close(player, true);
                s.fromCamera = false;
            }
            case "photos" -> openPhotos(player, Integer.parseInt(path[1]));
            case "photoplay" -> playPhoto(player, s, Integer.parseInt(path[1]));
            case "pbpause" -> pausePlayback(player, s);
            case "pbplay" -> resumePlayback(player, s);
            case "photoprint" -> printPhoto(player, s, Long.parseLong(path[1]));
            case "photodel" -> deletePhoto(player, s, Long.parseLong(path[1]));
            case "photoshare" -> openShare(player, s, Long.parseLong(path[1]));
            case "sendphoto" -> sendPhoto(player, s, Long.parseLong(path[1]), UUID.fromString(path[2]));
            case "sharegram" -> {
                s.gramBack = () -> openPhotos(player, s.photoIndex);
                gramCompose(player, s, Long.parseLong(path[1]));
            }
            case "gram" -> openGram(player);
            default -> routeGram(player, s, action, path, view);
        }
    }

    /** Opens the composer on one picture of the player's Photos. */
    private void gramCompose(Player player, Session s, long galleryId) {
        Tasks.onMain(plugin, gallery.list(player.getUniqueId()), list -> {
            int at = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id() == galleryId) at = i;
            }
            int index = at;
            Tasks.onMain(plugin, gram.account(player.getUniqueId()), acct -> {
                if (acct == null) openGram(player);
                else openCompose(player, s, index, null);
            });
        });
    }

    private void routeGram(Player player, Session s, String action, String[] path, DialogResponseView view) {
        if (!gram.mayUse(player)) {
            close(player, true);
            player.sendMessage(lang.get("error.no-permission"));
            return;
        }
        switch (action) {
            case "gramsave" -> saveAccount(player, s, Boolean.parseBoolean(path[1]), view);
            case "gramf" -> {
                s.gramBack = null;
                openFeed(player, s, null, Integer.parseInt(path[1]));
            }
            case "gramp" -> openFeed(player, s, UUID.fromString(path[1]), Integer.parseInt(path[2]));
            case "gramme" -> {
                UUID me = player.getUniqueId();
                if (s.screen == Screen.GRAM_FEED && me.equals(s.gramProfile)) {
                    // the profile tab on your own profile edits it
                    Tasks.onMain(plugin, gram.account(me), acct -> {
                        if (acct != null && player.isOnline()) openAccount(player, s, false, acct.handle(), acct.bio());
                    });
                } else {
                    Tasks.onMain(plugin, gram.account(me), acct -> {
                        if (!player.isOnline()) return;
                        if (acct == null) openAccount(player, s, true, Gram.suggestHandle(player.getName()), "");
                        else openFeed(player, s, me, 0);
                    });
                }
            }
            case "gramlike" -> onPost(player, s, (post, done) -> gram.like(player, post, done));
            case "gramrp" -> onPost(player, s, (post, done) -> gram.repost(player, post, done));
            case "gramdel" -> deletePost(player, s);
            case "gramcom" -> openComments(player, s, Integer.parseInt(path[1]));
            case "gramsay" -> sayComment(player, s, view);
            case "gramdelc" -> openDeleteComment(player, s);
            case "gramdelcid" -> gram.deleteComment(player, Long.parseLong(path[1]), result -> {
                if (!player.isOnline()) return;
                s.notice = result == Gram.Result.OK ? lang.plain("ui.gram.comment-deleted") : gramError(result);
                openComments(player, s, s.commentsSkip);
            });
            case "gramback" -> reopenGram(player, s);
            case "gramnew" -> {
                if (s.screen != Screen.GRAM_COMPOSE && s.screen != Screen.PHOTOS) {
                    Runnable back = s.gramBack;
                    s.gramBack = back;
                }
                openCompose(player, s, Integer.parseInt(path[1]), view);
            }
            case "grampost" -> publish(player, s, Long.parseLong(path[1]), view);
            case "gramsearch" -> openSearch(player, s, view);
            case "gramact" -> openActivity(player, s);
            case "grampost-open" -> openPost(player, s, Long.parseLong(path[1]));
            default -> close(player, true);
        }
    }

    // ---- incoming messages and activity -----------------------------------------------------------------------------

    /** Sends the screen a player is looking at again, once, a little later (several changes become one refresh). */
    private void refreshLater(Player player, Session s) {
        if (!cfg.autoRefresh || s.refresh != null) return;
        s.refresh = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.refresh = null;
            if (!player.isOnline()) return;
            switch (s.screen) {
                // while scrolled up the window keeps its place; at the end it follows the new message
                case CONVERSATION -> openConversation(player, s.partner, s.skip);
                case CONTACTS -> openContacts(player, s.contactsPage);
                case HOME -> openHome(player);
                default -> { }
            }
        }, cfg.refreshDelayTicks);
    }

    @Override
    public void incoming(Player recipient, Message message, String senderName) {
        sounds.play(recipient, Sounds.Cue.NOTIFY);
        Session s = sessions.get(recipient.getUniqueId());
        boolean watching = s != null && s.screen == Screen.CONVERSATION && message.sender().equals(s.partner);
        boolean onList = s != null && (s.screen == Screen.HOME || s.screen == Screen.CONTACTS);
        if (watching || onList) refreshLater(recipient, s);
        if (!watching) hint(recipient, lang.get("message.hint", Lang.ph("player", senderName)));
    }

    @Override
    public void activity(Player author, String handle, String kind) {
        if (!cfg.gramNotify) return;
        sounds.play(author, Sounds.Cue.NOTIFY);
        hint(author, lang.get("message.gram-" + kind, Lang.ph("player", handle)));
    }

    @Override
    public void conversationChanged(Player player, UUID partner) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        if ((s.screen == Screen.CONVERSATION && partner.equals(s.partner)) || s.screen == Screen.CONTACTS) refreshLater(player, s);
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
     * a player who presses a movement key has no screen open. The phone counts as closed, and so as locked.
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
        if (s != null) stopPlayback(s);
        postFx.forget(e.getPlayer());
        gram.quit(e.getPlayer());
    }

    /** Closes every open phone screen (plugin disable, reload). */
    public void closeAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) close(p, true);
        }
    }
}
