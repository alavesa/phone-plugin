package fi.alavesa.phone.ui;

import fi.alavesa.phone.config.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The phone screens as ordinary text, for players who declined the resource pack or have not loaded it yet. No
 * custom glyphs at all (they would show as missing-character boxes): names, times and colours carry the layout, and
 * the client wraps long lines itself.
 */
public final class PlainRenderer implements ScreenRenderer {

    private static final int WIDTH = 280;

    private final FontWidths fw;
    private final Lang lang;

    public PlainRenderer(FontWidths widths, Lang lang) {
        this.fw = widths;
        this.lang = lang;
    }

    @Override
    public int bodyWidth() {
        return WIDTH;
    }

    @Override
    public int rowButtonWidth() {
        return WIDTH;
    }

    @Override
    public int appButtonWidth() {
        return 92;
    }

    private static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static Component red(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    @Override
    public Component lock(Views.Lock v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(v.clock(), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(gray(v.date()));
        out.add(Component.text(switch (v.mode()) {
            case LOCKED -> lang.plain("ui.lock.enter", "player", v.playerName());
            case SETUP -> lang.plain("ui.lock.setup");
            case CHANGE -> lang.plain("ui.lock.change");
        }, NamedTextColor.WHITE));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component home(Views.Home v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(gray(v.clock()));
        out.add(Component.text(lang.plain("ui.home.greeting", "player", v.playerName()), NamedTextColor.WHITE));
        out.add(v.unread() > 0 ? red(lang.plain("ui.home.unread", "count", v.unread())) : gray(lang.plain("ui.home.no-unread")));
        return lines(out);
    }

    @Override
    public Component appButton(String app, String label, int badge) {
        Component out = Component.text(label);
        return badge > 0 ? out.append(Component.text(" (" + (badge > 99 ? "99+" : badge) + ")", NamedTextColor.RED)) : out;
    }

    @Override
    public Component contacts(Views.Contacts v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.contacts.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        if (!v.filter().isEmpty()) out.add(gray(lang.plain("ui.contacts.filter", "filter", v.filter())));
        else if (v.totalUnread() > 0) out.add(red(lang.plain("ui.home.unread", "count", v.totalUnread())));
        else out.add(gray(lang.plain("ui.contacts.hint")));
        if (v.page().isEmpty()) out.add(gray(lang.plain("ui.contacts.empty")));
        else if (v.pageCount() > 1) out.add(gray(lang.plain("ui.page", "page", v.pageIndex() + 1, "pages", v.pageCount())));
        return lines(out);
    }

    @Override
    public Component contactButton(Views.Contact c) {
        Component out = Component.text(c.unread() > 0 ? "(" + c.unread() + ") " : "", NamedTextColor.RED)
                .append(Component.text(c.name(), c.online() ? NamedTextColor.WHITE : NamedTextColor.GRAY));
        if (!c.preview().isEmpty()) {
            String preview = (c.previewFromMe() ? lang.plain("ui.contacts.you-prefix") : "") + c.preview();
            int room = WIDTH - 24 - fw.width(c.name(), false) - (c.unread() > 0 ? 22 : 0);
            if (room > 30) out = out.append(gray("  " + fw.ellipsize(preview, room, false)));
        }
        return out;
    }

    private Component message(String partnerName, Views.Bubble b) {
        String prefix = "[" + b.time() + "] " + (b.outgoing() ? lang.plain("ui.conversation.you") : partnerName) + ": ";
        Component line = Component.text(prefix, b.outgoing() ? NamedTextColor.AQUA : NamedTextColor.YELLOW)
                .append(Component.text(b.body(), NamedTextColor.WHITE));
        if (b.edited()) line = line.append(Component.text(" " + lang.plain("ui.conversation.edited"), NamedTextColor.DARK_GRAY));
        if (b.outgoing() && b.read()) line = line.append(Component.text(" " + lang.plain("ui.conversation.read-mark"), NamedTextColor.DARK_GRAY));
        return line;
    }

    @Override
    public int messageLines(String body) {
        // prefix estimate: "[00:00] " + a 16 character name + ": "
        return Math.max(1, (int) Math.ceil((fw.width(body, false) + 150) / (double) (WIDTH - 8)));
    }

    @Override
    public Component conversation(Views.Conversation v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(v.partnerName(), NamedTextColor.WHITE, TextDecoration.BOLD)
                .append(Component.text(" - " + lang.plain(v.online() ? "ui.conversation.online" : "ui.conversation.offline"),
                        v.online() ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        else if (v.blocked()) out.add(red(lang.plain("ui.conversation.blocked")));
        else if (v.hasOlder()) out.add(gray(lang.plain("ui.conversation.older-above")));
        if (v.messages().isEmpty()) out.add(gray(lang.plain("ui.conversation.empty")));
        for (Views.Bubble b : v.messages()) out.add(message(v.partnerName(), b));
        if (v.hasNewer()) out.add(gray(lang.plain("ui.conversation.newer-below")));
        return lines(out);
    }

    @Override
    public Component ownMessages(Views.OwnMessages v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.edit.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(gray(lang.plain("ui.edit.to", "player", v.partnerName())));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        else out.add(gray(lang.plain(v.count() == 0 ? "ui.edit.none" : "ui.edit.pick")));
        return lines(out);
    }

    @Override
    public Component ownMessageButton(Views.Bubble m) {
        return gray("[" + m.time() + "] ").append(Component.text(fw.ellipsize(m.body(), WIDTH - 90, false), NamedTextColor.WHITE));
    }

    @Override
    public Component editMessage(Views.EditMessage v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.edit.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(v.notice().isEmpty() ? gray(lang.plain("ui.edit.hint")) : red(v.notice()));
        out.add(message(v.partnerName(), v.message()));
        return lines(out);
    }

    @Override
    public Component photoResult(Views.PhotoResult v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(v.seconds() > 0 ? lang.plain("ui.camera.recorded", "seconds", String.format(Locale.ROOT, "%.1f", v.seconds()))
                : lang.plain("ui.camera.captured"), NamedTextColor.WHITE));
        out.add(gray(lang.plain("ui.camera.zoom", "zoom", v.zoom())));
        if (!v.location().isEmpty()) out.add(gray(v.location()));
        return lines(out);
    }

    @Override
    public Component settings(Views.Settings v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.settings.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(Component.text(v.playerName(), NamedTextColor.WHITE));
        out.add(gray(v.packLine()));
        return lines(out);
    }
}
