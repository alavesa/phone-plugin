package fi.alavesa.phone.ui;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * One line of a phone screen, built by placing glyphs and text at absolute x positions (GUI pixels from the left
 * edge of the content area). The line keeps track of the cursor and inserts the positive/negative spaces of the
 * {@code phone:ui} font to get there, so pieces may overlap: the client draws glyphs in text order, later ones on
 * top (background first, text last).
 *
 * <p>{@link #build()} pads every line to the same total width. Dialog text is centred per line; lines of equal
 * width therefore stay aligned with each other.
 *
 * <p>One client rule shapes this class: the dialog text widget takes the widest finished line as its width and
 * then counts lines by wrapping at that width, measuring each line from its start as it goes. If the cursor ever
 * travels further right than the finished line is wide (a 232 px panel glyph on a 216 px line, even though a
 * negative space pulls the cursor back right after), the widget reserves room for lines that are never drawn.
 * So a screen line starts at the panel's left edge and is padded to the panel's advance: the content area sits
 * {@code panelMargin} px inside it and nothing ever reaches past the end.
 */
public final class Line {

    /** Width of a player-head object in text (8 px sprite, advance 8). */
    public static final int HEAD_WIDTH = 8;

    private final Glyphs glyphs;
    private final FontWidths widths;
    private final TextComponent.Builder out = Component.text();
    /** Width of the content area: x runs from 0 to this. */
    private final int width;
    /** Where x = 0 lies, measured from the start of the line. */
    private final int origin;
    /** Advance of the finished line. */
    private final int total;
    /** Cursor, measured from the start of the line. */
    private int cursor;

    /** A line of a phone screen: content area inside the panel. */
    public Line(Glyphs glyphs, FontWidths widths) {
        this.glyphs = glyphs;
        this.widths = widths;
        this.width = glyphs.contentWidth;
        this.origin = glyphs.panelMargin;
        this.total = glyphs.advance("panel." + glyphs.panelHeights[0]);
    }

    /** A free-standing line of exactly {@code width} px (button labels). */
    public Line(Glyphs glyphs, FontWidths widths, int width) {
        this.glyphs = glyphs;
        this.widths = widths;
        this.width = width;
        this.origin = 0;
        this.total = width;
    }

    private static Style style(net.kyori.adventure.key.Key font, TextColor colour, boolean bold) {
        // no shadow: a shadow under a background glyph would be a second, dark copy of it
        return Style.style().font(font).color(colour == null ? NamedTextColor.WHITE : colour)
                .shadowColor(ShadowColor.none()).decoration(TextDecoration.BOLD, bold).build();
    }

    private void moveTo(int x) {
        int target = origin + x;
        if (target != cursor) {
            out.append(Component.text(glyphs.space(target - cursor), style(Glyphs.FONT, null, false)));
            cursor = target;
        }
    }

    /** Draws a {@code phone:ui} glyph with its left edge at {@code x}; {@code colour} tints it (null = as drawn). */
    public Line glyph(int x, String name, TextColor colour) {
        moveTo(x);
        out.append(Component.text(glyphs.ch(name), style(Glyphs.FONT, colour, false)));
        cursor += glyphs.advance(name);
        return this;
    }

    /** Draws default-font text starting at {@code x}. */
    public Line text(int x, String text, TextColor colour, boolean bold) {
        if (text.isEmpty()) return this;
        moveTo(x);
        out.append(Component.text(text, style(Glyphs.DEFAULT_FONT, colour, bold)));
        cursor += widths.width(text, bold);
        return this;
    }

    public Line text(int x, String text, TextColor colour) {
        return text(x, text, colour, false);
    }

    /** Text centred on the line. */
    public Line centred(String text, TextColor colour, boolean bold) {
        return text((width - widths.width(text, bold)) / 2, text, colour, bold);
    }

    /** Text whose right edge is at {@code right}. */
    public Line rightAligned(int right, String text, TextColor colour) {
        return text(right - widths.width(text, false), text, colour, false);
    }

    /** The face of a player (8x8), drawn by the client from the skin of that profile. */
    public Line head(int x, UUID player) {
        moveTo(x);
        out.append(Component.object(ObjectContents.playerHead(player)).style(style(Glyphs.DEFAULT_FONT, null, false)));
        cursor += HEAD_WIDTH;
        return this;
    }

    public int widthOf(String text, boolean bold) {
        return widths.width(text, bold);
    }

    public Component build() {
        moveTo(total - origin);
        return out.build();
    }
}
