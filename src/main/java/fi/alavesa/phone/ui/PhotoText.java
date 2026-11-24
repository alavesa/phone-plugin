package fi.alavesa.phone.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

/**
 * A photo inside a dialog: one coloured glyph per pixel.
 *
 * <p>The pack's {@code phone:photo} font has one glyph per photo row: a white 1x1 pixel that row {@code r} draws
 * {@code r} px below the top of its text line (negative ascent). The whole picture therefore hangs from a single
 * text line, and each pixel takes its colour from the text colour. A bitmap glyph advances by its width + 1 = 2 px,
 * so every row is drawn in two passes: the even columns, a step back to column 1, the odd columns, a step back to
 * column 0. Adjacent glyphs of the same colour share one text component.
 *
 * <p>Size: 16 384 glyphs, roughly 17 000 small components. That is a few hundred kilobytes in the dialog packet
 * before compression; the client draws it like any other text.
 */
public final class PhotoText {

    private final Glyphs glyphs;

    public PhotoText(Glyphs glyphs) {
        this.glyphs = glyphs;
    }

    /**
     * The picture as text, starting at the cursor and leaving it there. The furthest the cursor gets during a row is
     * {@code size + 1} px to the right of the start (the end of the odd pass).
     *
     * @param rgb size*size pixels 0xRRGGBB, row by row; null draws a dark placeholder
     */
    public Component build(int[] rgb) {
        int size = glyphs.photoSize;
        Style pixelStyle = Style.style().font(Glyphs.PHOTO_FONT).shadowColor(ShadowColor.none()).build();
        Style spaceStyle = Style.style().font(Glyphs.FONT).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none()).build();
        String back1 = glyphs.space(-(size - 1));       // from the end of the even pass to column 1
        String back0 = glyphs.space(-(size + 1));       // from the end of the odd pass to column 0
        TextComponent.Builder out = Component.text().style(pixelStyle);
        StringBuilder run = new StringBuilder();
        for (int y = 0; y < size; y++) {
            char ch = (char) (glyphs.photoBase + y);
            for (int pass = 0; pass < 2; pass++) {
                int colour = -1;
                for (int x = pass; x < size; x += 2) {
                    int c = rgb == null ? 0x101014 : rgb[y * size + x] & 0xFFFFFF;
                    if (c != colour && !run.isEmpty()) {
                        out.append(Component.text(run.toString(), TextColor.color(colour)));
                        run.setLength(0);
                    }
                    colour = c;
                    run.append(ch);
                }
                out.append(Component.text(run.toString(), TextColor.color(colour)));
                run.setLength(0);
                out.append(Component.text(pass == 0 ? back1 : back0, spaceStyle));
            }
        }
        return out.build();
    }
}
