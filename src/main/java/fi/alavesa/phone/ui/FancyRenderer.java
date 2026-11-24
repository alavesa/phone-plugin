package fi.alavesa.phone.ui;

import fi.alavesa.phone.config.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;

/**
 * The phone screens drawn with the resource pack: a portrait phone (black screen in a rounded bezel with the
 * dynamic island and the status bar), speech bubbles made of stretchable font pieces, icons, and photos drawn pixel
 * by pixel ({@link PhotoText}). Every screen is ONE multi-line text; each line is padded to the same width (see
 * {@link Line}), and the panel glyph on the first line hangs down behind everything else.
 *
 * <p><b>Buttons are part of the phone.</b> All grids are exactly {@code contentWidth} wide (1, 2, 3 or 5 columns),
 * so they line up with the screen. The label of the first button of every row starts with a "band" glyph: the black
 * screen and the bezel for that row and the 2 px under it, with a 1 px hole where the button's own border is (it
 * still lights up on hover). Later buttons of the row cover their grey face with a "cover" glyph. The last row is a
 * single button whose label is the bottom of the phone with the home indicator: the client cuts everything off at the
 * bottom of the last button, so that is the only place the rounded bottom edge can be.
 */
public final class FancyRenderer implements ScreenRenderer {

    private static final TextColor WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor META = TextColor.color(0x8E8E93);
    private static final TextColor DIM = TextColor.color(0xC7C7CC);
    private static final TextColor ACCENT = TextColor.color(0xF050C8);
    private static final TextColor LIKED = TextColor.color(0xFF3B6B);
    private static final TextColor PRINT = TextColor.color(0x50E6F0);
    private static final TextColor DANGER = TextColor.color(0xFF4D4D);
    private static final TextColor OUT_BUBBLE = TextColor.color(0xB83DBA);
    private static final TextColor OUT_TEXT = TextColor.color(0xFFFFFF);
    private static final TextColor IN_BUBBLE = TextColor.color(0x2C2C30);
    private static final TextColor IN_TEXT = TextColor.color(0xF2F2F7);
    private static final TextColor ONLINE = TextColor.color(0x34C759);
    private static final TextColor ALERT = TextColor.color(0xFF453A);
    private static final TextColor READ = TextColor.color(0x5AC8FA);
    private static final TextColor RING = TextColor.color(0xE0408A);

    /** Dialog text is 9 px per line and the text widget adds 4 px of padding around it. */
    private static final int LINE_HEIGHT = 9;
    private static final int TEXT_PADDING = 4;
    /** Gap the dialog leaves between its last body element / input and the button grid. */
    private static final int GRID_GAP = 10;
    /** A screen taller than one panel glyph continues the panel on this line. */
    private static final int CONTINUE_LINE = 24;
    /** Space between a bubble's rounded end and its text. */
    private static final int BUBBLE_PAD = 2;
    /** Bubbles never span the whole screen: the other side keeps this much free, like in a real messenger. */
    private static final int BUBBLE_FREE = 30;
    /** Text lines a photo takes (128 px hang from the first one). */
    private static final int PHOTO_LINES = 15;

    private final Glyphs g;
    private final FontWidths fw;
    private final Lang lang;
    private final boolean heads;
    private final PhotoText photoText;
    private final int bubbleTextWidth;
    private final int photoX;

    public FancyRenderer(Glyphs glyphs, FontWidths widths, Lang lang, boolean playerHeads) {
        this.g = glyphs;
        this.fw = widths;
        this.lang = lang;
        this.heads = playerHeads;
        this.photoText = new PhotoText(glyphs);
        this.bubbleTextWidth = g.contentWidth - 2 * g.tailWidth - 2 * g.capWidth - 2 * BUBBLE_PAD - BUBBLE_FREE;
        this.photoX = (g.contentWidth - g.photoSize) / 2;
    }

    @Override
    public int bodyWidth() {
        return g.bodyWidth;
    }

    @Override
    public int buttonWidth(int columns) {
        return g.buttonWidth(columns);
    }

