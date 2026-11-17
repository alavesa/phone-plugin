package fi.alavesa.phone.ui;

import java.util.ArrayList;
import java.util.List;

/** Cleans up typed text and wraps it to a pixel width with {@link FontWidths}. */
public final class TextWrapper {

    private TextWrapper() {}

    /**
     * Makes typed text safe to store and draw: line breaks and tabs become spaces, control characters, legacy
     * colour codes (§x) and private-use characters are removed (the phone UI glyphs live in the private-use area;
     * a player must not be able to type them into a message), runs of spaces collapse, and the result is cut to
     * {@code maxLength} characters.
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(input.length(), maxLength));
        boolean lastSpace = true;
        int count = 0;
        for (int i = 0; i < input.length() && count < maxLength; ) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                if (i < input.length()) i += Character.charCount(input.codePointAt(i));
                continue;
            }
            if (cp == '\n' || cp == '\r' || cp == '\t') cp = ' ';
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) continue;
            if (cp == ' ') {
                if (lastSpace) continue;
                lastSpace = true;
            } else {
                lastSpace = false;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString().strip();
    }

    /**
     * Word-wraps {@code text} into lines no wider than {@code maxPx}. Breaks at spaces; a word wider than a whole
     * line is split between characters. Never returns an empty list.
     */
    public static List<String> wrap(FontWidths fw, String text, int maxPx) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineW = 0;
        int space = fw.width(' ', false);
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int wordW = fw.width(word, false);
            if (line.length() > 0 && lineW + space + wordW <= maxPx) {
                line.append(' ').append(word);
                lineW += space + wordW;
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
                lineW = 0;
            }
            if (wordW <= maxPx) {
                line.append(word);
                lineW = wordW;
                continue;
            }
            // a word longer than a line: fill lines character by character
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                int w = fw.width(cp, false);
                if (lineW + w > maxPx && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineW = 0;
                }
                line.appendCodePoint(cp);
                lineW += w;
                i += Character.charCount(cp);
            }
        }
        if (line.length() > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
