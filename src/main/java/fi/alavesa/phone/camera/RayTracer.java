package fi.alavesa.phone.camera;

import fi.alavesa.phone.camera.assets.Models;
import fi.alavesa.phone.camera.assets.Texture;
import fi.alavesa.phone.camera.assets.Tints;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/**
 * Renders a {@link Scene} into a 128x128 picture by casting one ray per pixel through the chunk snapshots.
 * Pure computation on immutable data: safe on any thread, never touches the live world.
 *
 * <p><b>Blocks.</b> With the client assets loaded ({@link Scene#models}), every block is drawn with its real model:
 * the ray is tested against the boxes of the block-state model (stairs, slabs, fences, flowers, rotated elements)
 * and the face it enters picks the texel of the real texture, tinted like vanilla (grass, leaves, water by biome).
 * Transparent texels let the ray through (leaves, flowers, glass panes); partly transparent ones (stained glass, ice,
 * water) are blended front to back. Faces are shaded like vanilla's directional shading and by the stored sky and
 * block light next to them; far surfaces use the texture's mean colour, so distant ground does not turn to noise.
 * Without the assets the old renderer runs: map colours, full cubes.
 *
 * <p><b>Creatures and props.</b> Players and simple mobs are {@link Figure}s (textured boxes, second skin layer);
 * display entities, held items and skulls are {@link Prop}s (models with an affine transform); other entities are
 * plain boxes. The nearest of them limits the voxel walk.
 *
 * <p>Cost: 16 384 rays, each visiting at most about 1.7 blocks per block of distance, each non-air block tested
 * against a handful of boxes. At the default 96 blocks that is a few million box tests in the worst case.
 */
final class RayTracer {

    static final int SIZE = 128;

