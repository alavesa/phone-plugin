package fi.alavesa.phone.ui;

/** Small copies of a 128x128 picture for the conversation screen. */
public final class Thumb {

    public static final int SIZE = 64;

    private Thumb() {}

    /** The picture at half size: every pixel is the average of a 2x2 block. */
    public static int[] of(int[] rgb128) {
        int src = 128, out = SIZE, f = src / out;
        int[] t = new int[out * out];
        for (int y = 0; y < out; y++) {
            for (int x = 0; x < out; x++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < f; dy++) {
                    for (int dx = 0; dx < f; dx++) {
                        int c = rgb128[(y * f + dy) * src + x * f + dx];
                        r += c >> 16 & 255;
                        g += c >> 8 & 255;
                        b += c & 255;
                    }
                }
                int n = f * f;
                t[y * out + x] = (r / n) << 16 | (g / n) << 8 | b / n;
            }
        }
        return t;
    }
}
