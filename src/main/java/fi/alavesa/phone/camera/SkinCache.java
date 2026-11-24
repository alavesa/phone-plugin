package fi.alavesa.phone.camera;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

/**
 * The skins of online players as 64x64 pixel arrays, for drawing them in photos. A skin is downloaded once, in the
 * background, when the player joins (the address comes from their profile; nothing is asked of the client) and is
 * dropped when they leave. Until it has arrived, and for players without a skin (offline-mode servers), a generated
 * stand-in is used.
 */
public final class SkinCache implements Listener {

    private record Skin(int[] pixels, boolean slim) {}

    private final Plugin plugin;
    private final boolean download;
    private final Map<UUID, Skin> skins = new ConcurrentHashMap<>();
    private final int[] standIn = standIn();

    public SkinCache(Plugin plugin, boolean download) {
        this.plugin = plugin;
        this.download = download;
        for (Player p : Bukkit.getOnlinePlayers()) fetch(p);
    }

    int[] pixels(Player player) {
        Skin skin = skins.get(player.getUniqueId());
        return skin == null ? standIn : skin.pixels;
    }

    boolean slim(Player player) {
        Skin skin = skins.get(player.getUniqueId());
        return skin != null && skin.slim;
    }

    /** The stand-in skin (64x64 ARGB) for heads whose skin is not known. */
    int[] standInPixels() {
        return standIn;
    }

    private final Map<String, int[]> byUrl = new ConcurrentHashMap<>();
    private final java.util.Set<String> fetching = ConcurrentHashMap.newKeySet();

    /**
     * A skin by its texture address (player heads on items): the pixels once downloaded, null until then (the first
     * photo of a new head shows the stand-in; the download starts in the background).
     */
    int[] byUrl(String url) {
        int[] px = byUrl.get(url);
        if (px != null || !download || !url.startsWith("http") || byUrl.size() > 512 || !fetching.add(url)) return px;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage image = ImageIO.read(java.net.URI.create(url).toURL());
                if (image != null && image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32)) {
                    int[] pixels = new int[64 * 64];
                    image.getRGB(0, 0, 64, image.getHeight(), pixels, 0, 64);
                    byUrl.put(url, pixels);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Head skin " + url + " could not be downloaded: " + e);
            } finally {
                fetching.remove(url);
            }
        });
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        fetch(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        skins.remove(e.getPlayer().getUniqueId());
    }

    private void fetch(Player player) {
        if (!download) return;
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL url = textures.getSkin();
        if (url == null) return;
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage image = ImageIO.read(url);
                if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) return;
                int[] pixels = new int[64 * 64];
                image.getRGB(0, 0, 64, image.getHeight(), pixels, 0, 64);
                if (image.getHeight() == 32) {
                    // old single-layer skins have no left limbs of their own: reuse the right ones
                    copy(pixels, 0, 16, 16, 48, 16, 16);
                    copy(pixels, 40, 16, 32, 48, 16, 16);
                }
                if (player.isOnline()) skins.put(id, new Skin(pixels, slim));
            } catch (Exception e) {
                plugin.getLogger().fine("Skin of " + id + " could not be downloaded: " + e);
            }
        });
    }

    private static void copy(int[] px, int fromX, int fromY, int toX, int toY, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) px[(toY + y) * 64 + toX + x] = px[(fromY + y) * 64 + fromX + x];
        }
    }

    /** A plain figure painted straight into the skin layout: hair, face with eyes, shirt, trousers, shoes. */
    private static int[] standIn() {
        int[] px = new int[64 * 64];
        int skin = 0xFFD8A27A, hair = 0xFF4A3220, shirt = 0xFF3A7BD5, trousers = 0xFF34346E, shoe = 0xFF4A4A4A;
        fill(px, 0, 0, 32, 16, hair);               // head: everything hair ...
        fill(px, 8, 8, 8, 8, skin);                 // ... except the face
        fill(px, 8, 8, 8, 2, hair);                 // fringe
        fill(px, 9, 12, 2, 1, 0xFFFFFFFF);
        fill(px, 13, 12, 2, 1, 0xFFFFFFFF);
        fill(px, 10, 12, 1, 1, 0xFF3A5FA0);
        fill(px, 13, 12, 1, 1, 0xFF3A5FA0);
        fill(px, 11, 14, 2, 1, 0xFF9A6A4A);         // mouth
        fill(px, 16, 16, 24, 16, shirt);            // body
        for (int[] arm : new int[][] {{40, 16}, {32, 48}}) {
            fill(px, arm[0], arm[1], 16, 16, skin);
            fill(px, arm[0], arm[1], 16, 10, shirt);        // sleeve: the cap and the upper 6 px of the sides
        }
        for (int[] leg : new int[][] {{0, 16}, {16, 48}}) {
            fill(px, leg[0], leg[1], 16, 16, trousers);
            fill(px, leg[0], leg[1] + 13, 16, 3, shoe);
        }
        return px;
    }

    private static void fill(int[] px, int x0, int y0, int w, int h, int argb) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) px[y * 64 + x] = argb;
        }
    }
}