    @Override
    public boolean framed() {
        return true;
    }

    private Line line() {
        return new Line(g, fw);
    }

    private Line notice(String text) {
        return line().centred(fw.ellipsize(text, g.contentWidth - 4, false), ALERT, false);
    }

    private static TextColor tone(Views.Tone tone) {
        return switch (tone) {
            case NORMAL -> WHITE;
            case DIM -> META;
            case ACCENT -> ACCENT;
            case ACTIVE -> LIKED;
            case PRINT -> PRINT;
            case DANGER -> DANGER;
        };
    }

    /**
     * Assembles a screen: the panel and the status bar on the first line, then the rest. The panel is picked tall
     * enough to reach the first row of buttons under {@code inputsPx} of inputs; a screen too tall for one panel
     * glyph continues it a few lines further down.
     */
    private Component screen(String clock, List<Line> rest, int inputsPx) {
        int lines = rest.size() + 1;
        int needed = g.panelTop + lines * LINE_HEIGHT + TEXT_PADDING + inputsPx + GRID_GAP;
        int top = g.panelHeightFor(needed);
        // the panel has to be the first glyph of the line: it is drawn first, everything else on top
        Line status = line().glyph(-g.panelMargin, "panel." + top, null).append(line().text(3, clock, WHITE, true));
        List<Component> out = new ArrayList<>(lines);
        out.add(status.build());
        int k = Math.min(CONTINUE_LINE, lines - 1);
        boolean more = needed > top && k > 0;
        for (int i = 0; i < rest.size(); i++) {
            Line l = rest.get(i);
            if (more && i + 1 == k) {
                int startsAt = g.panelTop + k * LINE_HEIGHT;
                l = line().glyph(-g.panelMargin, "panelx." + g.panelHeightFor(needed - startsAt), null).append(l);
            }
            out.add(l.build());
        }
        return Component.join(JoinConfiguration.newlines(), out);
    }

    private Line title(String text) {
        return line().text(2, fw.ellipsize(text, g.contentWidth - 50, true), WHITE, true);
    }

    // ---- buttons ----------------------------------------------------------------------------------------------------

    /** A button label with the row band (first button) or the face cover (the others) already placed. */
    private Line label(int columns, boolean first) {
        int w = g.buttonWidth(columns);
        // the label starts 2 px inside the button; the band starts at the phone's left edge
        Line l = new Line(g, fw, w - 4);
        if (first) l.glyph(-2 - g.panelMargin, "band." + columns, null);
        else l.glyph(-1, "cover." + w, null);
        return l;
    }

    @Override
    public Component button(Views.Btn b, int columns, boolean first) {
        Line l = label(columns, first);
        int inner = g.buttonWidth(columns) - 4;
        TextColor colour = tone(b.tone());
        boolean icon = b.icon() != null;
        String text = fw.ellipsize(b.label(), inner - (icon ? 10 : 0), false);
        int width = (icon ? 8 : 0) + (icon && !text.isEmpty() ? 2 : 0) + fw.width(text, false);
        int x = (inner - width) / 2;
        if (icon) {
            l.glyph(x, "mark." + b.icon(), colour);
            x += 10;
        }
        l.text(x, text, colour, false);
        return l.build();
    }

    @Override
    public Component homeBar() {
        Line l = new Line(g, fw, g.buttonWidth(1) - 4);
        l.glyph(-2 - g.panelMargin, "band.home", null);
        return l.build();
    }

    // ---- lock screen ------------------------------------------------------------------------------------------------

