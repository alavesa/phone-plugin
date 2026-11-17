package fi.alavesa.phone.camera;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Renders a {@link Scene} into a 128x128 picture by casting one ray per pixel through the chunk snapshots.
 * Pure computation on immutable data: safe on any thread, never touches the live world.
 *
 * <p>Per ray: a voxel walk (Amanatides and Woo) from the eye along the view direction until a solid block, an
 * entity box, the edge of the captured chunks or the maximum distance. Colours are the blocks' map colours, shaded
 * by the face that was hit, by the stored sky/block light next to it and by the time of day; water is looked
 * through with a blue tint that deepens with the distance travelled in it; small non-solid blocks (flowers, grass,
 * torches) show as a small column in the middle of their block; the far distance fades into the sky colour.
 *
 * <p>Cost: 16 384 rays, each visiting at most about 1.7 blocks per block of distance. At the default 96 blocks
 * that is under 3 million block lookups in the worst case (open view to the horizon), typically well below.
 */
final class RayTracer {

    static final int SIZE = 128;

    private static final int WATER_RGB = 0x3F76E4;
    private static final double[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    private final Scene s;
    private final Map<BlockData, Integer> colours = new HashMap<>();
    private final double skyFactor;         // 0.12 (night) .. 1 (day): how bright sky light is
    private final double sunX, sunY;        // sun direction (the sun moves in the x/y plane)
    private ChunkSnapshot lastChunk;
    private long lastKey = Long.MIN_VALUE;

    private RayTracer(Scene scene) {
        this.s = scene;
        double angle = (scene.time % 24000L) / 24000.0 * Math.PI * 2;     // 0 = sunrise in the east (+x), 6000 = noon
        sunX = Math.cos(angle);
        sunY = Math.sin(angle);
        double day = Math.clamp(0.5 + sunY * 2.5, 0.12, 1.0);
        skyFactor = scene.environment == World.Environment.NORMAL ? day * (scene.storm ? 0.75 : 1.0) : 0.0;
    }

    /** @return SIZE*SIZE pixels, 0xRRGGBB, row by row from the top-left */
    static int[] render(Scene scene) {
        RayTracer tracer = new RayTracer(scene);
        int[] out = new int[SIZE * SIZE];
        for (int py = 0; py < SIZE; py++) {
            double v = (1.0 - (py + 0.5) / SIZE * 2.0) * scene.tanHalfFov;
            for (int px = 0; px < SIZE; px++) {
                double u = ((px + 0.5) / SIZE * 2.0 - 1.0) * scene.tanHalfFov;
                double dx = scene.forward[0] + scene.right[0] * u + scene.up[0] * v;
                double dy = scene.forward[1] + scene.right[1] * u + scene.up[1] * v;
                double dz = scene.forward[2] + scene.right[2] * u + scene.up[2] * v;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int rgb = tracer.trace(dx / len, dy / len, dz / len);
                if (scene.dither) rgb = dither(rgb, px, py);
                out[py * SIZE + px] = rgb;
            }
        }
        return out;
    }

    /** Ordered dithering: map items only have about 240 colours, a little noise hides the banding in gradients. */
    private static int dither(int rgb, int x, int y) {
        int d = (int) Math.round((BAYER[(y & 3) * 4 + (x & 3)] / 15.0 - 0.5) * 14.0);
        int r = Math.clamp(((rgb >> 16) & 255) + d, 0, 255);
        int g = Math.clamp(((rgb >> 8) & 255) + d, 0, 255);
        int b = Math.clamp((rgb & 255) + d, 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private ChunkSnapshot chunk(int blockX, int blockZ) {
        long key = Scene.chunkKey(blockX >> 4, blockZ >> 4);
        if (key != lastKey) {
            lastKey = key;
            lastChunk = s.chunks.get(key);
        }
        return lastChunk;
    }

    private int colour(BlockData data) {
        Integer c = colours.get(data);
        if (c == null) {
            c = data.getMapColor().asRGB();
            colours.put(data, c);
        }
        return c;
    }

    private int trace(double dx, double dy, double dz) {
        // nearest entity along the ray: blocks behind it are never looked at
        double limit = s.maxDistance;
        Scene.Box hitBox = null;
        for (Scene.Box box : s.entities) {
            double t = intersect(box, dx, dy, dz);
            if (t >= 0 && t < limit) {
                limit = t;
                hitBox = box;
            }
        }

        int x = (int) Math.floor(s.eyeX), y = (int) Math.floor(s.eyeY), z = (int) Math.floor(s.eyeZ);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double nextX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - s.eyeX : s.eyeX - x) * deltaX;
        double nextY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - s.eyeY : s.eyeY - y) * deltaY;
        double nextZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - s.eyeZ : s.eyeZ - z) * deltaZ;

        double t = 0;               // distance at which the current voxel was entered
        int axis = -1;              // axis of the step that entered it (-1 = the eye's own voxel)
        double waterDepth = 0;
        double waterEnter = -1;
        int overlay = -1;           // colour of the first small non-solid block passed
        boolean reachedEdge = false;

        while (t <= limit) {
            if (y >= s.maxY) {
                if (dy >= 0) break;
            } else if (y < s.minY) {
                break;
            } else {
                ChunkSnapshot snap = chunk(x, z);
                if (snap == null) {
                    reachedEdge = true;         // not captured (unloaded or beyond the cap): fades into the distance
                    break;
                }
                Material type = snap.getBlockType(x & 15, y, z & 15);
                boolean water = type == Material.WATER;
                if (water && waterEnter < 0) waterEnter = t;
                if (!water && waterEnter >= 0) {
                    waterDepth += t - waterEnter;
                    waterEnter = -1;
                }
                if (!water && !type.isAir()) {
                    int rgb = colour(snap.getBlockData(x & 15, y, z & 15));
                    if (rgb != 0) {         // 0 = map colour NONE: glass, barriers ... are looked through
                        boolean glowing = type == Material.LAVA || type == Material.FIRE;
                        if (type.isSolid() || glowing) {
                            double shade = axis < 0 ? 0.8 : axis == 1 ? (stepY < 0 ? 1.0 : 0.6) : axis == 2 ? 0.88 : 0.76;
                            double light = glowing ? 1.0 : lightBefore(x, y, z, axis, stepX, stepY, stepZ);
                            int c = scale(rgb, glowing ? 1.0 : shade * light);
                            if (overlay >= 0) c = mix(c, scale(overlay, light), 0.8);
                            return finish(c, t, waterDepth, dx, dy, dz);
                        }
                        // a flower or torch fills only the middle of its block: count it when the ray passes there
                        if (overlay < 0) {
                            double mid = (t + Math.min(nextX, Math.min(nextY, nextZ))) / 2;
                            double mx = s.eyeX + dx * mid - x - 0.5, my = s.eyeY + dy * mid - y, mz = s.eyeZ + dz * mid - z - 0.5;
                            if (Math.abs(mx) < 0.3 && Math.abs(mz) < 0.3 && my < 0.8) overlay = rgb;
                        }
                    }
                }
            }
            // step into the next voxel
            if (nextX <= nextY && nextX <= nextZ) {
                t = nextX;
                nextX += deltaX;
                x += stepX;
                axis = 0;
            } else if (nextY <= nextZ) {
                t = nextY;
                nextY += deltaY;
                y += stepY;
                axis = 1;
            } else {
                t = nextZ;
                nextZ += deltaZ;
                z += stepZ;
                axis = 2;
            }
        }

        if (waterEnter >= 0) waterDepth += Math.min(t, limit) - waterEnter;
        if (hitBox != null && !reachedEdge && t > limit) {
            double hitY = s.eyeY + dy * limit;
            int c = scale(hitY >= hitBox.headFromY() ? hitBox.headRgb() : hitBox.bodyRgb(), 0.55 + 0.45 * ambient());
            return finish(c, limit, waterDepth, dx, dy, dz);
        }
        int sky = sky(dx, dy, dz);
        if (waterDepth > 0) return mix(sky, scale(WATER_RGB, 0.35 + 0.65 * ambient()), 1 - Math.exp(-0.3 * waterDepth));
        return sky;
    }

