package fi.alavesa.phone.ui;

import fi.alavesa.phone.config.Lang;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The phone screens drawn with the resource pack: a dark screen panel with a status bar, a header bar, speech
 * bubbles made of stretchable font pieces, icons and marks. Every screen is ONE multi-line text; each line is padded
 * to the same width (see {@link Line}), and the panel glyph on the first line hangs down behind everything else.
 */
public final class FancyRenderer implements ScreenRenderer {

    private static final TextColor TITLE = TextColor.color(0xFFFFFF);
    private static final TextColor META = TextColor.color(0x8E8E93);
    private static final TextColor DIM = TextColor.color(0xC7C7CC);
    private static final TextColor OUT_BUBBLE = TextColor.color(0x2F80ED);
    private static final TextColor OUT_TEXT = TextColor.color(0xFFFFFF);
    private static final TextColor IN_BUBBLE = TextColor.color(0xE5E5EA);
    private static final TextColor IN_TEXT = TextColor.color(0x1C1C1E);
    private static final TextColor ONLINE = TextColor.color(0x34C759);
    private static final TextColor ALERT = TextColor.color(0xFF453A);
    private static final TextColor READ = TextColor.color(0x5AC8FA);

    /** Dialog text is 9 px per line and the text widget adds 4 px of padding around it. */
    private static final int LINE_HEIGHT = 9;
    private static final int TEXT_PADDING = 4;
    /** Panel left over under the last control. */
    private static final int PANEL_BOTTOM = 8;
    /** Space between a bubble's rounded end and its text. */
    private static final int BUBBLE_PAD = 2;
    /** Bubbles never span the whole screen: the other side keeps this much free, like in a real messenger. */
    private static final int BUBBLE_FREE = 34;
    private static final int CONTACT_LABEL_WIDTH = 204;

    private final Glyphs g;
    private final FontWidths fw;
    private final Lang lang;
    private final boolean heads;
    private final int bubbleTextWidth;

    public FancyRenderer(Glyphs glyphs, FontWidths widths, Lang lang, boolean playerHeads) {
        this.g = glyphs;
        this.fw = widths;
        this.lang = lang;
        this.heads = playerHeads;
        this.bubbleTextWidth = g.contentWidth - 2 * g.tailWidth - 2 * g.capWidth - 2 * BUBBLE_PAD - BUBBLE_FREE;
    }

    @Override
    public int bodyWidth() {
        // the text widget wraps at width - 8; a line is one pixel wider than the panel (its advance)
        return g.panelWidth + 10;
    }

    private Line line() {
        return new Line(g, fw);
    }

    /** Puts the status line (panel + clock) and the header line (bar + title) in front of {@code rest}. */
    private Component screen(String clock, String title, Line titleLine, List<Line> rest, int belowPx) {
        int lines = rest.size() + 2;
        int needed = g.panelTop + lines * LINE_HEIGHT + TEXT_PADDING + belowPx + PANEL_BOTTOM;
        Line status = line().glyph(-g.panelMargin, "panel." + g.panelHeightFor(needed), null).centred(clock, DIM, false);
        Line header = titleLine != null ? titleLine : line().glyph(-g.panelMargin, "header", null).centred(title, TITLE, true);
        List<Component> out = new ArrayList<>(lines);
        out.add(status.build());
        out.add(header.build());
        for (Line l : rest) out.add(l.build());
        return Component.join(JoinConfiguration.newlines(), out);
    }

