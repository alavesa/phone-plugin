package fi.alavesa.phone.camera.assets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/**
 * A texture as ARGB pixels. Animated textures (a strip of square frames) keep their first frame. {@code average} is
 * the mean colour of the opaque pixels, used for far-away surfaces where one photo pixel covers many texels.
 */
public final class Texture {

    public final int width;
    public final int height;
    public final int[] argb;
    public final int average;
    /** Some pixels are partly transparent (stained glass, ice, water): the ray continues through them. */
    public final boolean translucent;

    public Texture(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
        long r = 0, g = 0, b = 0, n = 0;
        boolean partial = false;
        for (int c : argb) {
            int a = c >>> 24;
            if (a > 16 && a < 235) partial = true;
            if (a < 128) continue;
            r += (c >> 16) & 255;
            g += (c >> 8) & 255;
            b += c & 255;
            n++;
        }
        this.average = n == 0 ? 0 : (0xFF << 24) | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        this.translucent = partial;
    }

    static Texture decode(byte[] png) {
        if (png == null) return null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) return null;
            int w = image.getWidth();
            int h = image.getHeight();
            if (h > w && h % w == 0) h = w;          // animation strip: first frame
            return new Texture(w, h, image.getRGB(0, 0, w, h, null, 0, w));
        } catch (Exception e) {
            return null;
        }
    }

    /** ARGB at texture coordinates u, v in 0..1 (clamped). */
    public int sample(double u, double v) {
        int x = Math.clamp((int) (u * width), 0, width - 1);
        int y = Math.clamp((int) (v * height), 0, height - 1);
        return argb[y * width + x];
    }
}
