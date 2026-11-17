package fi.alavesa.phone.camera;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.resourcepack.PackService;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.util.PostFx;
import fi.alavesa.phone.util.Sounds;
import fi.alavesa.phone.util.Tasks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * The camera app: viewfinder mode, zoom, taking a picture and printing it.
 *
 * <p><b>Viewfinder.</b> While it is open the player's phones use the {@code phone:phone_camera} item model (hidden
 * in first person) and the post effect {@code phone:camera_<zoom>} draws the zoomed picture and the frame. Controls
 * need no client mod: right-click = shutter, mouse wheel / hotbar keys = zoom (the slot change is cancelled, so the
 * phone stays in hand), sneak = leave.
 *
 * <p><b>Zoom.</b> Primary method: one post effect per zoom level, swapped with {@code Player.postEffects()}.
 * Fallback (config, and automatically for players without the pack): a movement-speed modifier that makes the
 * client narrow its own FOV; limited to about 2x. Either way the photo is rendered with the zoom the player saw.
 *
 * <p><b>Photo.</b> The server cannot see the client's screen, so it renders the picture itself: chunk snapshots are
 * copied on the main thread a few per tick, entity boxes are noted, and {@link RayTracer} does the rest on one
 * background thread. The main thread never waits for it.
 */
public final class CameraService implements Listener {

    /** Shows the "photo taken" screen; implemented by the UI. */
    public interface ResultScreen {
        void show(Player player, int zoom, String location);
    }

    private static final NamespacedKey FOV_MODIFIER = new NamespacedKey("phone", "camera_zoom");
    private static final Key FLASH = Key.key("phone", "flash");

    private static final class Session {
        int zoomIndex;
        long lastShot;
        boolean capturing;
        boolean postEffect;         // zoom by post effect (else by FOV)
        Key effect;
        Pending pending;
        boolean autoPrint;          // /phone shutter <player> print: skip the result screen
    }

    private record Pending(int[] rgb, long takenAt, Location where) {}

