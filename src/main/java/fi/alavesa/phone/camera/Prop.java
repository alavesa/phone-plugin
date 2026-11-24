package fi.alavesa.phone.camera;

import fi.alavesa.phone.camera.assets.Models;
import java.util.List;

/**
 * A model somewhere in the world with an arbitrary affine transform: a block display, an item display, a text
 * display, an item in a hand, a skull on an armour stand. World point = {@code a} * model point + {@code b}; the ray
 * tracer turns rays into model space with the inverse, so scale and shear are fine.
 */
final class Prop {

    final List<Models.Placed> models;
    /** Model space to world: 3x3 and translation. */
    final double[][] a;
    final double[] b;
    /** World to model space. */
    final double[][] inv;
    /** Bounding sphere (world). */
    final double cx, cy, cz, radius;
    /** 0..1 light at the prop; glowing props are always lit. */
    final double light;
    final boolean glow;
    /** Multiplied into faces with a tint index (leather, potions ...); -1 = none. */
    final int tint;

    Prop(List<Models.Placed> models, double[][] a, double[] b, double light, boolean glow, int tint) {
        this.models = models;
        this.a = a;
        this.b = b;
        this.inv = invert(a);
        this.light = light;
        this.glow = glow;
        this.tint = tint;
        // the model's unit cube (models may reach a little outside it)
        double[] c = apply(a, 0.5, 0.5, 0.5);
        this.cx = c[0] + b[0];
        this.cy = c[1] + b[1];
        this.cz = c[2] + b[2];
        double r = 0;
        for (int i = 0; i < 3; i++) {
            double col = Math.sqrt(a[0][i] * a[0][i] + a[1][i] * a[1][i] + a[2][i] * a[2][i]);
            r += col * col;
        }
        this.radius = Math.sqrt(r) * 1.25 + 0.05;
    }

    static double[] apply(double[][] m, double x, double y, double z) {
        return new double[] {m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }

    static double[][] invert(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) det = 1e-12;
        double k = 1 / det;
        return new double[][] {
                {(e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k},
                {(f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k},
                {(d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k}};
    }

    /** A unit quaternion (x, y, z, w) as a 3x3 rotation. */
    static double[][] quaternion(double x, double y, double z, double w) {
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-9) return Models.rotX(0);
        x /= n;
        y /= n;
        z /= n;
        w /= n;
        return new double[][] {
                {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    }

    static double[][] scale(double x, double y, double z) {
        return new double[][] {{x, 0, 0}, {0, y, 0}, {0, 0, z}};
    }
}