    @Override
    public Component lock(Views.Lock v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        // the clock: vanilla digits at three times their size, centred as one unit
        int width = 0;
        List<String> digits = new ArrayList<>();
        for (char c : v.clock().toCharArray()) {
            String name = c == ':' ? "big.colon" : Character.isDigit(c) ? "big." + c : null;
            if (name == null) continue;
            digits.add(name);
            width += g.advance(name);
        }
        Line clock = line();
        int x = (g.contentWidth - width) / 2;
        for (String name : digits) {
            clock.glyph(x, name, WHITE);
            x += g.advance(name);
        }
        rest.add(line());
        rest.add(clock);
        rest.add(line());
        rest.add(line().centred(v.date(), DIM, false));
        rest.add(line());
        String prompt = switch (v.mode()) {
            case LOCKED -> lang.plain("ui.lock.enter", "player", v.playerName());
            case SETUP -> lang.plain("ui.lock.setup");
            case CHANGE -> lang.plain("ui.lock.change");
        };
        rest.add(line().glyph((g.contentWidth - 8) / 2, "mark.lock", DIM));
        rest.add(line().centred(fw.ellipsize(prompt, g.contentWidth - 4, false), WHITE, false));
        rest.add(v.notice().isEmpty() ? line() : notice(v.notice()));
        return screen("", rest, inputsPx);
    }

    // ---- home -------------------------------------------------------------------------------------------------------

