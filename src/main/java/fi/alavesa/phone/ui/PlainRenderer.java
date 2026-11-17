package fi.alavesa.phone.ui;

import fi.alavesa.phone.config.Lang;
import java.util.ArrayList;
import java.util.List;
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

    private static Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    @Override
    public Component home(Views.Home v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(v.clock(), NamedTextColor.GRAY));
        out.add(Component.text(lang.plain("ui.home.greeting", "player", v.playerName()), NamedTextColor.WHITE));
        out.add(v.unread() > 0 ? Component.text(lang.plain("ui.home.unread", "count", v.unread()), NamedTextColor.RED)
                : Component.text(lang.plain("ui.home.no-unread"), NamedTextColor.GRAY));
        return lines(out);
    }

    @Override
    public Component appButton(String app, Component label, int badge) {
        return badge > 0 ? label.append(Component.text(" (" + (badge > 99 ? "99+" : badge) + ")", NamedTextColor.RED)) : label;
    }

    @Override
    public Component contacts(Views.Contacts v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.contacts.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        if (!v.filter().isEmpty()) out.add(Component.text(lang.plain("ui.contacts.filter", "filter", v.filter()), NamedTextColor.GRAY));
        else if (v.totalUnread() > 0) out.add(Component.text(lang.plain("ui.home.unread", "count", v.totalUnread()), NamedTextColor.RED));
        else out.add(Component.text(lang.plain("ui.contacts.hint"), NamedTextColor.GRAY));
        if (v.page().isEmpty()) out.add(Component.text(lang.plain("ui.contacts.empty"), NamedTextColor.GRAY));
        else if (v.pageCount() > 1) out.add(Component.text(lang.plain("ui.page", "page", v.pageIndex() + 1, "pages", v.pageCount()), NamedTextColor.GRAY));
        return lines(out);
    }

    @Override
    public int contactButtonWidth() {
        return WIDTH;
    }

    @Override
    public Component contactButton(Views.Contact c) {
        Component out = Component.text(c.unread() > 0 ? "(" + c.unread() + ") " : "", NamedTextColor.RED)
                .append(Component.text(c.name(), c.online() ? NamedTextColor.WHITE : NamedTextColor.GRAY));
        if (!c.preview().isEmpty()) {
            String preview = (c.previewFromMe() ? lang.plain("ui.contacts.you-prefix") : "") + c.preview();
            int room = WIDTH - 24 - fw.width(c.name(), false) - (c.unread() > 0 ? 22 : 0);
            if (room > 30) out = out.append(Component.text("  " + fw.ellipsize(preview, room, false), NamedTextColor.GRAY));
        }
        return out;
    }

    private String prefix(Views.Conversation v, Views.Bubble b) {
        return "[" + b.time() + "] " + (b.outgoing() ? lang.plain("ui.conversation.you") : v.partnerName()) + ": ";
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
        if (!v.notice().isEmpty()) out.add(Component.text(v.notice(), NamedTextColor.RED));
        else if (v.blocked()) out.add(Component.text(lang.plain("ui.conversation.blocked"), NamedTextColor.RED));
        else if (v.hasOlder()) out.add(Component.text(lang.plain("ui.conversation.older-above"), NamedTextColor.GRAY));
        if (v.messages().isEmpty()) out.add(Component.text(lang.plain("ui.conversation.empty"), NamedTextColor.GRAY));
        for (Views.Bubble b : v.messages()) {
            Component line = Component.text(prefix(v, b), b.outgoing() ? NamedTextColor.AQUA : NamedTextColor.YELLOW)
                    .append(Component.text(b.body(), NamedTextColor.WHITE));
            if (b.outgoing() && b.read()) line = line.append(Component.text(" " + lang.plain("ui.conversation.read-mark"), NamedTextColor.DARK_GRAY));
            out.add(line);
        }
        if (v.hasNewer()) out.add(Component.text(lang.plain("ui.conversation.newer-below"), NamedTextColor.GRAY));
        return lines(out);
    }

    @Override
    public Component photoResult(Views.PhotoResult v, int belowPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.camera.captured"), NamedTextColor.WHITE));
        out.add(Component.text(lang.plain("ui.camera.zoom", "zoom", v.zoom()), NamedTextColor.GRAY));
        if (!v.location().isEmpty()) out.add(Component.text(v.location(), NamedTextColor.GRAY));
        return lines(out);
    }
}