    private final Plugin plugin;
    private final Database db;
    private final PhoneItems items;
    private final PackService pack;
    private final PostFx postFx;
    private final Sounds sounds;
    private final PhotoStore photos;
    private PhoneConfig cfg;
    private Lang lang;
    private ResultScreen resultScreen;
    /** Puts the player's phones back into their normal/unread look when the viewfinder closes. */
    private java.util.function.Consumer<Player> itemSync = p -> { };
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Double> fovs = new HashMap<>();
    private final ExecutorService renderer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phone-camera");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public CameraService(Plugin plugin, Database db, PhoneItems items, PackService pack, PostFx postFx, Sounds sounds,
                         PhotoStore photos, PhoneConfig cfg, Lang lang) {
        this.plugin = plugin;
        this.db = db;
        this.items = items;
        this.pack = pack;
        this.postFx = postFx;
        this.sounds = sounds;
        this.photos = photos;
        this.cfg = cfg;
        this.lang = lang;
        // keeps the hint on screen and notices a phone that left the hands
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void reload(PhoneConfig cfg, Lang lang) {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exit(p);
        }
        this.cfg = cfg;
        this.lang = lang;
    }

    public void setResultScreen(ResultScreen screen) {
        this.resultScreen = screen;
    }

    public void setItemSync(java.util.function.Consumer<Player> itemSync) {
        this.itemSync = itemSync;
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exit(p);
        }
        renderer.shutdownNow();
    }

    public boolean isActive(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ---- field of view ----------------------------------------------------------------------------------------------

    /** The vertical FOV photos of this player are rendered with at 1x. */
    public double fov(Player player) {
        return fovs.getOrDefault(player.getUniqueId(), cfg.baseFov);
    }

    public void setFov(Player player, Double fov) {
        if (fov == null) fovs.remove(player.getUniqueId());
        else fovs.put(player.getUniqueId(), fov);
        db.setFov(player.getUniqueId(), fov);
    }

    /**
     * FOV fallback: the client multiplies its FOV by (speed / walk speed + 1) / 2, so a speed modifier of m (multiply
     * total) gives a factor of 1 + m / 2. The smallest factor is 0.5 (speed 0).
     */
    private static double fovFactor(double baseFov, int zoom) {
        double wanted = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(baseFov / 2)) / zoom));
        return Math.max(0.5, wanted / baseFov);
    }

    /** tan(half the vertical FOV) the player currently sees inside the photo frame. */
    private double tanHalfFov(Player player, Session s) {
        double base = fov(player);
        int zoom = cfg.zoomLevels.get(s.zoomIndex);
        if (s.postEffect) return Math.tan(Math.toRadians(base / 2)) / zoom;
        return Math.tan(Math.toRadians(base * fovFactor(base, zoom) / 2));
    }

    // ---- viewfinder -------------------------------------------------------------------------------------------------

    public void enter(Player player) {
        if (!cfg.cameraEnabled || !player.hasPermission("phone.camera")) {
            player.sendMessage(lang.get("camera.not-allowed"));
            return;
        }
        if (isActive(player)) return;
        Session s = new Session();
        s.postEffect = cfg.zoomMethod == PhoneConfig.ZoomMethod.POST_EFFECT && pack.hasPack(player);
        sessions.put(player.getUniqueId(), s);
        items.sync(player, PhoneItems.State.CAMERA);
        applyZoom(player, s);
        hint(player, s);
    }

    public void exit(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        if (s.effect != null) postFx.set(player, s.effect, false);
        postFx.set(player, FLASH, false);
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(FOV_MODIFIER);
        itemSync.accept(player);
        if (player.isOnline()) player.sendActionBar(net.kyori.adventure.text.Component.empty());
    }

    private void applyZoom(Player player, Session s) {
        int zoom = cfg.zoomLevels.get(s.zoomIndex);
        if (s.postEffect) {
            Key next = Key.key("phone", "camera_" + zoom);
            if (!next.equals(s.effect)) {
                // add the new one first: no frame without an effect in between
                postFx.set(player, next, true);
                if (s.effect != null) postFx.set(player, s.effect, false);
                s.effect = next;
            }
        } else {
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed == null) return;
            speed.removeModifier(FOV_MODIFIER);
            double factor = fovFactor(fov(player), zoom);
            if (factor < 1.0) {
                speed.addTransientModifier(new AttributeModifier(FOV_MODIFIER, 2 * factor - 2, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    private void hint(Player player, Session s) {
        player.sendActionBar(lang.get(s.capturing ? "camera.actionbar-capturing" : "camera.actionbar",
                Lang.ph("zoom", cfg.zoomLevels.get(s.zoomIndex))));
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                sessions.remove(id);
            } else if (!items.holdsPhone(p)) {
                exit(p);
            } else {
                hint(p, sessions.get(id));
            }
        }
    }

    /** Scrolling the hotbar zooms: wheel up (towards slot 0) zooms in. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);       // the phone stays in hand
        int delta = Math.floorMod(e.getNewSlot() - e.getPreviousSlot(), 9);
        int next = Math.clamp(s.zoomIndex + (delta >= 5 ? 1 : -1), 0, cfg.zoomLevels.size() - 1);
        if (next == s.zoomIndex) return;
        s.zoomIndex = next;
        applyZoom(e.getPlayer(), s);
        hint(e.getPlayer(), s);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && isActive(e.getPlayer())) exit(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        exit(e.getEntity());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        exit(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        // a crash while zoomed must not leave the slow-down behind
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(FOV_MODIFIER);
        Tasks.onMain(plugin, db.fov(player.getUniqueId()), fov -> {
            if (fov != null && player.isOnline()) fovs.put(player.getUniqueId(), fov);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exit(e.getPlayer());
        fovs.remove(e.getPlayer().getUniqueId());
    }

    // ---- taking a picture -------------------------------------------------------------------------------------------

    /** The shutter pressed by command: takes the picture and, with {@code print}, prints it without asking. */
    public void shoot(Player player, boolean print) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.capturing) return;
        s.autoPrint = print;
        shoot(player);
    }

    /** Right-click in viewfinder mode. */
    public void shoot(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.capturing) return;
        long now = System.currentTimeMillis();
        long wait = cfg.cooldownSeconds * 1000L - (now - s.lastShot);
        if (wait > 0) {
            player.sendActionBar(lang.get("camera.cooldown", Lang.ph("seconds", (wait + 999) / 1000)));
            return;
        }
        s.capturing = true;
        s.lastShot = now;
        s.pending = null;
        sounds.play(player, Sounds.Cue.SHUTTER);
        if (pack.hasPack(player)) {
            postFx.set(player, FLASH, true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> postFx.set(player, FLASH, false), 3L);
        }
        hint(player, s);

        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        Scene scene = new Scene();
        scene.eyeX = eye.getX();
        scene.eyeY = eye.getY();
        scene.eyeZ = eye.getZ();
        Vector f = eye.getDirection();
        double yaw = Math.toRadians(eye.getYaw());
        // yaw 0 looks toward +z; the viewer's right is then -x
        double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        scene.forward = new double[] {f.getX(), f.getY(), f.getZ()};
        scene.right = right;
        scene.up = new double[] {
                right[1] * f.getZ() - right[2] * f.getY(),
                right[2] * f.getX() - right[0] * f.getZ(),
                right[0] * f.getY() - right[1] * f.getX()};
        scene.tanHalfFov = tanHalfFov(player, s);
        scene.maxDistance = cfg.maxDistance;
        scene.dither = cfg.dither;
        scene.minY = world.getMinHeight();
        scene.maxY = world.getMaxHeight();
        scene.environment = world.getEnvironment();
        scene.time = world.getTime();
        scene.storm = world.hasStorm();
        if (cfg.renderEntities) captureEntities(player, eye, scene);

        ArrayDeque<int[]> wanted = chunksInView(scene);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != s) {
                    cancel();
                    s.capturing = false;
                    return;
                }
                // copying a chunk is the only main-thread cost of a photo: a few per tick
                for (int i = 0; i < cfg.chunksPerTick && !wanted.isEmpty(); i++) {
                    int[] c = wanted.poll();
                    if (world.isChunkLoaded(c[0], c[1])) {
                        scene.chunks.put(Scene.chunkKey(c[0], c[1]), world.getChunkAt(c[0], c[1]).getChunkSnapshot(false, false, false));
                    }
                }
                if (!wanted.isEmpty()) return;
                cancel();
                CompletableFuture<int[]> render = CompletableFuture.supplyAsync(() -> RayTracer.render(scene), renderer);
                render.whenComplete((rgb, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> developed(player, s, rgb, error, now, eye));
                });
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void developed(Player player, Session s, int[] rgb, Throwable error, long takenAt, Location eye) {
        s.capturing = false;
        if (error != null) {
            plugin.getLogger().log(Level.SEVERE, "Rendering a photo failed", error);
            if (player.isOnline()) player.sendMessage(lang.get("camera.failed"));
            return;
        }
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != s) return;
        s.pending = new Pending(rgb, takenAt, eye);
        if (s.autoPrint) {
            s.autoPrint = false;
            print(player);
            return;
        }
        String location = cfg.locationInLore
                ? eye.getWorld().getName() + "  " + eye.getBlockX() + ", " + eye.getBlockY() + ", " + eye.getBlockZ() : "";
        resultScreen.show(player, cfg.zoomLevels.get(s.zoomIndex), location);
    }

    /**
     * The chunk columns the view can reach, nearest first, capped at max-chunks. The view frustum is projected onto
     * the ground: a chunk is wanted when its centre lies inside (or within a chunk's half diagonal of) the convex
     * hull of the eye and the four far corners.
     */
    private ArrayDeque<int[]> chunksInView(Scene scene) {
        double[][] pts = new double[5][];
        pts[0] = new double[] {scene.eyeX, scene.eyeZ};
        int n = 1;
        for (int sy = -1; sy <= 1; sy += 2) {
            for (int sx = -1; sx <= 1; sx += 2) {
                double dx = scene.forward[0] + (scene.right[0] * sx + scene.up[0] * sy) * scene.tanHalfFov;
                double dy = scene.forward[1] + (scene.right[1] * sx + scene.up[1] * sy) * scene.tanHalfFov;
                double dz = scene.forward[2] + (scene.right[2] * sx + scene.up[2] * sy) * scene.tanHalfFov;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                pts[n++] = new double[] {scene.eyeX + dx / len * scene.maxDistance, scene.eyeZ + dz / len * scene.maxDistance};
            }
        }
        double[][] hull = hull(pts);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        List<int[]> found = new ArrayList<>();
        for (int cx = (int) Math.floor(minX) >> 4; cx <= (int) Math.floor(maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(minZ) >> 4; cz <= (int) Math.floor(maxZ) >> 4; cz++) {
                if (distanceToHull(hull, cx * 16 + 8, cz * 16 + 8) <= 11.4) found.add(new int[] {cx, cz});
            }
        }
        found.sort(Comparator.comparingDouble(c -> Math.pow(c[0] * 16 + 8 - scene.eyeX, 2) + Math.pow(c[1] * 16 + 8 - scene.eyeZ, 2)));
        ArrayDeque<int[]> out = new ArrayDeque<>();
        for (int i = 0; i < found.size() && i < cfg.maxChunks; i++) out.add(found.get(i));
        return out;
    }

    /** Convex hull (monotone chain), counter-clockwise. */
    private static double[][] hull(double[][] points) {
        double[][] p = points.clone();
        java.util.Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] h = new double[p.length * 2][];
        int k = 0;
        for (double[] pt : p) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], pt) <= 0) k--;
            h[k++] = pt;
        }
        for (int i = p.length - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        return java.util.Arrays.copyOf(h, Math.max(1, k - 1));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** 0 inside the hull, else the distance to its nearest edge. */
    private static double distanceToHull(double[][] hull, double x, double z) {
        if (hull.length == 1) return Math.hypot(x - hull[0][0], z - hull[0][1]);
        boolean inside = hull.length >= 3;
        double best = Double.MAX_VALUE;
        double[] q = {x, z};
        for (int i = 0; i < hull.length; i++) {
            double[] a = hull[i], b = hull[(i + 1) % hull.length];
            if (cross(a, b, q) < 0) inside = false;
            double ex = b[0] - a[0], ez = b[1] - a[1];
            double len2 = ex * ex + ez * ez;
            double t = len2 == 0 ? 0 : Math.clamp(((x - a[0]) * ex + (z - a[1]) * ez) / len2, 0.0, 1.0);
            best = Math.min(best, Math.hypot(x - (a[0] + ex * t), z - (a[1] + ez * t)));
        }
        return inside ? 0 : best;
    }

    private void captureEntities(Player photographer, Location eye, Scene scene) {
        List<Entity> near = new ArrayList<>(eye.getWorld().getNearbyEntities(eye, cfg.maxDistance, cfg.maxDistance, cfg.maxDistance,
                e -> e instanceof LivingEntity living && e != photographer && !living.isInvisible()
                        && e.getType() != EntityType.ARMOR_STAND
                        && !(e instanceof Player p && (p.getGameMode() == GameMode.SPECTATOR || !photographer.canSee(p)))));
        near.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(eye)));
        for (int i = 0; i < near.size() && i < 64; i++) {
            Entity e = near.get(i);
            BoundingBox b = e.getBoundingBox();
            int[] c = colours(e);
            scene.entities.add(new Scene.Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ(),
                    c[0], c[1], b.getMinY() + b.getHeight() * 0.75));
        }
    }

    /** Body and head colour of an entity in a photo: a handful of recognisable mobs, sensible defaults for the rest. */
    private static int[] colours(Entity e) {
        return switch (e.getType()) {
            case PLAYER -> new int[] {0x3A7BD5, 0xD8A27A};
            case VILLAGER, WANDERING_TRADER -> new int[] {0x7A4F35, 0xC68E6E};
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED -> new int[] {0x3C6E8F, 0x4F7F3A};
            case SKELETON, STRAY, BOGGED -> new int[] {0xBDBDBD, 0xD6D6D6};
            case CREEPER -> new int[] {0x4CAF50, 0x4CAF50};
            case ENDERMAN -> new int[] {0x161616, 0x161616};
            case SPIDER, CAVE_SPIDER -> new int[] {0x34302B, 0x34302B};
            case COW -> new int[] {0x4A3A2A, 0x4A3A2A};
            case PIG -> new int[] {0xF0A5A2, 0xF0A5A2};
            case SHEEP -> new int[] {0xEDEDED, 0xC9B8A4};
            case CHICKEN -> new int[] {0xF5F5F5, 0xF5F5F5};
            case IRON_GOLEM -> new int[] {0xD9D2C5, 0xD9D2C5};
            default -> e instanceof Monster ? new int[] {0x5A6B4A, 0x5A6B4A}
                    : e instanceof WaterMob ? new int[] {0x6FA8C8, 0x6FA8C8} : new int[] {0x9A7B5A, 0x9A7B5A};
        };
    }

    // ---- printing ---------------------------------------------------------------------------------------------------

    public boolean hasPending(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.pending != null;
    }

    public void discard(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) s.pending = null;
    }

    /** Turns the last picture into a photo item. Returns false (after telling the player why) when it cannot. */
    public boolean print(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.pending == null) {
            player.sendMessage(lang.get("camera.nothing-to-print"));
            return false;
        }
        boolean needsPaper = cfg.requirePhotoPaper && player.getGameMode() != GameMode.CREATIVE;
        if (needsPaper && !items.hasPhotoPaper(player)) {
            player.sendMessage(lang.get("camera.no-paper"));
            return false;
        }
        boolean full = player.getInventory().firstEmpty() == -1;
        if (full && !cfg.dropWhenFull) {
            player.sendMessage(lang.get("camera.inventory-full"));
            return false;
        }
        if (needsPaper) items.takePhotoPaper(player);
        Pending p = s.pending;
        s.pending = null;
        ItemStack photo = photos.create(player, p.rgb(), p.takenAt(), p.where());
        Map<Integer, ItemStack> left = player.getInventory().addItem(photo);
        if (!left.isEmpty()) {
            for (ItemStack item : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
            player.sendMessage(lang.get("camera.printed-dropped"));
        } else {
            player.sendMessage(lang.get("camera.printed"));
        }
        return true;
    }
}
