package fi.alavesa.phone.camera;

import java.util.List;

/**
 * The camera filters. One table drives both places a filter shows up:
 * <ul>
 *   <li>the viewfinder: the pack's post effect {@code phone:filter_<id>} (resourcepack/assets/phone/post_effect, generated
 *       from this table by tools/gen_pack.py - keep the numbers in sync) running {@code post/filter.fsh}</li>
 *   <li>the picture that is saved: {@link #apply} runs the same maths on the 128x128 pixels of every frame</li>
 * </ul>
 * Per pixel: grey mix, sepia mix, saturation, contrast, brightness, tint, vignette; "pixel" averages blocks.
 */
public final class Filters {

    /** @param screenPixel block size of the pixelation in the viewfinder (screen px), @param photoPixel in the 128 px picture */
    public record Filter(String id, double gray, double sepia, double saturation, double contrast, double bright, double[] tint,
                         double vignette, int screenPixel, int photoPixel) {}

    private static final double[] WHITE = {1, 1, 1};

    public static final List<Filter> ALL = List.of(
            new Filter("mono", 1, 0, 1, 1.05, 0, WHITE, 0, 0, 0),
            new Filter("noir", 1, 0, 1, 1.55, -0.05, WHITE, 0.55, 0, 0),
            new Filter("sepia", 0, 0.9, 1, 1.05, 0, WHITE, 0.15, 0, 0),
            new Filter("vivid", 0, 0, 1.55, 1.12, 0, WHITE, 0, 0, 0),
            new Filter("warm", 0, 0, 1.1, 1.0, 0, new double[] {1.08, 1.0, 0.86}, 0, 0, 0),
            new Filter("cool", 0, 0, 1.0, 1.0, 0, new double[] {0.88, 0.98, 1.1}, 0, 0, 0),
            new Filter("fade", 0, 0, 0.7, 0.82, 0.1, WHITE, 0, 0, 0),
            new Filter("vignette", 0, 0, 1, 1.0, 0, WHITE, 0.75, 0, 0),
            new Filter("pixel", 0, 0, 1.2, 1.0, 0, WHITE, 0, 10, 4));

    private Filters() {}

    /** Filter number {@code index}: 0 = no filter (null), 1.. = {@link #ALL}; wraps around. */
    public static Filter at(int index) {
        int n = ALL.size() + 1;
        int i = Math.floorMod(index, n);
        return i == 0 ? null : ALL.get(i - 1);
    }

    public static int count() {
        return ALL.size() + 1;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    /** The picture with the filter applied; a new array ({@code rgb} is left alone). {@code size}: edge of the square picture. */
    public static int[] apply(Filter f, int[] rgb, int size) {
        if (f == null) return rgb;
        int[] src = rgb;
        if (f.photoPixel() > 1) {
            src = new int[rgb.length];
            int b = f.photoPixel();
            for (int by = 0; by < size; by += b) {
                for (int bx = 0; bx < size; bx += b) {
                    long r = 0, g = 0, bl = 0;
                    int n = 0;
                    for (int y = by; y < Math.min(size, by + b); y++) {
                        for (int x = bx; x < Math.min(size, bx + b); x++) {
                            int c = rgb[y * size + x];
                            r += c >> 16 & 255;
                            g += c >> 8 & 255;
                            bl += c & 255;
                            n++;
                        }
                    }
                    int avg = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
                    for (int y = by; y < Math.min(size, by + b); y++) {
                        for (int x = bx; x < Math.min(size, bx + b); x++) src[y * size + x] = avg;
                    }
                }
            }
        }
        int[] out = new int[rgb.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int c = src[y * size + x];
                double r = (c >> 16 & 255) / 255.0, g = (c >> 8 & 255) / 255.0, b = (c & 255) / 255.0;
                double l = 0.299 * r + 0.587 * g + 0.114 * b;
                r += (l - r) * f.gray();
                g += (l - g) * f.gray();
                b += (l - b) * f.gray();
                r += (l * 1.07 - r) * f.sepia();
                g += (l * 0.74 - g) * f.sepia();
                b += (l * 0.43 - b) * f.sepia();
                double l2 = 0.299 * r + 0.587 * g + 0.114 * b;
                r = l2 + (r - l2) * f.saturation();
                g = l2 + (g - l2) * f.saturation();
                b = l2 + (b - l2) * f.saturation();
                r = (r - 0.5) * f.contrast() + 0.5;
                g = (g - 0.5) * f.contrast() + 0.5;
                b = (b - 0.5) * f.contrast() + 0.5;
                r = r * f.tint()[0] + f.bright();
                g = g * f.tint()[1] + f.bright();
                b = b * f.tint()[2] + f.bright();
                if (f.vignette() > 0) {
                    double d = Math.hypot((x + 0.5) / size - 0.5, (y + 0.5) / size - 0.5) * 1.35;
                    double k = 1 - f.vignette() * smoothstep(0.3, 0.9, d);
                    r *= k;
                    g *= k;
                    b *= k;
                }
                out[y * size + x] = (int) Math.round(clamp(r) * 255) << 16 | (int) Math.round(clamp(g) * 255) << 8 | (int) Math.round(clamp(b) * 255);
            }
        }
        return out;
    }
}
