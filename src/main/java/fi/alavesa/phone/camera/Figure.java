package fi.alavesa.phone.camera;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/**
 * A player as the ray tracer sees one: the six boxes of the vanilla player model (head, body, arms, legs), each
 * with its own position and rotation, textured from the player's skin.
 *
 * <p>The pose is rebuilt from what the server knows at the moment of the picture: where the player stands and
 * looks, their {@link Pose} (standing, sneaking, swimming, gliding, sleeping), whether they sit in a vehicle,
 * sprint, press a movement key or hold an item up. Walking is the vanilla limb swing driven by the player's age in
 * ticks, so consecutive video frames show the legs moving. What the server does not know is not drawn: the exact
 * swing phase the client shows, attack swings, held items and armour.
 *
 * <p>Model space: feet at the origin, +y up, the player faces +z, +x is the player's left. One skin pixel is
 * 1/16 block times 0.9375 (a player is 1.875 blocks of model for a 1.8 block hitbox, as in vanilla).
 */
final class Figure {

    static final double PIXEL = 0.9375 / 16.0;

    /** One box of the model in world space. */
    static final class Part {
        /** Centre of the box. */
        final double[] centre = new double[3];
        /** Local x, y and z axis of the box as world vectors (unit length). */
        final double[] ax = new double[3], ay = new double[3], az = new double[3];
        /** Half sizes along the local axes, in blocks. */
        double hx, hy, hz;
        /** Box size in skin pixels and the top-left corner of its unwrapped texture in the skin. */
        int w, h, d, u, v;
    }

    final List<Part> parts = new ArrayList<>(6);
    /** 64x64 ARGB. */
    final int[] skin;
    /** Bounding sphere for a quick reject. */
    final double cx, cy, cz, radius;
    /** 0..1 brightness where the player stands. */
    final double light;

    private Figure(int[] skin, double cx, double cy, double cz, double light) {
        this.skin = skin;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radius = 1.6;
        this.light = light;
    }

    /** Main thread: reads the player's state and builds the posed model. */
    static Figure of(Player p, int[] skin, boolean slim) {
        Location at = p.getLocation();
        double light = at.getBlock().getLightLevel() / 15.0;
        Figure f = new Figure(skin, at.getX(), at.getY() + 0.9, at.getZ(), light);

        Pose pose = p.getPose();
        boolean lying = pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SPIN_ATTACK;
        boolean sleeping = pose == Pose.SLEEPING;
        boolean sitting = p.isInsideVehicle();
        boolean sneaking = pose == Pose.SNEAKING && !sitting;

        // walking: vanilla swings the limbs with cos(limbSwing * 0.6662); the player's age stands in for limbSwing
        var input = p.getCurrentInput();
        boolean moving = input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
        double amount = sitting || sleeping ? 0 : p.isSprinting() ? 1.0 : moving ? 0.7 : 0.0;
        double swing = Math.cos(p.getTicksLived() * 0.6662) * 1.2 * amount;
        if (lying) swing = Math.cos(p.getTicksLived() * 0.33) * 0.5;   // slow kick while swimming / gliding

        // root: body yaw, plus lying down for swimming, gliding and sleeping
        double yaw = Math.toRadians(-p.getBodyYaw());
        double[][] root = rotY(yaw);
        double rootY = 0;
        if (lying) {
            // face down along the view direction; the looking pitch tilts the whole body
            root = mul(root, rotX(Math.toRadians(90 + at.getPitch())));
            rootY = 0.3;
        } else if (sleeping) {
            root = mul(root, rotX(Math.toRadians(-90)));
            rootY = 0.2;
        } else if (sitting) {
            rootY = -10 * PIXEL;
        }
        double[] origin = {at.getX(), at.getY() + rootY, at.getZ()};

        double lean = sneaking ? 0.5 : 0;                   // sneaking bends the body forward at the hips
        double drop = sneaking ? 2 : 0;                     // and lowers everything above the legs a little
        double[][] torso = rotX(lean);

        // legs: pivot at the hip (y = 12 px)
        double legAngle = sitting ? -1.4 : 0;
        double spread = sitting ? 0.3 : 0;
        f.add(root, origin, mul(rotY(-spread), rotX(legAngle + swing)), -2, 12, sneaking ? -3 : 0, 0, -6, 0, 4, 12, 4, 0, 16);
        f.add(root, origin, mul(rotY(spread), rotX(legAngle - swing)), 2, 12, sneaking ? -3 : 0, 0, -6, 0, 4, 12, 4, 16, 48);

        // body: 12..24 px, rotating about the hips when sneaking
        f.add(root, origin, torso, 0, 12 - drop, sneaking ? -3 : 0, 0, 6, 0, 8, 12, 4, 16, 16);

        // arms: pivot at the shoulder, 2 px below the top of the body; they follow the lean
        double[] shoulder = apply(torso, 0, 10, 0);
        double sy = 12 - drop + shoulder[1], sz = (sneaking ? -3 : 0) + shoulder[2];
        int armW = slim ? 3 : 4;
        double armX = 4 + armW / 2.0;
        double raised = p.isHandRaised() ? -1.3 : 0;        // eating, drawing a bow, blocking
        double armSit = sitting ? -0.6 : 0;
        f.add(root, origin, rotX(lean * 0.8 - swing * 0.8 + armSit + raised), -armX, sy, sz, 0, -4, 0, armW, 12, 4, 40, 16);
        f.add(root, origin, rotX(lean * 0.8 + swing * 0.8 + armSit), armX, sy, sz, 0, -4, 0, armW, 12, 4, 32, 48);

        // head: pivot at the neck, turning with the view
        double[] neck = apply(torso, 0, 12, 0);
        double headYaw = lying || sleeping ? 0 : Math.toRadians(-(at.getYaw() - p.getBodyYaw()));
        double headPitch = lying ? Math.toRadians(-45) : sleeping ? 0 : Math.toRadians(at.getPitch());
        f.add(root, origin, mul(rotY(wrap(headYaw)), rotX(headPitch)), 0, 12 - drop + neck[1], (sneaking ? -3 : 0) + neck[2],
                0, 4, 0, 8, 8, 8, 0, 0);
        return f;
    }