    @Override
    public Component home(Views.Home v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(lang.plain("ui.home.greeting", "player", v.playerName()), TITLE, false));
        rest.add(line().centred(v.unread() > 0 ? lang.plain("ui.home.unread", "count", v.unread()) : lang.plain("ui.home.no-unread"),
                v.unread() > 0 ? ALERT : META, false));
        rest.add(line());
        return screen(v.clock(), lang.plain("ui.home.title"), null, rest, belowPx);
    }

    @Override
    public Component appButton(String app, Component label, int badge) {
        Component out = Component.text(g.ch("icon." + app)).font(Glyphs.FONT).color(TITLE).shadowColor(ShadowColor.none())
                .append(Component.text(" ").font(Glyphs.DEFAULT_FONT))
                .append(label.font(Glyphs.DEFAULT_FONT));
        if (badge > 0) {
            out = out.append(Component.text(g.space(3) + g.ch("mark.dot")).font(Glyphs.FONT).color(ALERT).shadowColor(ShadowColor.none()))
                    .append(Component.text(badge > 99 ? "99+" : String.valueOf(badge)).font(Glyphs.DEFAULT_FONT).color(ALERT));
        }
        return out;
    }

    @Override
    public Component contacts(Views.Contacts v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        String info;
        if (!v.filter().isEmpty()) info = lang.plain("ui.contacts.filter", "filter", v.filter());
        else if (v.totalUnread() > 0) info = lang.plain("ui.home.unread", "count", v.totalUnread());
        else info = lang.plain("ui.contacts.hint");
        rest.add(line());
        rest.add(line().centred(fw.ellipsize(info, g.contentWidth - 8, false), v.totalUnread() > 0 && v.filter().isEmpty() ? ALERT : META, false));
        if (v.page().isEmpty()) {
            rest.add(line().centred(lang.plain("ui.contacts.empty"), META, false));
        } else if (v.pageCount() > 1) {
            rest.add(line().centred(lang.plain("ui.page", "page", v.pageIndex() + 1, "pages", v.pageCount()), META, false));
        }
        return screen(v.clock(), lang.plain("ui.contacts.title"), null, rest, belowPx);
    }

    @Override
    public int contactButtonWidth() {
        return g.contentWidth;
    }

    @Override
    public Component contactButton(Views.Contact c) {
        Line l = new Line(g, fw, CONTACT_LABEL_WIDTH);
        int x = 0;
        // unread dot, else a presence dot
        l.glyph(x, "mark.dot", c.unread() > 0 ? ALERT : (c.online() ? ONLINE : META));
        x += 9;
        if (heads) {
            l.head(x, c.id());
            x += Line.HEAD_WIDTH + 3;
        }
        String name = fw.ellipsize(c.name(), 70, true);
        l.text(x, name, TITLE, true);
        x += fw.width(name, true) + 5;
        int right = CONTACT_LABEL_WIDTH;
        if (!c.time().isEmpty()) right -= fw.width(c.time(), false) + 4;
        if (!c.preview().isEmpty() && right - x > 20) {
            String preview = (c.previewFromMe() ? lang.plain("ui.contacts.you-prefix") : "") + c.preview();
            l.text(x, fw.ellipsize(preview, right - x, false), c.unread() > 0 ? DIM : META, false);
        }
        if (!c.time().isEmpty()) l.rightAligned(CONTACT_LABEL_WIDTH, c.time(), META);
        return l.build();
    }

    @Override
    public int messageLines(String body) {
        // the bubble rows plus the line with name / time above it
        return TextWrapper.wrap(fw, body, bubbleTextWidth).size() + 1;
    }

    @Override
    public Component conversation(Views.Conversation v, int belowPx) {
        // header: presence dot + name, centred as one unit
        String name = fw.ellipsize(v.partnerName(), g.contentWidth - 60, true);
        int nameW = fw.width(name, true);
        int start = (g.contentWidth - nameW - 9) / 2;
        Line header = line().glyph(-g.panelMargin, "header", null)
                .glyph(start, "mark.dot", v.online() ? ONLINE : META)
                .text(start + 9, name, TITLE, true);

        List<Line> rest = new ArrayList<>();
        boolean alert = !v.notice().isEmpty() || v.blocked();
        String info = !v.notice().isEmpty() ? v.notice() : v.blocked() ? lang.plain("ui.conversation.blocked")
                : v.hasOlder() ? lang.plain("ui.conversation.older-above") : "";
        rest.add(line().centred(fw.ellipsize(info, g.contentWidth - 4, false), alert ? ALERT : META, false));
        if (v.messages().isEmpty()) {
            rest.add(line());
            rest.add(line().centred(lang.plain("ui.conversation.empty"), META, false));
            rest.add(line());
        }
        for (Views.Bubble b : v.messages()) {
            rest.add(meta(v, b));
            bubble(rest, TextWrapper.wrap(fw, b.body(), bubbleTextWidth), b.outgoing());
        }
        if (v.hasNewer()) rest.add(line().centred(lang.plain("ui.conversation.newer-below"), META, false));
        return screen(v.clock(), "", header, rest, belowPx);
    }

    /** The small line above a bubble: head, name and time for incoming, time and read mark for outgoing. */
    private Line meta(Views.Conversation v, Views.Bubble b) {
        Line l = line();
        if (b.outgoing()) {
            int right = g.contentWidth - g.tailWidth;
            int markW = g.advance("mark.ticks");
            int timeW = fw.width(b.time(), false);
            l.text(right - markW - 2 - timeW, b.time(), META, false);
            l.glyph(right - markW, b.read() ? "mark.ticks" : "mark.tick", b.read() ? READ : META);
        } else {
            int x = g.tailWidth;
            if (heads) {
                l.head(x, v.partnerId());
                x += Line.HEAD_WIDTH + 3;
            }
            String name = fw.ellipsize(v.partnerName(), 110, true);
            l.text(x, name, DIM, true);
            x += fw.width(name, true) + 5;
            l.text(x, b.time(), META, false);
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

    @Override
    public Component photoResult(Views.PhotoResult v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(lang.plain("ui.camera.captured"), TITLE, false));
        rest.add(line().centred(lang.plain("ui.camera.zoom", "zoom", v.zoom()), META, false));
        if (!v.location().isEmpty()) rest.add(line().centred(fw.ellipsize(v.location(), g.contentWidth - 8, false), META, false));
        rest.add(line());
        return screen(v.clock(), lang.plain("ui.camera.title"), null, rest, belowPx);
    }
}