    /** Water tint and distance fog over a surface colour. */
    private int finish(int rgb, double distance, double waterDepth, double dx, double dy, double dz) {
        if (waterDepth > 0) {
            rgb = mix(rgb, scale(WATER_RGB, 0.35 + 0.65 * ambient()), Math.max(0.35, 1 - Math.exp(-0.3 * waterDepth)));
        }
        double f = distance / s.maxDistance;
        return mix(rgb, sky(dx, 0.02, dz), Math.min(0.85, f * f * 0.85));
    }

    private double ambient() {
        return s.environment == World.Environment.NORMAL ? skyFactor : 0.5;
    }

    /** Brightness 0.18..1 from the light stored in the voxel the ray came from (the air in front of the face). */
    private double lightBefore(int x, int y, int z, int axis, int stepX, int stepY, int stepZ) {
        if (axis == 0) x -= stepX;
        else if (axis == 1) y -= stepY;
        else if (axis == 2) z -= stepZ;
        double level;
        if (y >= s.maxY) {
            level = skyFactor;
        } else if (y < s.minY) {
            level = 0;
        } else {
            ChunkSnapshot snap = chunk(x, z);
            if (snap == null) {
                level = skyFactor;
            } else {
                level = Math.max(snap.getBlockEmittedLight(x & 15, y, z & 15) / 15.0, snap.getBlockSkyLight(x & 15, y, z & 15) / 15.0 * skyFactor);
            }
        }
        double floor = s.environment == World.Environment.NORMAL ? 0.18 : 0.4;   // nether/end have ambient light
        return floor + (1 - floor) * level;
    }

