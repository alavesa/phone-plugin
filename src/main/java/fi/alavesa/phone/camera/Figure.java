package fi.alavesa.phone.camera;

import fi.alavesa.phone.camera.assets.Texture;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/**
 * A creature as the ray tracer sees one: boxes of the vanilla model (head, body, arms, legs), each with its own
 * position and rotation, textured from a skin or an entity texture.
 *
 * <p><b>Players</b> are posed from what the server knows at the moment of the picture: where they stand and look,
 * their {@link Pose} (standing, sneaking, swimming, gliding, sleeping), whether they sit in a vehicle, sprint, press a
 * movement key, hold an item up or just swung their arm. Walking is the vanilla limb swing driven by the player's age
 * in ticks, so consecutive video frames show the legs moving. The second skin layer (hat, jacket, sleeves, trousers)
 * is drawn as slightly bigger boxes whose transparent pixels let the ray through.
 *
 * <p><b>Mobs</b> with a simple vanilla model (zombies, skeletons, creepers, pigs, cows, sheep) are built the same
 * way from their texture; other mobs stay plain boxes.
 *
 * <p>Model space: feet at the origin, +y up, the creature faces +z, +x is its left. One texture pixel is 1/16 block
 * times {@code scale} (0.9375 for players and humanoids, as in vanilla).
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
        /** Box size in texture pixels and the top-left corner of its unwrapped texture. */
        int w, h, d, u, v;
        /** Second-layer box: a transparent pixel is no hit. */
        boolean overlay;
    }

    final List<Part> parts = new ArrayList<>(12);
    /** ARGB texture, {@code texW} x {@code texH}; uv coordinates are given for a {@code 64} x {@code layoutH} layout. */
    final int[] tex;
    final int texW, texH, layoutH;
    /** Bounding sphere for a quick reject. */
    final double cx, cy, cz, radius;
    /** 0..1 brightness where the creature stands. */
    final double light;
    private final double pixel;
    /** Where the right and left hand are (world) and the arm's axes, for held items. */
    double[] rightHand, leftHand;
    double[][] rightArm, leftArm;

    private Figure(int[] tex, int texW, int texH, int layoutH, double cx, double cy, double cz, double radius, double light, double pixel) {
        this.tex = tex;
        this.texW = texW;
        this.texH = texH;
        this.layoutH = layoutH;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radius = radius;
        this.light = light;
        this.pixel = pixel;
    }

    private static double lightAt(Entity e) {
        return e.getLocation().getBlock().getLightLevel() / 15.0;
    }

    /**
     * Main thread: reads the player's state and builds the posed model.
     *
     * @param swing 0..1 progress of an arm swing that is under way, or -1
     */
    static Figure of(Player p, int[] skin, boolean slim, double swing) {
        Location at = p.getLocation();
        Figure f = new Figure(skin, 64, 64, 64, at.getX(), at.getY() + 0.9, at.getZ(), 1.6, lightAt(p), PIXEL);

        Pose pose = p.getPose();
        boolean lying = pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SPIN_ATTACK;
        boolean sleeping = pose == Pose.SLEEPING;
        boolean sitting = p.isInsideVehicle();
        boolean sneaking = pose == Pose.SNEAKING && !sitting;

        // walking: vanilla swings the limbs with cos(limbSwing * 0.6662); the player's age stands in for limbSwing
        var input = p.getCurrentInput();
        boolean moving = input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
        double amount = sitting || sleeping ? 0 : p.isSprinting() ? 1.0 : moving ? 0.7 : 0.0;
        double walk = Math.cos(p.getTicksLived() * 0.6662) * 1.2 * amount;
        if (lying) walk = Math.cos(p.getTicksLived() * 0.33) * 0.5;   // slow kick while swimming / gliding

        // root: body yaw, plus lying down for swimming, gliding and sleeping
        double[][] root = rotY(Math.toRadians(-p.getBodyYaw()));
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
        double headYaw = lying || sleeping ? 0 : wrap(Math.toRadians(-(at.getYaw() - p.getBodyYaw())));
        double headPitch = lying ? Math.toRadians(-45) : sleeping ? 0 : Math.toRadians(at.getPitch());
        double armRight = p.isHandRaised() ? -1.3 : 0;      // eating, drawing a bow, blocking
        f.humanoid(root, origin, sneaking, sitting, walk, headYaw, headPitch, slim ? 3 : 4, armRight, 0, swing, true);
        return f;
    }

    /**
     * The vanilla humanoid: legs, body, arms, head (64x64 player layout), optionally with the second layer.
     *
     * @param armRight extra pitch of the right arm (negative = forward and up), {@code armLeft} the same for the left
     */
    private void humanoid(double[][] root, double[] origin, boolean sneaking, boolean sitting, double walk, double headYaw,
                          double headPitch, int armW, double armRight, double armLeft, double swing, boolean outer) {
        double lean = sneaking ? 0.5 : 0;                   // sneaking bends the body forward at the hips
        double drop = sneaking ? 2 : 0;                     // and lowers everything above the legs a little
        double back = sneaking ? -3 : 0;
        double[][] torso = rotX(lean);

        // legs: pivot at the hip (y = 12 px)
        double legAngle = sitting ? -1.4 : 0;
        double spread = sitting ? 0.3 : 0;
        double[][] rightLeg = mul(rotY(-spread), rotX(legAngle + walk));
        double[][] leftLeg = mul(rotY(spread), rotX(legAngle - walk));
        add(root, origin, rightLeg, -2, 12, back, 0, -6, 0, 4, 12, 4, 0, 16, 0, false);
        add(root, origin, leftLeg, 2, 12, back, 0, -6, 0, 4, 12, 4, 16, 48, 0, false);
        if (outer) {
            add(root, origin, rightLeg, -2, 12, back, 0, -6, 0, 4, 12, 4, 0, 32, 0.25, true);
            add(root, origin, leftLeg, 2, 12, back, 0, -6, 0, 4, 12, 4, 0, 48, 0.25, true);
        }

        // body: 12..24 px, rotating about the hips when sneaking
        add(root, origin, torso, 0, 12 - drop, back, 0, 6, 0, 8, 12, 4, 16, 16, 0, false);
        if (outer) add(root, origin, torso, 0, 12 - drop, back, 0, 6, 0, 8, 12, 4, 16, 32, 0.25, true);

        // arms: pivot at the shoulder, 2 px below the top of the body; they follow the lean
        double[] shoulder = apply(torso, 0, 10, 0);
        double sy = 12 - drop + shoulder[1], sz = back + shoulder[2];
        double armX = 4 + armW / 2.0;
        double armSit = sitting ? -0.6 : 0;
        double hit = swing < 0 ? 0 : Math.sin(Math.sqrt(swing) * Math.PI);
        double[][] right = mul(rotY(hit * 0.4), rotX(lean * 0.8 - walk * 0.8 + armSit + armRight - hit * 1.4));
        double[][] left = rotX(lean * 0.8 + walk * 0.8 + armSit + armLeft);
        Part r = add(root, origin, right, -armX, sy, sz, 0, -4, 0, armW, 12, 4, 40, 16, 0, false);
        Part l = add(root, origin, left, armX, sy, sz, 0, -4, 0, armW, 12, 4, 32, 48, 0, false);
        if (outer) {
            add(root, origin, right, -armX, sy, sz, 0, -4, 0, armW, 12, 4, 40, 32, 0.25, true);
            add(root, origin, left, armX, sy, sz, 0, -4, 0, armW, 12, 4, 48, 48, 0.25, true);
        }
        rightHand = hand(r);
        leftHand = hand(l);
        rightArm = axes(r);
        leftArm = axes(l);

        // head: pivot at the neck, turning with the view
        double[] neck = apply(torso, 0, 12, 0);
        double[][] head = mul(rotY(headYaw), rotX(headPitch));
        add(root, origin, head, 0, 12 - drop + neck[1], back + neck[2], 0, 4, 0, 8, 8, 8, 0, 0, 0, false);
        if (outer) add(root, origin, head, 0, 12 - drop + neck[1], back + neck[2], 0, 4, 0, 8, 8, 8, 32, 0, 0.5, true);
    }

    private static double[] hand(Part p) {
        return new double[] {p.centre[0] - p.ay[0] * p.hy, p.centre[1] - p.ay[1] * p.hy, p.centre[2] - p.ay[2] * p.hy};
    }

    private static double[][] axes(Part p) {
        return new double[][] {{p.ax[0], p.ay[0], p.az[0]}, {p.ax[1], p.ay[1], p.az[1]}, {p.ax[2], p.ay[2], p.az[2]}};
    }

    // ---- mobs -------------------------------------------------------------------------------------------------------

    /** The texture and model family of a mob, or null when it is drawn as a box. */
    static String textureOf(Entity e) {
        return switch (e.getType()) {
            case ZOMBIE -> "minecraft:entity/zombie/zombie";
            case HUSK -> "minecraft:entity/zombie/husk";
            case DROWNED -> "minecraft:entity/zombie/drowned";
            case SKELETON -> "minecraft:entity/skeleton/skeleton";
            case STRAY -> "minecraft:entity/skeleton/stray";
            case WITHER_SKELETON -> "minecraft:entity/skeleton/wither_skeleton";
            case BOGGED -> "minecraft:entity/skeleton/bogged";
            case CREEPER -> "minecraft:entity/creeper/creeper";
            case PIG -> "minecraft:entity/pig/pig_temperate";
            case COW -> "minecraft:entity/cow/cow_temperate";
            case SHEEP -> "minecraft:entity/sheep/sheep";
            default -> null;
        };
    }

    /** Main thread: a mob of a family {@link #textureOf} knows, built from its texture. */
    static Figure mob(LivingEntity e, Texture t) {
        Location at = e.getLocation();
        int layoutH = t.height * 64 / t.width;
        double walk = Math.cos(e.getTicksLived() * 0.6662) * 0.9 * Math.min(1, e.getVelocity().clone().setY(0).length() * 8);
        double[][] root = rotY(Math.toRadians(-e.getBodyYaw()));
        double[] origin = {at.getX(), at.getY(), at.getZ()};
        double headYaw = wrap(Math.toRadians(-(at.getYaw() - e.getBodyYaw())));
        double headPitch = Math.toRadians(at.getPitch());
        Figure f;
        switch (e.getType()) {
            case ZOMBIE, HUSK, DROWNED -> {
                f = new Figure(t.argb, t.width, t.height, layoutH, at.getX(), at.getY() + 0.9, at.getZ(), 1.6, lightAt(e), PIXEL);
                // arms held out in front, as zombies do
                f.humanoid(root, origin, false, false, walk, headYaw, headPitch, 4, -1.45, -1.45, -1, false);
            }
            case SKELETON, STRAY, WITHER_SKELETON, BOGGED -> {
                double scale = e.getType() == org.bukkit.entity.EntityType.WITHER_SKELETON ? 1.2 : 1.0;
                f = new Figure(t.argb, t.width, t.height, layoutH, at.getX(), at.getY() + 0.9 * scale, at.getZ(), 1.6 * scale, lightAt(e), PIXEL * scale);
                f.skeleton(root, origin, walk, headYaw, headPitch);
            }
            case CREEPER -> {
                f = new Figure(t.argb, t.width, t.height, layoutH, at.getX(), at.getY() + 0.8, at.getZ(), 1.3, lightAt(e), 1 / 16.0);
                f.add(root, origin, rotX(walk), -2, 6, 4, 0, -3, 0, 4, 6, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(-walk), 2, 6, 4, 0, -3, 0, 4, 6, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(-walk), -2, 6, -4, 0, -3, 0, 4, 6, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(walk), 2, 6, -4, 0, -3, 0, 4, 6, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(0), 0, 6, 0, 0, 6, 0, 8, 12, 4, 16, 16, 0, false);
                f.add(root, origin, mul(rotY(headYaw), rotX(headPitch)), 0, 18, 0, 0, 4, 0, 8, 8, 8, 0, 0, 0, false);
            }
            default -> {
                // pigs, cows, sheep: four legs, a body lying along z, a head in front
                boolean cow = e.getType() == org.bukkit.entity.EntityType.COW;
                boolean sheep = e.getType() == org.bukkit.entity.EntityType.SHEEP;
                int leg = cow || sheep ? 12 : 6;
                f = new Figure(t.argb, t.width, t.height, layoutH, at.getX(), at.getY() + 0.6, at.getZ(), 1.4, lightAt(e), 1 / 16.0);
                int lx = cow ? 4 : 3, front = cow ? 6 : 5, rear = cow ? -7 : -7;
                f.add(root, origin, rotX(walk), -lx, leg, rear, 0, -leg / 2.0, 0, 4, leg, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(-walk), lx, leg, rear, 0, -leg / 2.0, 0, 4, leg, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(-walk), -lx, leg, front, 0, -leg / 2.0, 0, 4, leg, 4, 0, 16, 0, false);
                f.add(root, origin, rotX(walk), lx, leg, front, 0, -leg / 2.0, 0, 4, leg, 4, 0, 16, 0, false);
                int bw = cow ? 12 : sheep ? 8 : 10, bl = cow ? 18 : 16, bd = cow ? 10 : sheep ? 6 : 8;
                int bu = cow ? 18 : 28, bv = cow ? 4 : 8;
                // the body box stands upright in the texture and is turned to lie along the animal
                f.add(root, origin, rotX(Math.PI / 2), 0, leg + bd / 2.0, 0, 0, 0, 0, bw, bl, bd, bu, bv, 0, false);
                int hw = sheep ? 6 : 8, hh = sheep ? 6 : 8, hd = cow ? 6 : 8;
                f.add(root, origin, mul(rotY(headYaw), rotX(headPitch)), 0, leg + bd - 1, bl / 2.0 - 1, 0, 0, hd / 2.0, hw, hh, hd, 0, 0, 0, false);
            }
        }
        return f;
    }

    /** Skeletons: humanoid with 2 px limbs, 64x32 layout. */
    private void skeleton(double[][] root, double[] origin, double walk, double headYaw, double headPitch) {
        add(root, origin, rotX(walk), -2, 12, 0, 0, -6, 0, 2, 12, 2, 0, 16, 0, false);
        add(root, origin, rotX(-walk), 2, 12, 0, 0, -6, 0, 2, 12, 2, 0, 16, 0, false);
        add(root, origin, rotX(0), 0, 12, 0, 0, 6, 0, 8, 12, 4, 16, 16, 0, false);
        Part r = add(root, origin, rotX(-walk * 0.8 - 0.1), -5, 22, 0, 0, -4, 0, 2, 12, 2, 40, 16, 0, false);
        Part l = add(root, origin, rotX(walk * 0.8 - 0.1), 5, 22, 0, 0, -4, 0, 2, 12, 2, 40, 16, 0, false);
        rightHand = hand(r);
        leftHand = hand(l);
        rightArm = axes(r);
        leftArm = axes(l);
        add(root, origin, mul(rotY(headYaw), rotX(headPitch)), 0, 24, 0, 0, 4, 0, 8, 8, 8, 0, 0, 0, false);
    }

    /**
     * Adds a box. {@code pivot} (px, model space) is where the part hangs; {@code local} rotates it there;
     * {@code off} (px) is the centre of the box relative to the pivot before that rotation. {@code inflate} grows
     * the box by that many pixels on every side (second skin layer).
     */
    private Part add(double[][] root, double[] origin, double[][] local, double pivotX, double pivotY, double pivotZ,
                     double offX, double offY, double offZ, int w, int h, int d, int u, int v, double inflate, boolean overlay) {
        double[][] m = mul(root, local);
        double[] c = apply(local, offX, offY, offZ);
        double[] world = apply(root, (pivotX + c[0]) * pixel, (pivotY + c[1]) * pixel, (pivotZ + c[2]) * pixel);
        Part part = new Part();
        for (int i = 0; i < 3; i++) {
            part.centre[i] = origin[i] + world[i];
            part.ax[i] = m[i][0];
            part.ay[i] = m[i][1];
            part.az[i] = m[i][2];
        }
        part.hx = (w / 2.0 + inflate) * pixel;
        part.hy = (h / 2.0 + inflate) * pixel;
        part.hz = (d / 2.0 + inflate) * pixel;
        part.w = w;
        part.h = h;
        part.d = d;
        part.u = u;
        part.v = v;
        part.overlay = overlay;
        parts.add(part);
        return part;
    }

    /**
     * The texture colour (ARGB) where a ray enters local point (x, y, z) of a part, using the standard box unwrap:
     * side, front, side, back in a row, top and bottom above the front. The model faces +z, so +z is the front.
     */
    int texel(Part p, int axis, boolean positive, double x, double y, double z) {
        // position inside the box in texture pixels, 0..size
        double px = (x + p.hx) / (2 * p.hx) * p.w, py = (p.hy - y) / (2 * p.hy) * p.h, pz = (z + p.hz) / (2 * p.hz) * p.d;
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
        int iu = Math.clamp((int) (u * texW / 64.0), 0, texW - 1);
        int iv = Math.clamp((int) (v * texH / (double) layoutH), 0, texH - 1);
        return tex[iv * texW + iu];
    }

    private static double wrap(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return Math.clamp(angle, -1.4, 1.4);        // a head does not turn further than the shoulders
    }

    static double[][] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    static double[][] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        }
        return out;
    }

    static double[] apply(double[][] m, double x, double y, double z) {
        return new double[] {m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }
}
