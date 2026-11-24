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
    public int buttonWidth(int columns) {
        return switch (columns) {
            case 1 -> 200;
            case 2 -> 100;
            case 3 -> 70;
            default -> 46;
        };
    }

    @Override
    public boolean framed() {
        return false;
    }

    @Override
    public Component button(Views.Btn b, int columns, boolean first) {
        String icon = b.icon() == null ? "" : switch (b.icon()) {
            case "left" -> "<";
            case "right" -> ">";
            case "heart" -> "\u2661";
            case "heart_full" -> "\u2665";
            case "comment" -> "\u2709";
            case "repost" -> "\u21c4";
            case "home" -> "\u2302";
            case "search" -> "?";
            case "plus" -> "+";
            case "profile" -> "@";
            case "play" -> "\u25b6";
            case "trash" -> "x";
            default -> "";
        };
        String text = (icon + " " + b.label()).strip();
        NamedTextColor colour = switch (b.tone()) {
            case NORMAL -> NamedTextColor.WHITE;
            case DIM -> NamedTextColor.GRAY;
            case ACCENT -> NamedTextColor.LIGHT_PURPLE;
            case ACTIVE -> NamedTextColor.RED;
            case PRINT -> NamedTextColor.AQUA;
            case DANGER -> NamedTextColor.RED;
        };
        return Component.text(text, colour);
    }

    @Override
    public Component homeBar() {
        return Component.text(lang.plain("ui.button.home"));
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
    public Component lock(Views.Lock v, int inputsPx) {
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
    public Component home(Views.Home v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(gray(v.clock()));
        out.add(Component.text(lang.plain("ui.home.greeting", "player", v.playerName()), NamedTextColor.WHITE));
        out.add(v.unread() > 0 ? red(lang.plain("ui.home.unread", "count", v.unread())) : gray(lang.plain("ui.home.no-unread")));
        return lines(out);
    }

    @Override
    public Component appButton(String app, String label, int badge, boolean first) {
        Component out = Component.text(label);
        return badge > 0 ? out.append(Component.text(" (" + (badge > 99 ? "99+" : badge) + ")", NamedTextColor.RED)) : out;
    }

    @Override
    public Component contacts(Views.Contacts v, int inputsPx) {
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
    public Component contactButton(Views.Contact c, boolean first) {
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
    public Component conversation(Views.Conversation v, int inputsPx) {
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
    public Component ownMessages(Views.OwnMessages v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.edit.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(gray(lang.plain("ui.edit.to", "player", v.partnerName())));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        else out.add(gray(lang.plain(v.count() == 0 ? "ui.edit.none" : "ui.edit.pick")));
        return lines(out);
    }

    @Override
    public Component ownMessageButton(Views.Bubble m, boolean first) {
        return gray("[" + m.time() + "] ").append(Component.text(fw.ellipsize(m.body(), WIDTH - 90, false), NamedTextColor.WHITE));
    }

    @Override
    public Component editMessage(Views.EditMessage v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.edit.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(v.notice().isEmpty() ? gray(lang.plain("ui.edit.hint")) : red(v.notice()));
        out.add(message(v.partnerName(), v.message()));
        return lines(out);
    }

    @Override
    public Component settings(Views.Settings v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(Component.text(lang.plain("ui.settings.title"), NamedTextColor.WHITE, TextDecoration.BOLD));
        out.add(Component.text(v.playerName(), NamedTextColor.WHITE));
        out.add(gray(v.packLine()));
        return lines(out);
    }

    // ---- photos and Minestagram: no pictures without the pack, only what they are -----------------------------------

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.WHITE, TextDecoration.BOLD);
    }

    @Override
    public Component photos(Views.Photos v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.photos.title") + (v.count() > 0 ? "  " + (v.index() + 1) + "/" + v.count() : "")));
        if (v.photo() == null) {
            out.add(Component.text(lang.plain("ui.photos.empty"), NamedTextColor.WHITE));
            out.add(gray(lang.plain("ui.photos.empty-hint")));
        } else {
            out.add(gray(lang.plain("ui.photos.no-pack")));
            out.add(Component.text(v.info(), NamedTextColor.WHITE));
        }
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component share(Views.Share v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.share.title")));
        out.add(gray(lang.plain("ui.share.hint")));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component feed(Views.Feed v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        Views.Profile p = v.profile();
        String pager = v.total() > 0 ? "  " + (v.index() + 1) + "/" + v.total() : "";
        if (p == null) {
            out.add(title(lang.plain("ui.gram.title") + pager));
        } else {
            out.add(title("@" + p.handle() + pager));
            out.add(gray(lang.plain("ui.gram.post-count", "count", p.posts()) + (p.bio().isEmpty() ? "" : " - " + p.bio())));
        }
        Views.Post post = v.post();
        if (post == null) {
            out.add(Component.text(lang.plain(p == null ? "ui.gram.empty" : "ui.gram.empty-profile"), NamedTextColor.WHITE));
        } else {
            Component head = Component.text("@" + post.handle(), NamedTextColor.WHITE, TextDecoration.BOLD)
                    .append(gray("  " + post.age()).decoration(TextDecoration.BOLD, false));
            if (post.reposter() != null) head = head.append(gray("  \u21c4 " + post.reposter()).decoration(TextDecoration.BOLD, false));
            out.add(head);
            out.add(gray(lang.plain(post.video() ? "ui.gram.a-video" : "ui.gram.a-photo")));
            if (!post.caption().isEmpty()) out.add(Component.text(post.caption(), NamedTextColor.WHITE));
            out.add(gray("\u2665 " + post.likes() + "   \u2709 " + post.comments() + "   \u21c4 " + post.reposts()));
        }
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component comments(Views.Comments v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.gram.comments")));
        out.add(gray("@" + v.postHandle() + (v.caption().isEmpty() ? "" : ": " + v.caption())));
        if (v.hasOlder()) out.add(gray(lang.plain("ui.gram.older-comments")));
        if (v.comments().isEmpty()) out.add(gray(lang.plain("ui.gram.first-comment")));
        for (Views.Comment c : v.comments()) {
            out.add(Component.text(c.handle() + " ", NamedTextColor.YELLOW).append(Component.text(c.body(), NamedTextColor.WHITE))
                    .append(Component.text("  " + c.age(), NamedTextColor.DARK_GRAY)));
        }
        if (v.hasNewer()) out.add(gray(lang.plain("ui.gram.newer-comments")));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component account(Views.Account v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.gram.title")));
        out.add(Component.text(lang.plain(v.create() ? "ui.gram.create" : "ui.gram.edit"), NamedTextColor.WHITE));
        out.add(gray(lang.plain("ui.gram.handle-rules")));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component compose(Views.Compose v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.gram.new-post") + (v.count() > 0 ? "  " + (v.index() + 1) + "/" + v.count() : "")));
        out.add(gray(lang.plain(v.photo() == null ? "ui.photos.empty" : "ui.photos.no-pack")));
        out.add(v.notice().isEmpty() ? gray(lang.plain("ui.gram.pick-hint")) : red(v.notice()));
        return lines(out);
    }

    @Override
    public Component search(Views.Search v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.gram.search")));
        out.add(gray(v.results() == 0 ? lang.plain("ui.gram.nobody")
                : v.query().isEmpty() ? lang.plain("ui.gram.newest-accounts") : lang.plain("ui.gram.results", "query", v.query())));
        if (!v.notice().isEmpty()) out.add(red(v.notice()));
        return lines(out);
    }

    @Override
    public Component accountButton(java.util.UUID id, String handle, int posts, boolean first) {
        return Component.text("@" + handle, NamedTextColor.WHITE).append(gray("  " + lang.plain("ui.gram.post-count", "count", posts)));
    }

    @Override
    public Component activity(Views.Activity v, int inputsPx) {
        List<Component> out = new ArrayList<>();
        out.add(title(lang.plain("ui.gram.activity")));
        out.add(gray(lang.plain(v.count() == 0 ? "ui.gram.no-activity" : "ui.gram.activity-hint")));
        return lines(out);
    }

    @Override
    public Component activityButton(java.util.UUID who, String icon, String text, String age, boolean first) {
        return Component.text(fw.ellipsize(text, 150, false), NamedTextColor.WHITE).append(gray("  " + age));
    }
}