    private int sky(double dx, double dy, double dz) {
        if (s.environment == World.Environment.NETHER) return 0x2A0A0A;
        if (s.environment == World.Environment.THE_END) return 0x0D0A14;
        double h = Math.pow(Math.clamp(dy * 1.5, 0.0, 1.0), 0.6);
        int c = mix(0xB4D2FF, 0x5B8DEF, h);
        if (s.storm) c = mix(c, 0x7A8088, 0.7);
        c = scale(c, skyFactor);
        if (!s.storm && dy > -0.05) {
            double sun = dx * sunX + dy * sunY;
            if (sun > 0.9985) return 0xFFF5C0;
            if (sun < -0.9988) return 0xDDE4F0;         // the moon is opposite the sun
            if (skyFactor < 0.3 && dy > 0.05) {
                // a few stars: a hash of the direction, quantised so each star is one pixel-ish
                long hx = Math.round(dx * 300), hy = Math.round(dy * 300), hz = Math.round(dz * 300);
                long hash = (hx * 73856093L) ^ (hy * 19349663L) ^ (hz * 83492791L);
                if (Math.floorMod(hash, 331L) == 0) return 0xD8DCE8;
            }
        }
        return c;
    }

    /** Distance along the ray to the box, or -1. The eye is at the scene's eye position. */
    private double intersect(Scene.Box b, double dx, double dy, double dz) {
        double tMin = 0, tMax = s.maxDistance;
        double[] o = {s.eyeX, s.eyeY, s.eyeZ};
        double[] d = {dx, dy, dz};
        double[] lo = {b.minX(), b.minY(), b.minZ()};
        double[] hi = {b.maxX(), b.maxY(), b.maxZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1;
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    private static int scale(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 255) * f);
        int b = (int) Math.min(255, (rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