    private static final double[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final double EPS = 1e-6;

    private final Scene s;
    private final Models models;
    private final Tints tints;
    private final Map<BlockData, Integer> colours = new HashMap<>();
    private final Map<BlockData, List<Models.Placed>> placed = new HashMap<>();
    private final double skyFactor;         // 0.12 (night) .. 1 (day): how bright sky light is
    private final double sunX, sunY;        // sun direction (the sun moves in the x/y plane)
    private final double pixelAngle;        // angle one photo pixel covers
    private final Texture waterTex, lavaTex;
    private ChunkSnapshot lastChunk;
    private long lastKey = Long.MIN_VALUE;

    // front-to-back compositing of the current ray
    private double accR, accG, accB, trans;

    // the last successful model hit
    private double hitT;
    private int hitArgb;
    private double hitNx, hitNy, hitNz;
    private boolean hitShade;
    private boolean hitTranslucent;

    // tint of the block being tested (computed when a tinted face is hit)
    private BlockData tintData;
    private int tintX, tintY, tintZ;
    private int tintValue;

    private RayTracer(Scene scene) {
        this.s = scene;
        this.models = scene.models;
        this.tints = scene.tints;
        double angle = (scene.time % 24000L) / 24000.0 * Math.PI * 2;     // 0 = sunrise in the east (+x), 6000 = noon
        sunX = Math.cos(angle);
        sunY = Math.sin(angle);
        double day = Math.clamp(0.5 + sunY * 2.5, 0.12, 1.0);
        skyFactor = scene.environment == World.Environment.NORMAL ? day * (scene.storm ? 0.75 : 1.0) : 0.0;
        pixelAngle = 2 * scene.tanHalfFov / SIZE;
        waterTex = models == null ? null : models.texture("minecraft:block/water_still");
        lavaTex = models == null ? null : models.texture("minecraft:block/lava_still");
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

    private List<Models.Placed> placed(BlockData data) {
        List<Models.Placed> p = placed.get(data);
        if (p == null) {
            p = models.block(data.getAsString());
            placed.put(data, p);
        }
        return p;
    }

    // ---- compositing ------------------------------------------------------------------------------------------------

    private void layer(int rgb, double alpha) {
        double a = alpha * trans;
        accR += a * ((rgb >> 16) & 255);
        accG += a * ((rgb >> 8) & 255);
        accB += a * (rgb & 255);
        trans *= 1 - alpha;
    }

    private int result(int background) {
        layer(background, 1);
        return (Math.clamp((int) accR, 0, 255) << 16) | (Math.clamp((int) accG, 0, 255) << 8) | Math.clamp((int) accB, 0, 255);
    }

    /** Distance fog over a surface colour. */
    private int fog(int rgb, double distance, double dx, double dz) {
        double f = distance / s.maxDistance;
        return mix(rgb, sky(dx, 0.02, dz), Math.min(0.85, f * f * 0.85));
    }

    // ---- one ray ----------------------------------------------------------------------------------------------------

    private int trace(double dx, double dy, double dz) {
        accR = accG = accB = 0;
        trans = 1;
        // nearest creature or prop along the ray: blocks behind it are never looked at
        double limit = s.maxDistance;
        int entityRgb = -1;
        for (Scene.Box box : s.entities) {
            double t = intersect(box, dx, dy, dz);
            if (t >= 0 && t < limit) {
                limit = t;
                entityRgb = scale(s.eyeY + dy * t >= box.headFromY() ? box.headRgb() : box.bodyRgb(), 0.55 + 0.45 * ambient());
            }
        }
        for (Figure figure : s.figures) {
            if (!nearSphere(figure.cx, figure.cy, figure.cz, figure.radius, dx, dy, dz, limit)) continue;
            for (Figure.Part part : figure.parts) {
                double t = intersect(figure, part, dx, dy, dz, limit);
                if (t >= 0) {
                    limit = t;
                    entityRgb = figureRgb;
                }
            }
        }
        for (Prop prop : s.props) {
            if (!nearSphere(prop.cx, prop.cy, prop.cz, prop.radius, dx, dy, dz, limit)) continue;
            int rgb = intersect(prop, dx, dy, dz, limit);
            if (rgb >= 0) {
                limit = hitT;
                entityRgb = rgb;
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
        boolean inWater = false;
        int overlay = -1;           // flat renderer: colour of the first small non-solid block passed
        boolean reachedEdge = false;

        while (t <= limit && trans > 0.02) {
            double exit = Math.min(nextX, Math.min(nextY, nextZ));
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
                BlockData data = snap.getBlockData(x & 15, y, z & 15);
                Material type = data.getMaterial();
                boolean water = type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.SEAGRASS
                        || type == Material.TALL_SEAGRASS || type == Material.KELP || type == Material.KELP_PLANT
                        || (data instanceof Waterlogged w && w.isWaterlogged());
                if (water) {
                    int waterColour = models == null ? Tints.WATER : Tints.water(snap.getBiome(x & 15, y, z & 15));
                    if (!inWater && waterTex != null) {
                        // the surface: the water texture, tinted, half see-through
                        int surface = surfaceTexel(waterTex, axis, x, y, z, t, dx, dy, dz);
                        layer(fog(scale(multiply(surface, waterColour), 0.55 + 0.45 * light(x, y, z, x, y + 1, z)), t, dx, dz), 0.45);
                    }
                    inWater = true;
                    // the water itself: a little more blue with every block
                    double a = 1 - Math.exp(-0.16 * (Math.min(exit, limit) - t));
                    layer(scale(waterColour, 0.25 + 0.5 * ambient()), a);
                } else {
                    inWater = false;
                }
                if (!type.isAir() && type != Material.WATER && type != Material.BUBBLE_COLUMN) {
                    if (models == null) {
                        int c = flat(snap, data, type, x, y, z, t, axis, stepX, stepY, stepZ, overlay, nextX, nextY, nextZ, dx, dy, dz);
                        if (c >= 0) {
                            layer(fog(c, t, dx, dz), 1);
                            return result(0);
                        }
                        if (c == -2 && overlay < 0) overlay = colour(data);
                    } else if (type == Material.LAVA) {
                        int c = lavaTex == null ? 0xCF5A10 : surfaceTexel(lavaTex, axis, x, y, z, t, dx, dy, dz) & 0xFFFFFF;
                        layer(fog(c, t, dx, dz), 1);
                        return result(0);
                    } else if (blockHit(snap, data, x, y, z, t, exit, dx, dy, dz)) {
                        layer(fog(hitArgb & 0xFFFFFF, hitT, dx, dz), hitTranslucent ? (hitArgb >>> 24) / 255.0 : 1);
                        if (!hitTranslucent) return result(0);
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

        if (entityRgb >= 0 && !reachedEdge && t > limit) {
            layer(fog(entityRgb, limit, dx, dz), 1);
            return result(0);
        }
        return result(sky(dx, dy, dz));
    }

    /** The texel of a full-block texture where the ray enters voxel (x, y, z) through the face of {@code axis}. */
    private int surfaceTexel(Texture tex, int axis, int x, int y, int z, double t, double dx, double dy, double dz) {
        double hx = s.eyeX + dx * t - x, hy = s.eyeY + dy * t - y, hz = s.eyeZ + dz * t - z;
        double u, v;
        if (axis == 1 || axis < 0) {
            u = hx;
            v = hz;
        } else if (axis == 0) {
            u = hz;
            v = 1 - hy;
        } else {
            u = hx;
            v = 1 - hy;
        }
        return minify(tex, tex.sample(frac(u), frac(v)), t);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** Far away one photo pixel covers many texels: fade toward the texture's mean colour. */
    private int minify(Texture tex, int argb, double distance) {
        double texels = distance * pixelAngle * tex.width;
        if (texels < 0.8 || tex.average == 0) return argb;
        double w = Math.min(1, (texels - 0.8) / 1.5);
        int a = argb >>> 24;
        return (a << 24) | mix(argb & 0xFFFFFF, tex.average & 0xFFFFFF, w);
    }

    // ---- block models -----------------------------------------------------------------------------------------------

    /** Tests the ray against the model of the block in voxel (x, y, z) between t0 and t1; fills the hit fields. */
    private boolean blockHit(ChunkSnapshot snap, BlockData data, int x, int y, int z, double t0, double t1, double dx, double dy, double dz) {
        List<Models.Placed> list = placed(data);
        if (list.isEmpty()) {
            // a block without a model of its own (chests, signs, beds, heads: drawn by the client in code): map colour
            int rgb = colour(data);
            if (rgb == 0 || !data.getMaterial().isSolid()) return false;
            hitT = t0;
            hitTranslucent = false;
            hitArgb = 0xFF000000 | scale(rgb, 0.85 * light(x, y, z, x, y + 1, z));
            return true;
        }
        tintData = data;
        tintX = x;
        tintY = y;
        tintZ = z;
        tintValue = -1;
        double[] o = {s.eyeX - x, s.eyeY - y, s.eyeZ - z};
        double[] d = {dx, dy, dz};
        if (!modelHit(list, o, d, Math.max(0, t0 - 1e-4), t1 + 1e-4, -1)) return false;
        // light from the space in front of the face (and the block's own, for slabs, plants ...)
        int nx = (int) Math.round(hitNx), ny = (int) Math.round(hitNy), nz = (int) Math.round(hitNz);
        double light = light(x, y, z, x + nx, y + ny, z + nz);
        double shade = hitShade ? 0.6 * hitNx * hitNx + 0.8 * hitNz * hitNz + (hitNy > 0 ? 1.0 : 0.5) * hitNy * hitNy : 1.0;
        int a = hitArgb >>> 24;
        hitArgb = (a << 24) | scale(hitArgb & 0xFFFFFF, shade * light);
        return true;
    }

    private int blockTint() {
        if (tintValue < 0) {
            if (tints == null || tintData == null) {
                tintValue = 0x91BD59;
            } else {
                ChunkSnapshot snap = chunk(tintX, tintZ);
                Biome biome = snap == null ? null : snap.getBiome(tintX & 15, tintY, tintZ & 15);
                double temperature = snap == null ? 0.8 : snap.getRawBiomeTemperature(tintX & 15, tintY, tintZ & 15);
                tintValue = tints.block(tintData, biome, temperature);
            }
        }
        return tintValue;
    }

    /**
     * The nearest visible face of {@code list} along o + t*d (model space, 0..1 = the block) with tMin <= t < tMax.
     * Fills hitT, hitArgb (texel with tint, alpha), the model-space normal (hitN*) and hitShade.
     *
     * @param fixedTint tint for tinted faces, or -1 to ask {@link #blockTint()}
     */
    private boolean modelHit(List<Models.Placed> list, double[] o, double[] d, double tMin, double tMax, int fixedTint) {
        double best = tMax;
        boolean found = false;
        for (Models.Placed p : list) {
            double[] po = o, pd = d;
            double[][] pr = p.rotation();
            if (pr != null) {
                po = transposeApply(pr, o[0] - 0.5, o[1] - 0.5, o[2] - 0.5);
                po[0] += 0.5;
                po[1] += 0.5;
                po[2] += 0.5;
                pd = transposeApply(pr, d[0], d[1], d[2]);
            }
            for (Models.Element e : p.model().elements()) {
                double[] eo = po, ed = pd;
                double[][] er = e.rotation();
                if (er != null) {
                    double[] c = e.origin();
                    eo = transposeApply(er, po[0] - c[0], po[1] - c[1], po[2] - c[2]);
                    eo[0] += c[0];
                    eo[1] += c[1];
                    eo[2] += c[2];
                    ed = transposeApply(er, pd[0], pd[1], pd[2]);
                }
                double[] from = e.from(), to = e.to();
                double near = -Double.MAX_VALUE, far = Double.MAX_VALUE;
                int axis = -1;
                boolean positive = false;
                boolean miss = false;
                for (int i = 0; i < 3; i++) {
                    if (Math.abs(ed[i]) < 1e-12) {
                        if (eo[i] < from[i] - EPS || eo[i] > to[i] + EPS) {
                            miss = true;
                            break;
                        }
                        continue;
                    }
                    double t1 = (from[i] - eo[i]) / ed[i], t2 = (to[i] - eo[i]) / ed[i];
                    double n = Math.min(t1, t2);
                    if (n > near) {
                        near = n;
                        axis = i;
                        positive = ed[i] < 0;       // moving toward -axis means the ray came in through the + face
                    }
                    far = Math.min(far, Math.max(t1, t2));
                    if (near > far + EPS) {
                        miss = true;
                        break;
                    }
                }
                if (miss || axis < 0 || near < tMin || near >= best) continue;
                int face = switch (axis) {
                    case 0 -> positive ? 5 : 4;
                    case 1 -> positive ? 1 : 0;
                    default -> positive ? 3 : 2;
                };
                Models.Face f = e.faces()[face];
                if (f == null) continue;
                double hx = eo[0] + ed[0] * near, hy = eo[1] + ed[1] * near, hz = eo[2] + ed[2] * near;
                int argb = faceTexel(f, face, hx, hy, hz, from, to, near);
                int a = argb >>> 24;
                boolean translucent = f.texture().translucent;
                if (a < 26 || (!translucent && a < 128)) continue;      // see-through texel: the ray goes on
                if (f.tint() >= 0) argb = (argb & 0xFF000000) | multiply(argb & 0xFFFFFF, fixedTint >= 0 ? fixedTint : blockTint());
                best = near;
                found = true;
                hitT = near;
                hitArgb = translucent ? argb : (argb | 0xFF000000);
                hitTranslucent = translucent && a < 250;
                hitShade = e.shade();
                double[] n = new double[3];
                n[axis] = positive ? 1 : -1;
                if (er != null) n = apply(er, n[0], n[1], n[2]);
                if (pr != null) n = apply(pr, n[0], n[1], n[2]);
                hitNx = n[0];
                hitNy = n[1];
                hitNz = n[2];
            }
        }
        return found;
    }

    /** The texel of a face at a point of its element (model units), with the face's uv rectangle and rotation. */
    private int faceTexel(Models.Face f, int face, double hx, double hy, double hz, double[] from, double[] to, double distance) {
        double sx = Math.max(1e-9, to[0] - from[0]), sy = Math.max(1e-9, to[1] - from[1]), sz = Math.max(1e-9, to[2] - from[2]);
        double u, v;
        switch (face) {
            case 0 -> {
                u = (hx - from[0]) / sx;
                v = (to[2] - hz) / sz;
            }
            case 1 -> {
                u = (hx - from[0]) / sx;
                v = (hz - from[2]) / sz;
            }
            case 2 -> {
                u = (to[0] - hx) / sx;
                v = (to[1] - hy) / sy;
            }
            case 3 -> {
                u = (hx - from[0]) / sx;
                v = (to[1] - hy) / sy;
            }
            case 4 -> {
                u = (hz - from[2]) / sz;
                v = (to[1] - hy) / sy;
            }
            default -> {
                u = (to[2] - hz) / sz;
                v = (to[1] - hy) / sy;
            }
        }
        u = Math.clamp(u, 0.0, 0.9999);
        v = Math.clamp(v, 0.0, 0.9999);
        switch (f.rotation()) {
            case 90 -> {
                double t = u;
                u = v;
                v = 1 - t;
            }
            case 180 -> {
                u = 1 - u;
                v = 1 - v;
            }
            case 270 -> {
                double t = u;
                u = 1 - v;
                v = t;
            }
            default -> { }
        }
        double tu = f.u0() + (f.u1() - f.u0()) * u;
        double tv = f.v0() + (f.v1() - f.v0()) * v;
        int argb = f.texture().sample(tu, tv);
        return minify(f.texture(), argb, distance);
    }

    // ---- props ------------------------------------------------------------------------------------------------------

    /** Colour (0xRRGGBB) of a prop hit nearer than {@code limit} (hitT is set), or -1. */
    private int intersect(Prop prop, double dx, double dy, double dz, double limit) {
        double[] o = Prop.apply(prop.inv, s.eyeX - prop.b[0], s.eyeY - prop.b[1], s.eyeZ - prop.b[2]);
        double[] d = Prop.apply(prop.inv, dx, dy, dz);
        tintData = null;
        tintValue = -1;
        if (!modelHit(prop.models, o, d, 1e-4, limit, prop.tint >= 0 ? prop.tint : 0x91BD59)) return -1;
        // the model-space normal turned into the world (inverse transpose)
        double nx = prop.inv[0][0] * hitNx + prop.inv[1][0] * hitNy + prop.inv[2][0] * hitNz;
        double ny = prop.inv[0][1] * hitNx + prop.inv[1][1] * hitNy + prop.inv[2][1] * hitNz;
        double nz = prop.inv[0][2] * hitNx + prop.inv[1][2] * hitNy + prop.inv[2][2] * hitNz;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        double shade = hitShade ? 0.6 * nx * nx + 0.8 * nz * nz + (ny > 0 ? 1.0 : 0.5) * ny * ny : 1.0;
        double level = prop.glow ? 1.0 : Math.max(prop.light, s.environment == World.Environment.NORMAL ? skyFactor * 0.9 : 0.4);
        double floor = s.environment == World.Environment.NORMAL ? 0.18 : 0.4;
        return scale(hitArgb & 0xFFFFFF, shade * (floor + (1 - floor) * level));
    }

    private boolean nearSphere(double cx, double cy, double cz, double r, double dx, double dy, double dz, double limit) {
        double ox = cx - s.eyeX, oy = cy - s.eyeY, oz = cz - s.eyeZ;
        double along = ox * dx + oy * dy + oz * dz;
        if (along < -r || along - r > limit) return false;
        return ox * ox + oy * oy + oz * oz - along * along <= r * r;
    }

    // ---- the renderer without client assets -------------------------------------------------------------------------

    /**
     * Map colours, full cubes: the colour of a solid block hit in voxel (x, y, z), -2 for a small non-solid block the
     * ray passes through the middle of (flowers, torches: drawn as an overlay), -1 for nothing.
     */
    private int flat(ChunkSnapshot snap, BlockData data, Material type, int x, int y, int z, double t, int axis, int stepX, int stepY,
                     int stepZ, int overlay, double nextX, double nextY, double nextZ, double dx, double dy, double dz) {
        int rgb = colour(data);
        if (rgb == 0) return -1;            // 0 = map colour NONE: glass, barriers ... are looked through
        boolean glowing = type == Material.LAVA || type == Material.FIRE;
        if (type.isSolid() || glowing) {
            double shade = axis < 0 ? 0.8 : axis == 1 ? (stepY < 0 ? 1.0 : 0.6) : axis == 2 ? 0.88 : 0.76;
            int fx = x, fy = y, fz = z;
            if (axis == 0) fx -= stepX;
            else if (axis == 1) fy -= stepY;
            else if (axis == 2) fz -= stepZ;
            double light = glowing ? 1.0 : light(fx, fy, fz, fx, fy, fz);
            int c = scale(rgb, glowing ? 1.0 : shade * light);
            if (overlay >= 0) c = mix(c, scale(overlay, light), 0.8);
            return c;
        }
        double mid = (t + Math.min(nextX, Math.min(nextY, nextZ))) / 2;
        double mx = s.eyeX + dx * mid - x - 0.5, my = s.eyeY + dy * mid - y, mz = s.eyeZ + dz * mid - z - 0.5;
        return Math.abs(mx) < 0.3 && Math.abs(mz) < 0.3 && my < 0.8 ? -2 : -1;
    }

    // ---- light and sky ----------------------------------------------------------------------------------------------

    private double ambient() {
        return s.environment == World.Environment.NORMAL ? skyFactor : 0.5;
    }

    /** Brightness 0.18..1 from the brighter of two voxels' stored light (the block's own and the one in front of it). */
    private double light(int x0, int y0, int z0, int x1, int y1, int z1) {
        double level = Math.max(level(x0, y0, z0), level(x1, y1, z1));
        double floor = s.environment == World.Environment.NORMAL ? 0.18 : 0.4;   // nether/end have ambient light
        return floor + (1 - floor) * level;
    }

    private double level(int x, int y, int z) {
        if (y >= s.maxY) return skyFactor;
        if (y < s.minY) return 0;
        ChunkSnapshot snap = chunk(x, z);
        if (snap == null) return skyFactor;
        return Math.max(snap.getBlockEmittedLight(x & 15, y, z & 15) / 15.0, snap.getBlockSkyLight(x & 15, y, z & 15) / 15.0 * skyFactor);
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

    // ---- figures and boxes ------------------------------------------------------------------------------------------

    /** Colour of the last successful {@link #intersect(Figure, Figure.Part, double, double, double, double)}. */
    private int figureRgb;

    /**
     * Distance along the ray to one box of a figure if it is nearer than {@code limit}, else -1. The ray is moved
     * into the box's own axes, where the box is axis-aligned; the face it enters through picks the texel. A
     * transparent texel of a second-layer box is no hit.
     */
    private double intersect(Figure figure, Figure.Part p, double dx, double dy, double dz, double limit) {
        double rx = s.eyeX - p.centre[0], ry = s.eyeY - p.centre[1], rz = s.eyeZ - p.centre[2];
        double[] o = {rx * p.ax[0] + ry * p.ax[1] + rz * p.ax[2], rx * p.ay[0] + ry * p.ay[1] + rz * p.ay[2],
                rx * p.az[0] + ry * p.az[1] + rz * p.az[2]};
        double[] d = {dx * p.ax[0] + dy * p.ax[1] + dz * p.ax[2], dx * p.ay[0] + dy * p.ay[1] + dz * p.ay[2],
                dx * p.az[0] + dy * p.az[1] + dz * p.az[2]};
        double[] half = {p.hx, p.hy, p.hz};
        double tMin = 0, tMax = limit;
        int axis = -1;
        boolean positive = false;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (Math.abs(o[i]) > half[i]) return -1;
                continue;
            }
            double t1 = (-half[i] - o[i]) / d[i], t2 = (half[i] - o[i]) / d[i];
            double near = Math.min(t1, t2);
            if (near > tMin) {
                tMin = near;
                axis = i;
                positive = d[i] < 0;        // moving toward -axis means the ray came in through the + face
            }
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }
        if (axis < 0) return -1;            // the eye is inside the box
        int argb = figure.texel(p, axis, positive, o[0] + d[0] * tMin, o[1] + d[1] * tMin, o[2] + d[2] * tMin);
        if (p.overlay && (argb >>> 24) < 128) return -1;
        // light: the face's world normal against "up", like the blocks' face shading, times the light at the creature
        double[] n = axis == 0 ? p.ax : axis == 1 ? p.ay : p.az;
        double up = (positive ? 1 : -1) * n[1];
        double level = Math.max(figure.light, s.environment == World.Environment.NORMAL ? skyFactor * 0.9 : 0.4);
        figureRgb = scale(argb & 0xFFFFFF, (0.74 + 0.26 * up) * (0.25 + 0.75 * level));
        return tMin;
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

    // ---- small maths ------------------------------------------------------------------------------------------------

    private static double[] apply(double[][] m, double x, double y, double z) {
        return new double[] {m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }

    /** The transpose (= inverse, for a rotation) applied to a vector. */
    private static double[] transposeApply(double[][] m, double x, double y, double z) {
        return new double[] {m[0][0] * x + m[1][0] * y + m[2][0] * z, m[0][1] * x + m[1][1] * y + m[2][1] * z,
                m[0][2] * x + m[1][2] * y + m[2][2] * z};
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
        int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
        int bl = (a & 255) * (b & 255) / 255;
        return (r << 16) | (g << 8) | bl;
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
