package fi.alavesa.phone.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel widths of text in Minecraft's default font, so the server can wrap and align text exactly like the client
 * will draw it. The table (font/default_widths.tsv) is generated from the vanilla client jar by tools/gen_widths.py
 * and covers every character of the default bitmap font: ASCII, Latin-1 (ä ö å ...), Greek, Cyrillic and the
 * symbols Mojang drew.
 *
 * <p>Supported cases:
 * <ul>
 *   <li>Regular text: exact.</li>
 *   <li>Bold: exact (+1 px per character, as the client does).</li>
 *   <li>Characters outside the table (CJK, emoji, most symbols): the client draws them with the unifont fallback,
 *       whose widths this plugin does not ship. They are measured as {@link #UNKNOWN_ADVANCE} px, the widest such
 *       glyph, so a line never overflows its bubble; the bubble may come out a few pixels too wide.</li>
 * </ul>
 */
public final class FontWidths {

    /** Estimate for characters the client takes from unifont: 16 px wide at half scale, plus 1 px spacing. */
    public static final int UNKNOWN_ADVANCE = 9;

    private final int[] ascii = new int[256];
    private final Map<Integer, Integer> other = new HashMap<>();

    public FontWidths(InputStream in) throws IOException {
        java.util.Arrays.fill(ascii, -1);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                int cp = Integer.parseInt(line.substring(0, tab), 16);
                int adv = Integer.parseInt(line.substring(tab + 1).trim());
                if (cp < 256) ascii[cp] = adv;
                else other.put(cp, adv);
            }
        }
    }

    /** True when the width of this character is known exactly. */
    public boolean known(int codePoint) {
        return codePoint < 256 ? ascii[codePoint] >= 0 : other.containsKey(codePoint);
    }

    public int width(int codePoint, boolean bold) {
        int w;
        if (codePoint < 256) w = ascii[codePoint];
        else w = other.getOrDefault(codePoint, -1);
        if (w < 0) {
            // zero-width things stay zero; everything else gets the unifont estimate
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                    || type == Character.CONTROL) return 0;
            w = UNKNOWN_ADVANCE;
        }
        return bold && w > 0 ? w + 1 : w;
    }

    public int width(CharSequence text, boolean bold) {
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            total += width(cp, bold);
            i += Character.charCount(cp);
        }
        return total;
    }

    /** Cuts {@code text} so that it fits {@code maxPx}, ending in "..." when something was cut. */
    public String ellipsize(String text, int maxPx, boolean bold) {
        if (width(text, bold) <= maxPx) return text;
        int budget = maxPx - width("...", bold);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = width(cp, bold);
            if (used + w > budget) break;
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return sb.toString().stripTrailing() + "...";
    }
}
