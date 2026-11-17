package fi.alavesa.phone.camera;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/**
 * Everything the ray tracer needs, copied out of the world on the main thread so the render can run on another
 * thread without touching Bukkit: the camera, immutable chunk snapshots and entity boxes.
 */
final class Scene {

    /** An entity as an axis-aligned box in two colours (the part above {@code headFromY} uses {@code headRgb}). */
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int bodyRgb, int headRgb, double headFromY) {}

    // camera
    double eyeX, eyeY, eyeZ;
    /** Unit vectors: view direction, to the right of the viewer, up from the viewer. */
    double[] forward, right, up;
    /** tan(vertical field of view / 2), zoom already applied. The picture is square, so it is also the horizontal one. */
    double tanHalfFov;
    int maxDistance;
    boolean dither;

    // world
    int minY, maxY;
    World.Environment environment;
    long time;
    boolean storm;

    final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
    final List<Box> entities = new ArrayList<>();

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
