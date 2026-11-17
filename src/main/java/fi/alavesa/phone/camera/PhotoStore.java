package fi.alavesa.phone.camera;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.storage.Database;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
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
 * Printed photos. A photo is a vanilla filled map: it shows in the hand and in item frames (also glowing ones),
 * can be cloned on a cartography table and needs no client support. The map's own pixel data stays empty; the
 * picture is a PNG in plugins/Phone/photos/&lt;map id&gt;.png that a {@link MapRenderer} paints onto the map.
 *
 * <p>Persistence: the map id is stored in the item by vanilla, the PNG and a row in the photos table by us. When
 * the server loads a map (someone holds it, or a frame with it comes into view) Bukkit fires
 * {@link MapInitializeEvent}; if the id is one of ours the renderer is attached again and reads the PNG off-thread.
 */
public final class PhotoStore implements Listener {

    private final Plugin plugin;
    private final Database db;
    private final PhoneItems items;
    private final File dir;
    private final Set<Integer> ids = ConcurrentHashMap.newKeySet();
    private PhoneConfig cfg;
    private Lang lang;

    public PhotoStore(Plugin plugin, Database db, PhoneItems items, PhoneConfig cfg, Lang lang, Set<Integer> knownIds) {
        this.plugin = plugin;
        this.db = db;
        this.items = items;
        this.cfg = cfg;
        this.lang = lang;
        this.dir = new File(plugin.getDataFolder(), "photos");
        this.ids.addAll(knownIds);
        if (!dir.isDirectory() && !dir.mkdirs()) plugin.getLogger().warning("Could not create " + dir);
    }

    public void reload(PhoneConfig cfg, Lang lang) {
        this.cfg = cfg;
        this.lang = lang;
    }

    private File file(int mapId) {
        return new File(dir, mapId + ".png");
    }

    /**
     * Creates the map, stores the picture and returns the photo item. Main thread; the file and database writes
     * happen in the background.
     */
    public ItemStack create(Player photographer, int[] rgb, long takenAt, Location where) {
        MapView view = Bukkit.createMap(where.getWorld());
        int id = view.getId();
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);       // nothing may redraw it as a terrain map
        ids.add(id);
        attach(view, rgb);

        File file = file(id);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BufferedImage image = new BufferedImage(RayTracer.SIZE, RayTracer.SIZE, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, RayTracer.SIZE, RayTracer.SIZE, rgb, 0, RayTracer.SIZE);
            try {
                ImageIO.write(image, "png", file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save photo " + file, e);
            }
        });
        db.insertPhoto(id, photographer.getUniqueId(), photographer.getName(), takenAt, where.getWorld().getName(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ());

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        ZonedDateTime time = Instant.ofEpochMilli(takenAt).atZone(cfg.zone);
        String stamp = time.format(cfg.dateFormat) + " " + time.getYear() + ", " + time.format(cfg.timeFormat);
        item.editMeta(MapMeta.class, meta -> {
            meta.setMapView(view);
            meta.itemName(lang.get("item.photo.name", Lang.ph("player", photographer.getName())));
            List<Component> lore = new ArrayList<>();
            lore.add(plain(lang.get("item.photo.lore-by", Lang.ph("player", photographer.getName()))));
            lore.add(plain(lang.get("item.photo.lore-date", Lang.ph("date", stamp))));
            if (cfg.locationInLore) {
                lore.add(plain(lang.get("item.photo.lore-location", Lang.ph("world", where.getWorld().getName()),
                        Lang.ph("x", where.getBlockX()), Lang.ph("y", where.getBlockY()), Lang.ph("z", where.getBlockZ()))));
            }
            meta.lore(lore);
            if (items.useItemModel()) meta.setItemModel(PhoneItems.PHOTO_MODEL);
            meta.getPersistentDataContainer().set(PhoneItems.PHOTO, PersistentDataType.INTEGER, id);
        });
        return item;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void attach(MapView view, int[] rgb) {
        for (MapRenderer old : new ArrayList<>(view.getRenderers())) view.removeRenderer(old);
        view.addRenderer(new Painter(rgb, file(view.getId())));
    }

    @EventHandler
    public void onMapInitialize(MapInitializeEvent e) {
        if (ids.contains(e.getMap().getId())) attach(e.getMap(), null);
    }

    /** Paints the picture onto the map once; the canvas is shared by all viewers. */
    private final class Painter extends MapRenderer {

        private volatile int[] rgb;
        private final File file;
        private boolean loading;
        private boolean drawn;

        Painter(int[] rgb, File file) {
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
            for (int y = 0; y < RayTracer.SIZE; y++) {
                for (int x = 0; x < RayTracer.SIZE; x++) {
                    canvas.setPixelColor(x, y, new java.awt.Color(pixels[y * RayTracer.SIZE + x]));
                }
            }
            drawn = true;
            rgb = null;     // the canvas keeps the picture; free the copy
        }

        private void load() {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null || image.getWidth() != RayTracer.SIZE || image.getHeight() != RayTracer.SIZE) {
                    throw new IOException("not a " + RayTracer.SIZE + "x" + RayTracer.SIZE + " image");
                }
                int[] pixels = image.getRGB(0, 0, RayTracer.SIZE, RayTracer.SIZE, null, 0, RayTracer.SIZE);
                for (int i = 0; i < pixels.length; i++) pixels[i] &= 0xFFFFFF;
                rgb = pixels;
            } catch (IOException e) {
                plugin.getLogger().warning("Photo " + file.getName() + " could not be read (" + e.getMessage() + "); the map stays blank");
            }
        }
    }
}
