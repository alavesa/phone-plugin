package fi.alavesa.phone.camera;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.storage.Database;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Printed photos and videos. Both are vanilla filled maps: they show in the hand and in item frames and need no
 * client support. The map's own pixel data stays empty; a {@link MapRenderer} paints it from a file in the plugin
 * folder: photos/&lt;map id&gt;.png for a photo, videos/&lt;map id&gt;.bin (gzip) for a video.
 *
 * <p>Persistence: the map id is stored in the item by vanilla, the file and a row in the photos table by us. When
 * the server loads a map (someone holds it, or a frame with it comes into view) Bukkit fires
 * {@link MapInitializeEvent}; if the id is one of ours the painter is attached again and reads the file off-thread.
 *
 * <p>Memory: a photo is painted once and its pixels released. A video's frames are in memory only while the map
 * is being drawn for someone; {@value #IDLE_MILLIS} ms after the last draw they are dropped and read again from
 * the file the next time the video is looked at.
 */
public final class PhotoStore implements Listener {

    private static final int SIZE = RayTracer.SIZE;
    private static final long IDLE_MILLIS = 5000;
    private static final int VIDEO_MAGIC = 0x50485631;      // "PHV1"

    private final Plugin plugin;
    private final Database db;
    private final PhoneItems items;
    private final File photoDir;
    private final File videoDir;
    /** map id -> frame count (0 = photo). */
    private final Map<Integer, Integer> ids = new ConcurrentHashMap<>();
    private final Map<Integer, VideoPainter> playing = new HashMap<>();
    private PhoneConfig cfg;
    private Lang lang;

    public PhotoStore(Plugin plugin, Database db, PhoneItems items, PhoneConfig cfg, Lang lang, Map<Integer, Integer> known) {
        this.plugin = plugin;
        this.db = db;
        this.items = items;
        this.cfg = cfg;
        this.lang = lang;
        this.photoDir = new File(plugin.getDataFolder(), "photos");
        this.videoDir = new File(plugin.getDataFolder(), "videos");
        this.ids.putAll(known);
        for (File dir : new File[] {photoDir, videoDir}) {
            if (!dir.isDirectory() && !dir.mkdirs()) plugin.getLogger().warning("Could not create " + dir);
        }
        // videos nobody looks at any more give their frames back
        Bukkit.getScheduler().runTaskTimer(plugin, this::releaseIdle, 100L, 100L);
    }

    public void reload(PhoneConfig cfg, Lang lang) {
        this.cfg = cfg;
        this.lang = lang;
    }

    /** Frames of videos currently held in memory (for /phone status and tests). */
    public int framesInMemory() {
        int n = 0;
        for (VideoPainter p : playing.values()) {
            int[][] frames = p.frames;
            if (frames != null) n += frames.length;
        }
        return n;
    }

    private void releaseIdle() {
        long now = System.currentTimeMillis();
        for (VideoPainter p : playing.values()) p.releaseIfIdle(now);
    }

    // ---- creating ---------------------------------------------------------------------------------------------------

    private MapView newMap(Location where) {
        MapView view = Bukkit.createMap(where.getWorld());
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);       // nothing may redraw it as a terrain map
        for (MapRenderer old : new ArrayList<>(view.getRenderers())) view.removeRenderer(old);
        return view;
    }

    private ItemStack item(MapView view, Player photographer, long takenAt, Location where, String nameKey, double seconds) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        ZonedDateTime time = Instant.ofEpochMilli(takenAt).atZone(cfg.zone);
        String stamp = time.format(cfg.dateFormat) + " " + time.getYear() + ", " + time.format(cfg.timeFormat);
        item.editMeta(MapMeta.class, meta -> {
            meta.setMapView(view);
            meta.itemName(lang.get(nameKey, Lang.ph("player", photographer.getName())));
            List<Component> lore = new ArrayList<>();
            lore.add(plain(lang.get("item.photo.lore-by", Lang.ph("player", photographer.getName()))));
            lore.add(plain(lang.get("item.photo.lore-date", Lang.ph("date", stamp))));
            if (seconds > 0) {
                lore.add(plain(lang.get("item.video.lore-length", Lang.ph("seconds", String.format(Locale.ROOT, "%.1f", seconds)))));
            }
            if (cfg.locationInLore) {
                lore.add(plain(lang.get("item.photo.lore-location", Lang.ph("world", where.getWorld().getName()),
                        Lang.ph("x", where.getBlockX()), Lang.ph("y", where.getBlockY()), Lang.ph("z", where.getBlockZ()))));
            }
            meta.lore(lore);
            if (items.useItemModel()) meta.setItemModel(PhoneItems.PHOTO_MODEL);
            meta.getPersistentDataContainer().set(PhoneItems.PHOTO, PersistentDataType.INTEGER, view.getId());
        });
        return item;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Creates the map, stores the picture and returns the photo item. Main thread; file and database in the background. */
    public ItemStack create(Player photographer, int[] rgb, long takenAt, Location where) {
        MapView view = newMap(where);
        int id = view.getId();
        ids.put(id, 0);
        view.addRenderer(new PhotoPainter(rgb, new File(photoDir, id + ".png")));
        File file = new File(photoDir, id + ".png");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, SIZE, SIZE, rgb, 0, SIZE);
            try {
                ImageIO.write(image, "png", file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save photo " + file, e);
            }
        });
        db.insertPhoto(id, photographer.getUniqueId(), photographer.getName(), takenAt, where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ(), 0);
        return item(view, photographer, takenAt, where, "item.photo.name", 0);
    }

    /** Like {@link #create}, for a recording. The frames are written to disk and not kept. */
    public ItemStack createVideo(Player photographer, List<int[]> frames, int fps, long takenAt, Location where) {
        MapView view = newMap(where);
        int id = view.getId();
        ids.put(id, frames.size());
        File file = new File(videoDir, id + ".bin");
        // the painter reads the file like after a restart; until the file exists it shows the first frame
        VideoPainter painter = new VideoPainter(id, file, frames.getFirst());
        playing.put(id, painter);
        view.addRenderer(painter);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
                out.writeInt(VIDEO_MAGIC);
                out.writeInt(frames.size());
                out.writeInt(fps);
                byte[] row = new byte[SIZE * SIZE * 3];
                for (int[] frame : frames) {
                    for (int i = 0; i < frame.length; i++) {
                        row[i * 3] = (byte) (frame[i] >> 16);
                        row[i * 3 + 1] = (byte) (frame[i] >> 8);
                        row[i * 3 + 2] = (byte) frame[i];
                    }
                    out.write(row);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save video " + file, e);
            }
            painter.fileReady = true;
        });
        db.insertPhoto(id, photographer.getUniqueId(), photographer.getName(), takenAt, where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ(), frames.size());
        return item(view, photographer, takenAt, where, "item.video.name", frames.size() / (double) fps);
    }

    @EventHandler
    public void onMapInitialize(MapInitializeEvent e) {
        MapView view = e.getMap();
        Integer frames = ids.get(view.getId());
        if (frames == null) return;
        for (MapRenderer old : new ArrayList<>(view.getRenderers())) view.removeRenderer(old);
        if (frames == 0) {
            view.addRenderer(new PhotoPainter(null, new File(photoDir, view.getId() + ".png")));
        } else {
            VideoPainter painter = new VideoPainter(view.getId(), new File(videoDir, view.getId() + ".bin"), null);
            painter.fileReady = true;
            playing.put(view.getId(), painter);
            view.addRenderer(painter);
        }
    }

    private static void paint(MapCanvas canvas, int[] pixels, int[] shown, Map<Integer, java.awt.Color> colours) {
        for (int i = 0; i < pixels.length; i++) {
            // only what changed: fewer palette look-ups and smaller map packets
            if (shown != null && shown[i] == pixels[i]) continue;
            int rgb = pixels[i];
            java.awt.Color colour = colours == null ? new java.awt.Color(rgb) : colours.computeIfAbsent(rgb, java.awt.Color::new);
            canvas.setPixelColor(i % SIZE, i / SIZE, colour);
            if (shown != null) shown[i] = rgb;
        }
    }

    /** Paints a photo onto its map once; the canvas is shared by all viewers. */
    private final class PhotoPainter extends MapRenderer {

        private volatile int[] rgb;
        private final File file;
        private boolean loading;
        private boolean drawn;

        PhotoPainter(int[] rgb, File file) {
            super(false);
            this.rgb = rgb;
            this.file = file;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (drawn) return;
            int[] pixels = rgb;
            if (pixels == null) {
                if (!loading) {
                    loading = true;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::load);
                }
                return;     // drawn on a later call, when the file has been read
            }
            paint(canvas, pixels, null, null);
            drawn = true;
            rgb = null;     // the canvas keeps the picture; free the copy
        }

        private void load() {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
                    throw new IOException("not a " + SIZE + "x" + SIZE + " image");
                }
                int[] pixels = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
                for (int i = 0; i < pixels.length; i++) pixels[i] &= 0xFFFFFF;
                rgb = pixels;
            } catch (IOException e) {
                plugin.getLogger().warning("Photo " + file.getName() + " could not be read (" + e.getMessage() + "); the map stays blank");
            }
        }
    }

    /**
     * Plays a video on its map, in a loop, at the speed it was recorded. The server calls {@link #render} while
     * someone holds the map (every tick) or has it in a frame in view; each call notes the time, and the frame that
     * belongs to "now" is painted if it is not the one showing.
     */
    private final class VideoPainter extends MapRenderer {

        private final int id;
        private final File file;
        volatile int[][] frames;
        private volatile int fps = 4;
        volatile boolean fileReady;
        private volatile boolean loading;
        private boolean broken;
        private long lastUsed;
        private long startedAt;
        private int showing = -1;
        private int[] poster;                       // first frame, shown until the file has been written
        private final int[] shown = new int[SIZE * SIZE];
        private final Map<Integer, java.awt.Color> colours = new HashMap<>();

        VideoPainter(int id, File file, int[] poster) {
            super(false);
            this.id = id;
            this.file = file;
            this.poster = poster;
            java.util.Arrays.fill(shown, -1);
        }

        /**
         * Is this player looking at the video? Yes when it is in one of their hands, or when they do not carry
         * it at all (then the server is drawing it for an item frame near them). A video that merely sits in
         * the inventory is not being watched and must not keep its frames loaded.
         */
        private boolean watchedBy(Player player) {
            var inv = player.getInventory();
            if (isThis(inv.getItemInMainHand()) || isThis(inv.getItemInOffHand())) return true;
            for (ItemStack item : inv.getStorageContents()) {
                if (isThis(item)) return false;
            }
            return true;
        }

        private boolean isThis(ItemStack item) {
            if (item == null || item.getType() != Material.FILLED_MAP) return false;
            Integer photo = item.getPersistentDataContainer().get(PhoneItems.PHOTO, PersistentDataType.INTEGER);
            return photo != null && photo == id;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            long now = System.currentTimeMillis();
            if (!watchedBy(player)) {
                // still show something on the inventory icon's map the first time
                if (poster != null) {
                    paint(canvas, poster, shown, colours);
                    poster = null;
                }
                return;
            }
            lastUsed = now;
            int[][] loaded = frames;
            if (loaded == null) {
                if (poster != null) {
                    paint(canvas, poster, shown, colours);
                    poster = null;
                }
                if (fileReady && !loading && !broken) {
                    loading = true;
                    startedAt = now;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::load);
                }
                return;
            }
            int index = (int) ((now - startedAt) * fps / 1000 % loaded.length);
            if (index == showing) return;
            showing = index;
            paint(canvas, loaded[index], shown, colours);
        }

        /** Main thread, every five seconds: nobody has drawn this video for a while, so its frames go. */
        void releaseIfIdle(long now) {
            if (frames != null && !loading && now - lastUsed > IDLE_MILLIS) {
                frames = null;
                showing = -1;
                colours.clear();
            }
        }

        private void load() {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
                if (in.readInt() != VIDEO_MAGIC) throw new IOException("not a phone video");
                int count = in.readInt();
                int rate = in.readInt();
                if (count < 1 || count > 600 || rate < 1 || rate > 20) throw new IOException("bad header");
                int[][] out = new int[count][SIZE * SIZE];
                byte[] row = new byte[SIZE * SIZE * 3];
                for (int f = 0; f < count; f++) {
                    in.readFully(row);
                    for (int i = 0; i < SIZE * SIZE; i++) {
                        out[f][i] = ((row[i * 3] & 255) << 16) | ((row[i * 3 + 1] & 255) << 8) | (row[i * 3 + 2] & 255);
                    }
                }
                fps = rate;
                frames = out;
            } catch (IOException e) {
                broken = true;
                plugin.getLogger().warning("Video " + file.getName() + " (map " + id + ") could not be read (" + e.getMessage() + ")");
            } finally {
                loading = false;
            }
        }
    }
}
