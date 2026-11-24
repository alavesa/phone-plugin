package fi.alavesa.phone.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PhotoTextTest {

    private static Glyphs glyphs;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PhotoTextTest.class.getResourceAsStream("/font/glyphs.properties")) {
            glyphs = new Glyphs(in);
        }
    }

    private static void flatten(Component c, List<TextComponent> out) {
        if (c instanceof TextComponent t && !t.content().isEmpty()) out.add(t);
        for (Component child : c.children()) flatten(child, out);
    }

    private int advance(List<TextComponent> parts) {
        int x = 0;
        for (TextComponent t : parts) {
            for (char ch : t.content().toCharArray()) {
                if (ch >= glyphs.photoBase && ch < glyphs.photoBase + glyphs.photoSize && t.font() == null) x += 2;
                else x += spaceAdvance(ch);
            }
        }
        return x;
    }

    private int spaceAdvance(char ch) {
        for (int k = 1; k <= 512; k <<= 1) {
            if (glyphs.ch("space.p" + k).charAt(0) == ch) return k;
            if (glyphs.ch("space.n" + k).charAt(0) == ch) return -k;
        }
        return 2;       // a pixel glyph (its font is inherited from the root)
    }

    @Test
    void everyPixelIsOneGlyphAndTheCursorReturns() {
        int size = glyphs.photoSize;
        int[] rgb = new int[size * size];
        for (int i = 0; i < rgb.length; i++) rgb[i] = (i * 2654435761L % 0xFFFFFF) > 0 ? (int) (i * 2654435761L % 0xFFFFFF) : 1;
        List<TextComponent> parts = new ArrayList<>();
        flatten(new PhotoText(glyphs).build(rgb), parts);
        int pixels = 0;
        for (TextComponent t : parts) {
            for (char ch : t.content().toCharArray()) if (ch >= glyphs.photoBase && ch < glyphs.photoBase + size) pixels++;
        }
        assertEquals(size * size, pixels);
        assertEquals(0, advance(parts), "the picture must leave the cursor where it started");
    }

    @Test
    void equalNeighboursShareAComponent() {
        int size = glyphs.photoSize;
        int[] grey = new int[size * size];
        java.util.Arrays.fill(grey, 0x808080);
        List<TextComponent> parts = new ArrayList<>();
        flatten(new PhotoText(glyphs).build(grey), parts);
        // one run per pass plus one step back per pass
        assertTrue(parts.size() <= size * 4, "a flat picture should be a few hundred components, was " + parts.size());
    }
}
