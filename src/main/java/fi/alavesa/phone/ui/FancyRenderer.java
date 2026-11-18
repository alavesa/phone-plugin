package fi.alavesa.phone.ui;

import fi.alavesa.phone.config.Lang;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;

/**
 * The phone screens drawn with the resource pack: a dark screen panel with a status bar, a header bar, speech
 * bubbles made of stretchable font pieces, round app icons and marks. Every screen is ONE multi-line text; each line
 * is padded to the same width (see {@link Line}), and the panel glyph on the first line hangs down behind
 * everything else, including the inputs and buttons the dialog stacks under the text.
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
    private static final int ROW_LABEL_WIDTH = 204;

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

    @Override
    public int rowButtonWidth() {
        return g.contentWidth;
    }

    @Override
    public int appButtonWidth() {
        return g.appButtonWidth;
    }

    private Line line() {
        return new Line(g, fw);
    }

    private Line notice(String text) {
        return line().centred(fw.ellipsize(text, g.contentWidth - 4, false), ALERT, false);
    }

    /**
     * Assembles a screen: the status line (panel + {@code status} text), an optional header line (bar + title) and
     * the rest. The panel is picked tall enough to reach behind {@code belowPx} of controls.
     */
    private Component screen(Line statusText, Line header, List<Line> rest, int belowPx) {
        int lines = rest.size() + (header == null ? 1 : 2);
        int needed = g.panelTop + lines * LINE_HEIGHT + TEXT_PADDING + belowPx + PANEL_BOTTOM;
        // the panel has to be the first glyph of the line: it is drawn first, everything else on top
        Line status = line().glyph(-g.panelMargin, "panel." + g.panelHeightFor(needed), null);
        List<Component> out = new ArrayList<>(lines);
        out.add(status.append(statusText).build());
        if (header != null) out.add(header.build());
        for (Line l : rest) out.add(l.build());
        return Component.join(JoinConfiguration.newlines(), out);
    }

    private Component screen(String clock, String title, List<Line> rest, int belowPx) {
        return screen(line().centred(clock, DIM, false), header(title), rest, belowPx);
    }

    private Line header(String title) {
        return line().glyph(-g.panelMargin, "header", null).centred(fw.ellipsize(title, g.contentWidth - 20, true), TITLE, true);
    }

    // ---- lock screen ------------------------------------------------------------------------------------------------

    @Override
    public Component lock(Views.Lock v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
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
            clock.glyph(x, name, TITLE);
            x += g.advance(name);
        }
        rest.add(clock);
        rest.add(line());
        rest.add(line().centred(v.date(), DIM, false));
        rest.add(line());
        String prompt = switch (v.mode()) {
            case LOCKED -> lang.plain("ui.lock.enter", "player", v.playerName());
            case SETUP -> lang.plain("ui.lock.setup");
            case CHANGE -> lang.plain("ui.lock.change");
        };
        rest.add(line().centred(fw.ellipsize(prompt, g.contentWidth - 4, false), TITLE, false));
        rest.add(v.notice().isEmpty() ? line() : notice(v.notice()));
        // status bar: a small padlock where the other screens show the time
        Line status = line().glyph((g.contentWidth - g.advance("mark.lock")) / 2, "mark.lock", DIM);
        return screen(status, null, rest, belowPx);
    }

    // ---- home -------------------------------------------------------------------------------------------------------

    @Override
    public Component home(Views.Home v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(lang.plain("ui.home.greeting", "player", v.playerName()), TITLE, false));
        rest.add(line().centred(v.unread() > 0 ? lang.plain("ui.home.unread", "count", v.unread()) : lang.plain("ui.home.no-unread"),
                v.unread() > 0 ? ALERT : META, false));
        // room for the app icons, which reach up from their buttons over the end of this text
        int free = g.appIconRows - 10 - TEXT_PADDING;
        for (int i = 0; i < (free + LINE_HEIGHT - 1) / LINE_HEIGHT + 1; i++) rest.add(line());
        return screen(line().centred(v.clock(), DIM, false), null, rest, belowPx);
    }

    /**
     * An app on the home screen. The label of the (vanilla) button carries a tile glyph that covers the button's
     * face and draws the round icon above it; the app name is then written over the covered face. The client does
     * not clip a button label that fits its button, so the icon may stand taller than the button.
     */
    @Override
    public Component appButton(String app, String label, int badge) {
        int inner = g.appButtonWidth - 4;       // a centred label of this width starts 2 px inside the button
        Line l = new Line(g, fw, inner);
        l.glyph(-1, "app." + app, null);
        if (badge > 0) {
            String name = "badge." + (badge > 9 ? "9+" : String.valueOf(badge));
            l.glyph(-1 + (g.appButtonWidth - 2 + 32) / 2 - 9, name, null);
        }
        l.centred(fw.ellipsize(label, inner - 2, false), DIM, false);
        return l.build();
    }

    // ---- contacts ---------------------------------------------------------------------------------------------------

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
        return screen(v.clock(), lang.plain("ui.contacts.title"), rest, belowPx);
    }

    @Override
    public Component contactButton(Views.Contact c) {
        Line l = new Line(g, fw, ROW_LABEL_WIDTH);
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
        int right = ROW_LABEL_WIDTH;
        if (!c.time().isEmpty()) right -= fw.width(c.time(), false) + 4;
        if (!c.preview().isEmpty() && right - x > 20) {
            String preview = (c.previewFromMe() ? lang.plain("ui.contacts.you-prefix") : "") + c.preview();
            l.text(x, fw.ellipsize(preview, right - x, false), c.unread() > 0 ? DIM : META, false);
        }
        if (!c.time().isEmpty()) l.rightAligned(ROW_LABEL_WIDTH, c.time(), META);
        return l.build();
    }

    // ---- conversation -----------------------------------------------------------------------------------------------

    @Override
    public int messageLines(String body) {
        // the bubble rows plus the line with name / time above it
        return TextWrapper.wrap(fw, body, bubbleTextWidth).size() + 1;
    }

    private Line nameHeader(String partnerName, boolean online) {
        // presence dot + name, centred as one unit
        String name = fw.ellipsize(partnerName, g.contentWidth - 60, true);
        int start = (g.contentWidth - fw.width(name, true) - 9) / 2;
        return line().glyph(-g.panelMargin, "header", null)
                .glyph(start, "mark.dot", online ? ONLINE : META)
                .text(start + 9, name, TITLE, true);
    }

    @Override
    public Component conversation(Views.Conversation v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        boolean alert = !v.notice().isEmpty() || v.blocked();
        String info = !v.notice().isEmpty() ? v.notice() : v.blocked() ? lang.plain("ui.conversation.blocked")
                : v.hasOlder() ? lang.plain("ui.conversation.older-above") : "";
        Line top = line();
        if (v.hasOlder() && !alert) top.glyph(4, "mark.up", META);
        rest.add(top.centred(fw.ellipsize(info, g.contentWidth - 30, false), alert ? ALERT : META, false));
        if (v.messages().isEmpty()) {
            rest.add(line());
            rest.add(line().centred(lang.plain("ui.conversation.empty"), META, false));
            rest.add(line());
        }
        for (Views.Bubble b : v.messages()) {
            rest.add(meta(v.partnerId(), v.partnerName(), b));
            bubble(rest, TextWrapper.wrap(fw, b.body(), bubbleTextWidth), b.outgoing());
        }
        if (v.hasNewer()) {
            rest.add(line().glyph(4, "mark.down", META).centred(lang.plain("ui.conversation.newer-below"), META, false));
        }
        return screen(line().centred(v.clock(), DIM, false), nameHeader(v.partnerName(), v.online()), rest, belowPx);
    }

    /** The small line above a bubble: head, name and time for incoming, time and read mark for outgoing. */
    private Line meta(java.util.UUID partnerId, String partnerName, Views.Bubble b) {
        Line l = line();
        String time = b.edited() ? lang.plain("ui.conversation.edited") + " " + b.time() : b.time();
        if (b.outgoing()) {
            int right = g.contentWidth - g.tailWidth;
            int markW = g.advance("mark.ticks");
            l.text(right - markW - 2 - fw.width(time, false), time, META, false);
            l.glyph(right - markW, b.read() ? "mark.ticks" : "mark.tick", b.read() ? READ : META);
        } else {
            int x = g.tailWidth;
            if (heads) {
                l.head(x, partnerId);
                x += Line.HEAD_WIDTH + 3;
            }
            String name = fw.ellipsize(partnerName, 90, true);
            l.text(x, name, DIM, true);
            x += fw.width(name, true) + 5;
            l.text(x, time, META, false);
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
    public Component ownMessages(Views.OwnMessages v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(fw.ellipsize(lang.plain("ui.edit.to", "player", v.partnerName()), g.contentWidth - 8, false), DIM, false));
        if (!v.notice().isEmpty()) rest.add(notice(v.notice()));
        else rest.add(line().centred(lang.plain(v.count() == 0 ? "ui.edit.none" : "ui.edit.pick"), META, false));
        return screen(v.clock(), lang.plain("ui.edit.title"), rest, belowPx);
    }

    @Override
    public Component ownMessageButton(Views.Bubble m) {
        Line l = new Line(g, fw, ROW_LABEL_WIDTH);
        l.glyph(0, "mark.pencil", DIM);
        int right = ROW_LABEL_WIDTH - fw.width(m.time(), false) - 4;
        l.text(11, fw.ellipsize(m.body(), right - 11, false), TITLE, false);
        l.rightAligned(ROW_LABEL_WIDTH, m.time(), META);
        return l.build();
    }

    @Override
    public Component editMessage(Views.EditMessage v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(v.notice().isEmpty() ? line().centred(lang.plain("ui.edit.hint"), META, false) : notice(v.notice()));
        rest.add(meta(null, v.partnerName(), v.message()));
        bubble(rest, TextWrapper.wrap(fw, v.message().body(), bubbleTextWidth), true);
        rest.add(line());
        return screen(v.clock(), lang.plain("ui.edit.title"), rest, belowPx);
    }

    // ---- camera and settings ----------------------------------------------------------------------------------------

    @Override
    public Component photoResult(Views.PhotoResult v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(v.seconds() > 0 ? lang.plain("ui.camera.recorded", "seconds", String.format(java.util.Locale.ROOT, "%.1f", v.seconds()))
                : lang.plain("ui.camera.captured"), TITLE, false));
        rest.add(line().centred(lang.plain("ui.camera.zoom", "zoom", v.zoom()), META, false));
        if (!v.location().isEmpty()) rest.add(line().centred(fw.ellipsize(v.location(), g.contentWidth - 8, false), META, false));
        rest.add(line());
        return screen(v.clock(), lang.plain("ui.camera.title"), rest, belowPx);
    }

    @Override
    public Component settings(Views.Settings v, int belowPx) {
        List<Line> rest = new ArrayList<>();
        rest.add(line());
        rest.add(line().centred(v.playerName(), TITLE, false));
        rest.add(line().centred(fw.ellipsize(v.packLine(), g.contentWidth - 8, false), META, false));
        rest.add(line());
        return screen(v.clock(), lang.plain("ui.settings.title"), rest, belowPx);
    }
}