    @Override
    public Component home(Views.Home v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(v.date(), DIM, false));
        rest.add(line().centred(fw.ellipsize(lang.plain("ui.home.greeting", "player", v.playerName()), g.contentWidth - 4, true), WHITE, true));
        rest.add(line().centred(v.unread() > 0 ? lang.plain("ui.home.unread", "count", v.unread()) : lang.plain("ui.home.no-unread"),
                v.unread() > 0 ? ACCENT : META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component appButton(String app, String label, int badge, boolean first) {
        Line l = label(1, first);
        int inner = g.buttonWidth(1) - 4;
        l.glyph(1, "app." + app, null);
        l.text(22, fw.ellipsize(label, inner - 50, false), WHITE, false);
        if (badge > 0) l.rightAligned(inner - 3, badge > 99 ? "99+" : String.valueOf(badge), ACCENT);
        else l.glyph(inner - 9, "mark.right", META);
        return l.build();
    }

    // ---- contacts ---------------------------------------------------------------------------------------------------

    @Override
    public Component contacts(Views.Contacts v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        String info;
        if (!v.filter().isEmpty()) info = lang.plain("ui.contacts.filter", "filter", v.filter());
        else if (v.totalUnread() > 0) info = lang.plain("ui.home.unread", "count", v.totalUnread());
        else info = lang.plain("ui.contacts.hint");
        Line head = title(lang.plain("ui.contacts.title"));
        if (v.pageCount() > 1) head.rightAligned(g.contentWidth - 2, (v.pageIndex() + 1) + "/" + v.pageCount(), META);
        rest.add(head);
        rest.add(line().text(2, fw.ellipsize(info, g.contentWidth - 4, false), v.totalUnread() > 0 && v.filter().isEmpty() ? ACCENT : META, false));
        if (v.page().isEmpty()) rest.add(line().centred(lang.plain("ui.contacts.empty"), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component contactButton(Views.Contact c, boolean first) {
        Line l = label(1, first);
        int inner = g.buttonWidth(1) - 4;
        int x = 2;
        l.glyph(x, "mark.dot", c.unread() > 0 ? ACCENT : (c.online() ? ONLINE : META));
        x += 8;
        if (heads) {
            l.head(x, c.id());
            x += Line.HEAD_WIDTH + 3;
        }
        String name = fw.ellipsize(c.name(), 60, true);
        l.text(x, name, WHITE, true);
        x += fw.width(name, true) + 4;
        int right = inner - 2;
        if (!c.time().isEmpty()) right -= fw.width(c.time(), false) + 3;
        if (!c.preview().isEmpty() && right - x > 16) {
            String preview = (c.previewFromMe() ? lang.plain("ui.contacts.you-prefix") : "") + c.preview();
            l.text(x, fw.ellipsize(preview, right - x, false), c.unread() > 0 ? DIM : META, false);
        }
        if (!c.time().isEmpty()) l.rightAligned(inner - 2, c.time(), META);
        return l.build();
    }

    // ---- conversation -----------------------------------------------------------------------------------------------

    @Override
    public int messageLines(String body) {
        // the bubble rows plus the line with name / time above it
        return TextWrapper.wrap(fw, body, bubbleTextWidth).size() + 1;
    }

    @Override
    public Component conversation(Views.Conversation v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        String name = fw.ellipsize(v.partnerName(), g.contentWidth - 30, true);
        rest.add(line().glyph(2, "mark.dot", v.online() ? ONLINE : META).text(12, name, WHITE, true));
        boolean alert = !v.notice().isEmpty() || v.blocked();
        String info = !v.notice().isEmpty() ? v.notice() : v.blocked() ? lang.plain("ui.conversation.blocked")
                : v.hasOlder() ? lang.plain("ui.conversation.older-above") : "";
        Line top = line();
        if (v.hasOlder() && !alert) top.glyph(2, "mark.up", META);
        rest.add(top.centred(fw.ellipsize(info, g.contentWidth - 24, false), alert ? ALERT : META, false));
        if (v.messages().isEmpty()) {
            rest.add(line());
            rest.add(line().centred(lang.plain("ui.conversation.empty"), META, false));
            rest.add(line());
        }
        for (Views.Bubble b : v.messages()) {
            rest.add(meta(v.partnerId(), v.partnerName(), b));
            bubble(rest, TextWrapper.wrap(fw, b.body(), bubbleTextWidth), b.outgoing());
        }
        if (v.hasNewer()) rest.add(line().glyph(2, "mark.down", META).centred(lang.plain("ui.conversation.newer-below"), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    /** The small line above a bubble: head, name and time for incoming, time and read mark for outgoing. */
    private Line meta(UUID partnerId, String partnerName, Views.Bubble b) {
        Line l = line();
        String time = b.edited() ? lang.plain("ui.conversation.edited") + " " + b.time() : b.time();
        if (b.outgoing()) {
            int right = g.contentWidth - g.tailWidth;
            int markW = g.advance("mark.ticks");
            l.text(right - markW - 2 - fw.width(time, false), time, META, false);
            l.glyph(right - markW, b.read() ? "mark.ticks" : "mark.tick", b.read() ? READ : META);
        } else {
            int x = g.tailWidth;
            if (heads && partnerId != null) {
                l.head(x, partnerId);
                x += Line.HEAD_WIDTH + 3;
            }
            String name = fw.ellipsize(partnerName, 70, true);
            l.text(x, name, DIM, true);
            x += fw.width(name, true) + 4;
            l.text(x, fw.ellipsize(time, g.contentWidth - x, false), META, false);
        }
        return l;
    }

    /**
     * A speech bubble: per text row a left cap, tiles that add up to the needed width and a right cap, then the
     * text on top. The pieces are white in the texture and take the bubble colour from the text colour. The last
     * row carries the tail, on the left for incoming and on the right for outgoing messages.
     */
    private void bubble(List<Line> out, List<String> rows, boolean outgoing) {
        int textW = 0;
        for (String row : rows) textW = Math.max(textW, fw.width(row, false));
        // a tiny bubble looks odd; give short texts a minimum body
        int mid = Math.max(textW, 6) + 2 * BUBBLE_PAD;
        int total = mid + 2 * g.capWidth;
        int x0 = outgoing ? g.contentWidth - g.tailWidth - total : g.tailWidth;
        TextColor bubble = outgoing ? OUT_BUBBLE : IN_BUBBLE;
        TextColor ink = outgoing ? OUT_TEXT : IN_TEXT;
        for (int i = 0; i < rows.size(); i++) {
            boolean last = i == rows.size() - 1;
            String kind = "bubble." + (rows.size() == 1 ? "one" : i == 0 ? "top" : last ? "bot" : "mid");
            Line l = line();
            if (last && !outgoing) l.glyph(x0 - g.tailWidth, kind + ".capl_tail", bubble);
            else l.glyph(x0, kind + ".capl", bubble);
            int x = x0 + g.capWidth;
            int left = mid;
            for (int w : g.tileWidths) {
                while (left >= w) {
                    l.glyph(x, kind + ".tile" + w, bubble);
                    x += w;
                    left -= w;
                }
            }
            l.glyph(x, kind + (last && outgoing ? ".capr_tail" : ".capr"), bubble);
            l.text(x0 + g.capWidth + BUBBLE_PAD, rows.get(i), ink, false);
            out.add(l);
        }
    }

    // ---- editing ----------------------------------------------------------------------------------------------------

    @Override
    public Component ownMessages(Views.OwnMessages v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(title(lang.plain("ui.edit.title")));
        rest.add(line().text(2, fw.ellipsize(lang.plain("ui.edit.to", "player", v.partnerName()), g.contentWidth - 4, false), DIM, false));
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        else rest.add(line().text(2, fw.ellipsize(lang.plain(v.count() == 0 ? "ui.edit.none" : "ui.edit.pick"), g.contentWidth - 4, false), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component ownMessageButton(Views.Bubble m, boolean first) {
        Line l = label(1, first);
        int inner = g.buttonWidth(1) - 4;
        l.glyph(2, "mark.pencil", DIM);
        int right = inner - fw.width(m.time(), false) - 4;
        l.text(13, fw.ellipsize(m.body(), right - 13, false), WHITE, false);
        l.rightAligned(inner - 2, m.time(), META);
        return l.build();
    }

    @Override
    public Component editMessage(Views.EditMessage v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(title(lang.plain("ui.edit.title")));
        rest.add(v.notice().isEmpty() ? line().text(2, fw.ellipsize(lang.plain("ui.edit.hint"), g.contentWidth - 4, false), META, false)
                : notice(v.notice()));
        rest.add(meta(null, v.partnerName(), v.message()));
        bubble(rest, TextWrapper.wrap(fw, v.message().body(), bubbleTextWidth), true);
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component settings(Views.Settings v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(title(lang.plain("ui.settings.title")));
        rest.add(line());
        rest.add(line().centred(v.playerName(), WHITE, true));
        rest.add(line().centred(fw.ellipsize(v.packLine(), g.contentWidth - 4, false), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    // ---- photos -----------------------------------------------------------------------------------------------------

    /** The photo hanging from one line, plus the empty lines it covers. */
    private void photo(List<Line> rest, int[] rgb, boolean video) {
        Line l = line().photo(photoX, photoText.build(rgb));
        if (video) l.glyph(photoX + 3, "mark.play", WHITE);
        rest.add(l);
        for (int i = 1; i < PHOTO_LINES; i++) rest.add(line());
    }

    /** "<" + title in the accent colour, as at the top of the Photos app. */
    private Line backTitle(String text, String right) {
        Line l = line().glyph(0, "mark.left", ACCENT).text(9, fw.ellipsize(text, g.contentWidth - 50, false), ACCENT, false);
        if (right != null && !right.isEmpty()) l.rightAligned(g.contentWidth - 2, right, META);
        return l;
    }

    @Override
    public Component photos(Views.Photos v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.photos.title"), v.count() > 0 ? (v.index() + 1) + "/" + v.count() : ""));
        if (v.photo() == null) {
            rest.add(line());
            rest.add(line().glyph((g.contentWidth - 8) / 2, "mark.image", META));
            rest.add(line().centred(lang.plain("ui.photos.empty"), WHITE, false));
            rest.add(line().centred(fw.ellipsize(lang.plain("ui.photos.empty-hint"), g.contentWidth - 4, false), META, false));
        } else {
            photo(rest, v.photo(), v.video());
            rest.add(line().centred(fw.ellipsize(v.info(), g.contentWidth - 4, false), META, false));
        }
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component share(Views.Share v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.share.title"), ""));
        rest.add(line().text(2, fw.ellipsize(lang.plain("ui.share.hint"), g.contentWidth - 4, false), META, false));
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    // ---- Minestagram ------------------------------------------------------------------------------------------------

    /** Ring + face + bold handle, as in the post header. */
    private Line avatar(Line l, int x, UUID id, String handle, int maxWidth) {
        l.glyph(x, "ring", RING);
        if (heads) l.head(x + 2, id);
        String h = fw.ellipsize(handle, maxWidth - 14, true);
        l.text(x + 14, h, WHITE, true);
        return l;
    }

    @Override
    public Component feed(Views.Feed v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        Views.Profile p = v.profile();
        String pager = v.total() > 0 ? (v.index() + 1) + "/" + v.total() : "";
        if (p == null) {
            Line head = line().text(2, lang.plain("ui.gram.title"), WHITE, true);
            if (!pager.isEmpty()) {
                int w = fw.width(pager, false);
                int x = g.contentWidth - 2 - 8 - w - 10;
                head.glyph(x, "mark.left", DIM).text(x + 10, pager, DIM, false).glyph(x + 12 + w, "mark.right", DIM);
            }
            rest.add(head);
        } else {
            Line head = line().text(2, fw.ellipsize("@" + p.handle(), g.contentWidth - 60, true), WHITE, true);
            head.rightAligned(g.contentWidth - 2, lang.plain("ui.gram.post-count", "count", p.posts()), META);
            rest.add(head);
            String bio = p.own() && p.bio().isEmpty() ? lang.plain("ui.gram.edit-hint") : p.bio();
            rest.add(line().text(2, fw.ellipsize(bio, g.contentWidth - 4, false), META, false));
        }
        Views.Post post = v.post();
        if (post == null) {
            rest.add(line());
            rest.add(line().glyph((g.contentWidth - 8) / 2, "mark.image", META));
            rest.add(line().centred(lang.plain(p == null ? "ui.gram.empty" : "ui.gram.empty-profile"), WHITE, false));
            rest.add(line().centred(fw.ellipsize(lang.plain("ui.gram.empty-hint"), g.contentWidth - 4, false), META, false));
            if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
            return screen(v.clock(), rest, inputsPx);
        }
        // author, and who reposted it (or how old it is) on the right
        Line author = avatar(line(), 0, post.author(), post.handle(), 90);
        if (post.reposter() != null) {
            String who = fw.ellipsize(post.reposter(), 44, false);
            int w = fw.width(who, false);
            author.glyph(g.contentWidth - 2 - w - 10, "mark.repost", META).text(g.contentWidth - 2 - w, who, META, false);
        } else {
            author.rightAligned(g.contentWidth - 2, post.age(), META);
        }
        rest.add(author);
        photo(rest, post.photo(), post.video());
        // caption: bold handle, then the text, two lines at most
        String handle = fw.ellipsize(post.handle(), 70, true);
        int indent = fw.width(handle, true) + 4;
        Line c1 = line().text(2, handle, WHITE, true);
        String rest2 = "";
        if (!post.caption().isEmpty()) {
            List<String> start = TextWrapper.wrap(fw, post.caption(), g.contentWidth - 4 - indent);
            c1.text(2 + indent, start.getFirst(), DIM, false);
            if (start.size() > 1) {
                String remainder = String.join(" ", start.subList(1, start.size()));
                List<String> second = TextWrapper.wrap(fw, remainder, g.contentWidth - 4);
                rest2 = second.size() > 1 ? fw.ellipsize(second.getFirst() + " " + second.get(1), g.contentWidth - 4, false) : second.getFirst();
            }
        }
        rest.add(c1);
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        else if (!rest2.isEmpty()) rest.add(line().text(2, rest2, DIM, false));
        else rest.add(line().text(2, lang.plain(post.comments() == 0 ? "ui.gram.no-comments" : "ui.gram.view-comments",
                "count", post.comments()), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component comments(Views.Comments v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.gram.comments"), ""));
        String about = v.caption().isEmpty() ? "@" + v.postHandle() : v.postHandle() + ": " + v.caption();
        rest.add(line().text(2, fw.ellipsize(about, g.contentWidth - 4, false), META, false));
        if (v.hasOlder()) rest.add(line().glyph(2, "mark.up", META).text(12, lang.plain("ui.gram.older-comments"), META, false));
        if (v.comments().isEmpty()) {
            rest.add(line());
            rest.add(line().centred(lang.plain("ui.gram.first-comment"), META, false));
        }
        for (Views.Comment c : v.comments()) {
            String handle = fw.ellipsize(c.handle(), 70, true);
            int indent = fw.width(handle, true) + 4;
            List<String> rows = TextWrapper.wrap(fw, c.body(), g.contentWidth - 4 - indent);
            Line l = line().text(2, handle, WHITE, true).text(2 + indent, rows.getFirst(), DIM, false);
            rest.add(l);
            for (int i = 1; i < rows.size() && i < 3; i++) rest.add(line().text(2 + indent, rows.get(i), DIM, false));
            rest.add(line().text(2 + indent, c.age(), META, false));
        }
        if (v.hasNewer()) rest.add(line().glyph(2, "mark.down", META).text(12, lang.plain("ui.gram.newer-comments"), META, false));
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component account(Views.Account v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(title(lang.plain("ui.gram.title")));
        rest.add(line());
        rest.add(line().centred(lang.plain(v.create() ? "ui.gram.create" : "ui.gram.edit"), WHITE, true));
        rest.add(line().centred(fw.ellipsize(lang.plain("ui.gram.handle-rules"), g.contentWidth - 4, false), META, false));
        rest.add(v.notice().isEmpty() ? line() : notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component compose(Views.Compose v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.gram.new-post"), v.count() > 0 ? (v.index() + 1) + "/" + v.count() : ""));
        if (v.photo() == null) {
            rest.add(line());
            rest.add(line().glyph((g.contentWidth - 8) / 2, "mark.image", META));
            rest.add(line().centred(lang.plain("ui.photos.empty"), WHITE, false));
            rest.add(line().centred(fw.ellipsize(lang.plain("ui.photos.empty-hint"), g.contentWidth - 4, false), META, false));
        } else {
            photo(rest, v.photo(), v.video());
        }
        rest.add(v.notice().isEmpty() ? line().centred(fw.ellipsize(lang.plain("ui.gram.pick-hint"), g.contentWidth - 4, false), META, false)
                : notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component search(Views.Search v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.gram.search"), ""));
        String info = v.results() == 0 ? lang.plain("ui.gram.nobody")
                : v.query().isEmpty() ? lang.plain("ui.gram.newest-accounts") : lang.plain("ui.gram.results", "query", v.query());
        rest.add(line().text(2, fw.ellipsize(info, g.contentWidth - 4, false), META, false));
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component accountButton(UUID id, String handle, int posts, boolean first) {
        Line l = label(1, first);
        int inner = g.buttonWidth(1) - 4;
        String count = lang.plain("ui.gram.post-count", "count", posts);
        avatar(l, 1, id, "@" + handle, inner - fw.width(count, false) - 8);
        l.rightAligned(inner - 2, count, META);
        return l.build();
    }

    @Override
    public Component activity(Views.Activity v, int inputsPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(backTitle(lang.plain("ui.gram.activity"), ""));
        rest.add(line().text(2, fw.ellipsize(lang.plain(v.count() == 0 ? "ui.gram.no-activity" : "ui.gram.activity-hint"),
                g.contentWidth - 4, false), META, false));
        return screen(v.clock(), rest, inputsPx);
    }

    @Override
    public Component activityButton(UUID who, String icon, String text, String age, boolean first) {
        Line l = label(1, first);
        int inner = g.buttonWidth(1) - 4;
        l.glyph(2, "mark." + icon, icon.equals("heart_full") ? LIKED : ACCENT);
        int right = inner - fw.width(age, false) - 4;
        l.text(13, fw.ellipsize(text, right - 13, false), WHITE, false);
        l.rightAligned(inner - 2, age, META);
        return l.build();
    }
}