    /**
     * Adds a box. {@code pivot} (px, model space) is where the part hangs; {@code local} rotates it there;
     * {@code off} (px) is the centre of the box relative to the pivot before that rotation.
     */
    private void add(double[][] root, double[] origin, double[][] local, double pivotX, double pivotY, double pivotZ,
                     double offX, double offY, double offZ, int w, int h, int d, int u, int v) {
        double[][] m = mul(root, local);
        double[] c = apply(local, offX, offY, offZ);
        double[] world = apply(root, (pivotX + c[0]) * PIXEL, (pivotY + c[1]) * PIXEL, (pivotZ + c[2]) * PIXEL);
        Part part = new Part();
        for (int i = 0; i < 3; i++) {
            part.centre[i] = origin[i] + world[i];
            part.ax[i] = m[i][0];
            part.ay[i] = m[i][1];
            part.az[i] = m[i][2];
        }
        part.hx = w * PIXEL / 2;
        part.hy = h * PIXEL / 2;
        part.hz = d * PIXEL / 2;
        part.w = w;
        part.h = h;
        part.d = d;
        part.u = u;
        part.v = v;
        parts.add(part);
    }

    /**
     * The skin colour where a ray leaves local point (x, y, z) on a part, using the standard box unwrap: side,
     * front, side, back in a row, top and bottom above the front. The model faces +z, so +z is the front face.
     */
    static int texel(int[] skin, Part p, int axis, boolean positive, double x, double y, double z) {
        // position inside the box in pixels, 0..size
        double px = (x + p.hx) / PIXEL, py = (p.hy - y) / PIXEL, pz = (z + p.hz) / PIXEL;
        double u, v;
        if (axis == 1) {
            // top (+y), then bottom to its right; the edge toward the front face is at the bottom of the patch
            u = p.u + p.d + (positive ? 0 : p.w) + px;
            v = p.v + pz;
        } else {
            v = p.v + p.d + py;
            // seen from outside, each face runs left to right in the direction the row continues:
            // right side (back -> front), front (right -> left), left side (front -> back), back (left -> right)
            if (axis == 2) u = positive ? p.u + p.d + px : p.u + p.d + p.w + p.d + (p.w - px);
            else u = positive ? p.u + p.d + p.w + (p.d - pz) : p.u + pz;
        }
        int iu = Math.clamp((int) u, 0, 63), iv = Math.clamp((int) v, 0, 63);
        return skin[iv * 64 + iu] & 0xFFFFFF;
    }

    private static double wrap(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return Math.clamp(angle, -1.4, 1.4);        // a head does not turn further than the shoulders
    }

    private static double[][] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        }
        return out;
    }

    private static double[] apply(double[][] m, double x, double y, double z) {
        return new double[] {m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }
}
