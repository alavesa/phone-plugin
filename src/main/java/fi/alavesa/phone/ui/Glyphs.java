package fi.alavesa.phone.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.kyori.adventure.key.Key;

/**
 * The glyphs of the pack's {@code phone:ui} font and the layout numbers that go with them. Everything is read from
 * font/glyphs.properties, which tools/gen_pack.py writes together with the font itself, so the two cannot drift.
 */
public final class Glyphs {

    public static final Key FONT = Key.key("phone", "ui");
    public static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    private record Glyph(String ch, int advance) {}

    private final Map<String, Glyph> glyphs = new HashMap<>();
    public final int contentWidth;
    public final int panelWidth;
    public final int panelMargin;
    public final int panelTop;
    public final int capWidth;
    public final int tailWidth;
    /** Width of an app button on the home screen and how far its icon reaches above the button. */
    public final int appButtonWidth;
    public final int appIconRows;
    public final int[] panelHeights;
    /** Bubble tile widths, widest first. */
    public final int[] tileWidths;
    public final List<Integer> zoomLevels;
    private final int spacePowers;

    public Glyphs(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("glyph.")) continue;
            String[] v = p.getProperty(key).split(",");
            glyphs.put(key.substring(6), new Glyph(Character.toString(Integer.parseInt(v[0], 16)), Integer.parseInt(v[1])));
        }
        contentWidth = num(p, "layout.content_width");
        panelWidth = num(p, "layout.panel_width");
        panelMargin = num(p, "layout.panel_margin");
        panelTop = num(p, "layout.panel_top");
        capWidth = num(p, "layout.cap_width");
        tailWidth = num(p, "layout.tail_width");
        spacePowers = num(p, "layout.space_powers");
        appButtonWidth = num(p, "layout.app_button_width");
        appIconRows = num(p, "layout.app_icon_rows");
        panelHeights = ints(p, "layout.panel_heights");
        int[] tiles = ints(p, "layout.tile_widths");
        tileWidths = new int[tiles.length];
        for (int i = 0; i < tiles.length; i++) tileWidths[i] = tiles[tiles.length - 1 - i];
        List<Integer> zooms = new ArrayList<>();
        for (int z : ints(p, "camera.zoom_levels")) zooms.add(z);
        zoomLevels = List.copyOf(zooms);
    }

    private static int num(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key).trim());
    }

    private static int[] ints(Properties p, String key) {
        String[] parts = p.getProperty(key).split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private Glyph glyph(String name) {
        Glyph g = glyphs.get(name);
        if (g == null) throw new IllegalArgumentException("unknown glyph " + name);
        return g;
    }

    /** The character of a glyph, to be drawn in {@link #FONT}. */
    public String ch(String name) {
        return glyph(name).ch;
    }

    /** How far a glyph moves the cursor (GUI pixels). */
    public int advance(String name) {
        return glyph(name).advance;
    }

    /** Space characters (in {@link #FONT}) that move the cursor by exactly {@code px}, negative = left. */
    public String space(int px) {
        if (px == 0) return "";
        StringBuilder sb = new StringBuilder();
        String sign = px > 0 ? "space.p" : "space.n";
        int left = Math.abs(px);
        int top = 1 << (spacePowers - 1);
        while (left >= top * 2) {
            sb.append(ch(sign + top));
            left -= top;
        }
        for (int bit = top; bit >= 1; bit >>= 1) {
            if ((left & bit) != 0) sb.append(ch(sign + bit));
        }
        return sb.toString();
    }

    /** The smallest panel that is at least {@code needed} px tall (the tallest one if none is). */
    public int panelHeightFor(int needed) {
        for (int h : panelHeights) {
            if (h >= needed) return h;
        }
        return panelHeights[panelHeights.length - 1];
    }
}
