package fi.alavesa.phone.resourcepack;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.storage.PlayerSettings;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

/**
 * Builds and delivers the resource pack and knows which players have it.
 *
 * <p><b>Building.</b> The pack shipped in the jar is the base. Files an admin puts in plugins/Phone/textures/
 * (phone.png, phone_unread.png, photo.png, photo_paper.png) replace the item textures, and anything in
 * plugins/Phone/pack/ replaces the file with the same path inside the pack. The result is written to
 * plugins/Phone/phone-pack.zip; its SHA-1 is computed from exactly those bytes.
 *
 * <p><b>Delivering.</b> "same-port" answers the client's download on the Minecraft port itself, so nothing else has
 * to be opened on the host; "builtin" is a small HTTP server on its own port; "url" points at a copy the admin
 * hosts; "auto" (default) is same-port and falls back to builtin. The pack is sent with {@code replace(false)}.
 *
 * <p><b>Who has it.</b> The client reports what happened to the pack this plugin sent. A player who got it another
 * way (merged server pack, installed by hand) is invisible to the server: {@code assume-loaded} or the player's own
 * {@code /phone ui fancy} covers those.
 */
public final class PackService implements Listener {

    private enum State { PENDING, LOADED, FAILED }

    /** Shortcut names in plugins/Phone/textures/ and the pack file each one replaces. */
    private static final String[] TEXTURES = {"phone", "phone_unread", "photo", "photo_paper"};
    private static final String ITEM_TEXTURES = "assets/phone/textures/item/";
    /** Fixed entry time: the same input always gives the same zip, and so the same SHA-1. */
    private static final long ZIP_TIME = 946_684_800_000L;

    private final Plugin plugin;
    private final PlayerSettings settings;
    private PhoneConfig cfg;
    private Lang lang;
    private final Map<UUID, State> states = new HashMap<>();
    private String sha1 = "";
    private UUID packId;
    private PackHttpServer http;
    private SamePortHttp samePort;
    /** What actually serves the pack: "same-port", "builtin", "url" or "none". */
    private String delivery = "none";

    public PackService(Plugin plugin, PlayerSettings settings, PhoneConfig cfg, Lang lang) {
        this.plugin = plugin;
        this.settings = settings;
        this.cfg = cfg;
        this.lang = lang;
    }

    // ---- building ---------------------------------------------------------------------------------------------------

    private File zipFile() {
        return new File(plugin.getDataFolder(), "phone-pack.zip");
    }

    /** Assembles the pack and writes it. Returns false when the jar carries no pack. */
    private boolean build() throws Exception {
        TreeMap<String, byte[]> files = new TreeMap<>();
        try (InputStream in = plugin.getResource("pack/phone-pack.zip")) {
            if (in == null) return false;
            try (ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (!e.isDirectory()) files.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        int replaced = 0;

        // 1. the simple way: plugins/Phone/textures/<name>.png
        File textures = new File(plugin.getDataFolder(), "textures");
        if (textures.mkdirs() || !new File(textures, "README.txt").exists()) writeTexturesReadme(textures, files);
        boolean customPhone = false;
        boolean customUnread = false;
        for (String name : TEXTURES) {
            File f = new File(textures, name + ".png");
            if (!f.isFile()) continue;
            byte[] png = Files.readAllBytes(f.toPath());
            if (ImageIO.read(new ByteArrayInputStream(png)) == null) {
                plugin.getLogger().warning("textures/" + f.getName() + " is not a readable PNG; ignored");
                continue;
            }
            files.put(ITEM_TEXTURES + name + ".png", png);
            replaced++;
            customPhone |= name.equals("phone");
            customUnread |= name.equals("phone_unread");
        }
        if (customPhone && !customUnread) {
            // only the phone was replaced: draw the red dot onto it so the unread state matches the new look
            files.put(ITEM_TEXTURES + "phone_unread.png", withDot(files.get(ITEM_TEXTURES + "phone.png")));
        }

        // 2. the general way: plugins/Phone/pack/<path inside the pack>
        Path overlay = new File(plugin.getDataFolder(), "pack").toPath();
        Files.createDirectories(overlay);
        try (Stream<Path> walk = Files.walk(overlay)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String name = overlay.relativize(p).toString().replace(File.separatorChar, '/');
                if (name.startsWith(".") || name.contains("/.")) continue;      // .DS_Store and friends
                files.put(name, Files.readAllBytes(p));
                replaced++;
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(ZIP_TIME);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        byte[] zip = bytes.toByteArray();
        Files.write(zipFile().toPath(), zip);
        sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        packId = UUID.nameUUIDFromBytes(("phone-pack:" + sha1).getBytes(StandardCharsets.UTF_8));
        Files.writeString(new File(plugin.getDataFolder(), "phone-pack.zip.sha1").toPath(), sha1 + "\n");
        if (replaced > 0) plugin.getLogger().info("Resource pack built with " + replaced + " file(s) from textures/ and pack/");
        return true;
    }

    private void writeTexturesReadme(File dir, Map<String, byte[]> base) throws IOException {
        Files.writeString(new File(dir, "README.txt").toPath(), """
                Put PNG files here to change how the phone's items look, then run /phone reload
                (players get the new pack at once).

                  phone.png          the phone
                  phone_unread.png   the phone with unread messages (optional: without it the red dot
                                     is drawn onto your phone.png automatically)
                  photo.png          inventory icon of a printed photo or video
                  photo_paper.png    photo paper

                Square images, 16x16 to 128x128. The files in the "default" folder are the built-in
                textures, as a starting point; they are only copies and are not read.

                To replace anything else in the pack (app icons, bubbles, sounds, shaders), put a file
                with the same path as inside phone-pack.zip into the "pack" folder next to this one,
                for example  pack/assets/phone/textures/font/apps.png
                """);
        File defaults = new File(dir, "default");
        if (defaults.mkdirs() || defaults.isDirectory()) {
            for (String name : TEXTURES) {
                byte[] png = base.get(ITEM_TEXTURES + name + ".png");
                if (png != null) Files.write(new File(defaults, name + ".png").toPath(), png);
            }
        }
    }

    /** The texture with a red notification dot in its top-right corner, sized to the image. */
    private static byte[] withDot(byte[] png) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(src, 0, 0, null);
        int size = Math.min(img.getWidth(), img.getHeight());
        double r = Math.max(1.5, size * 0.13);
        double cx = img.getWidth() - r - size * 0.12 - 0.5, cy = r + size * 0.06 - 0.5;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                double d = Math.hypot(x - cx, y - cy);
                if (d <= r - 1) img.setRGB(x, y, 0xFFE8282E);
                else if (d <= r + 0.3) img.setRGB(x, y, 0xFF780C12);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---- serving ----------------------------------------------------------------------------------------------------

    /** Builds the pack and starts the host. Blocks briefly (small files): called from onEnable. */
    public void start() {
        try {
            if (!build()) {
                plugin.getLogger().warning("The jar has no pack/phone-pack.zip; the resource pack will not be sent");
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not build the resource pack: " + e);
            return;
        }
        serve();
    }

    private void serve() {
        delivery = "none";
        PhoneConfig.PackMode mode = cfg.packMode;
        if (mode == PhoneConfig.PackMode.AUTO || mode == PhoneConfig.PackMode.SAME_PORT) {
            SamePortHttp hook = new SamePortHttp(Bukkit.getServer(), "phone", path -> zipFile());
            String problem = hook.install();
            if (problem == null) {
                samePort = hook;
                delivery = "same-port";
                plugin.getLogger().info("Resource pack served on the Minecraft port (" + Bukkit.getPort() + "), SHA-1 " + sha1
                        + ". Behind a Velocity/Bungee proxy this does not work: use mode builtin or url.");
            } else {
                plugin.getLogger().warning("Could not serve the resource pack on the Minecraft port (" + problem + ")"
                        + (mode == PhoneConfig.PackMode.AUTO ? "; using the builtin HTTP port instead" : ""));
                if (mode == PhoneConfig.PackMode.AUTO) mode = PhoneConfig.PackMode.BUILTIN;
            }
        }
        if (mode == PhoneConfig.PackMode.BUILTIN) {
            try {
                http = new PackHttpServer(cfg.packBind, cfg.packPort, Files.readAllBytes(zipFile().toPath()), sha1);
                delivery = "builtin";
                plugin.getLogger().info("Resource pack served on port " + cfg.packPort + " (" + http.path() + "), SHA-1 " + sha1
                        + ". That TCP port must be reachable by players.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not start the resource pack host on " + cfg.packBind + ":" + cfg.packPort + " (" + e
                        + "). Free the port, change resource-pack.builtin.port, or host plugins/Phone/phone-pack.zip yourself (mode: url).");
            }
        } else if (mode == PhoneConfig.PackMode.URL) {
            if (cfg.packUrl.isEmpty()) {
                plugin.getLogger().warning("resource-pack.mode is 'url' but resource-pack.url is empty. Upload plugins/Phone/phone-pack.zip"
                        + " to a web host that serves it directly and put the link in config.yml. SHA-1: " + sha1);
            } else {
                delivery = "url";
                plugin.getLogger().info("Resource pack: " + cfg.packUrl + " must serve plugins/Phone/phone-pack.zip (SHA-1 " + sha1
                        + "). Upload the file again after every plugin update or texture change.");
            }
        } else if (mode == PhoneConfig.PackMode.NONE) {
            plugin.getLogger().info("Resource pack is not sent (mode: none). The file is plugins/Phone/phone-pack.zip, SHA-1 " + sha1);
        }
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
        if (samePort != null) samePort.uninstall();
        samePort = null;
        delivery = "none";
    }

    /** /phone reload: new config, pack rebuilt from textures/ and pack/; a changed pack goes out to everyone online. */
    public void reload(PhoneConfig cfg, Lang lang) {
        this.cfg = cfg;
        this.lang = lang;
        String before = sha1;
        stop();
        start();
        if (!sha1.equals(before) && cfg.packSendOnJoin) {
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }
    }

    public String sha1() {
        return sha1;
    }

    public String delivery() {
        return delivery;
    }

    // ---- who has it -------------------------------------------------------------------------------------------------

    /**
     * Should this player get the textured UI, custom sounds and post effects? Their own choice (/phone ui) wins;
     * otherwise yes once their client reported the pack as loaded, and for players the pack was never sent to it
     * is whatever {@code assume-loaded} says.
     */
    public boolean hasPack(Player player) {
        String choice = settings.ui(player.getUniqueId());
        if ("fancy".equals(choice)) return true;
        if ("plain".equals(choice)) return false;
        State state = states.get(player.getUniqueId());
        return state == null ? cfg.packAssumeLoaded : state == State.LOADED || (cfg.packAssumeLoaded && state != State.FAILED);
    }

    /** One line for /phone ui: what the server knows about this player's pack. */
    public String describe(Player player) {
        State state = states.get(player.getUniqueId());
        return (state == null ? "not sent" : state.name().toLowerCase()) + ", delivery " + delivery;
    }

    private URI uriFor(Player player) {
        if (delivery.equals("url")) return URI.create(cfg.packUrl);
        if (delivery.equals("none")) return null;
        boolean same = delivery.equals("same-port");
        String host = cfg.packPublicHost;
        int port = same ? Bukkit.getPort() : cfg.packPort;
        // the address the player typed into the server list reaches this machine by definition
        InetSocketAddress virtual = player.getVirtualHost();
        if (host.isEmpty() && virtual != null) {
            host = virtual.getHostString();
            if (same && virtual.getPort() > 0) port = virtual.getPort();    // a host may map another outside port
        }
        if (host == null || host.isEmpty()) host = Bukkit.getIp().isEmpty() ? "127.0.0.1" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";   // IPv6 literal
        return URI.create("http://" + host + ":" + port + "/phone/" + sha1 + ".zip");
    }

    /** Sends the pack to one player. Returns false when there is nothing to send. */
    public boolean send(Player player) {
        URI uri;
        try {
            uri = uriFor(player);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("The resource pack address is not a valid URL: " + e.getMessage());
            return false;
        }
        if (uri == null || packId == null) return false;
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo().id(packId).uri(uri).hash(sha1).build())
                .replace(false)
                .required(cfg.packRequired);
        if (!cfg.packPrompt.isEmpty()) request.prompt(Lang.parse(cfg.packPrompt));
        states.put(player.getUniqueId(), State.PENDING);
        player.sendResourcePacks(request.build());
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg.packSendOnJoin) return;
        Player player = e.getPlayer();
        // a moment after join, so the pack screen does not fight the loading screen
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) send(player);
        }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (packId == null || !packId.equals(e.getID())) return;
        Player player = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> states.put(player.getUniqueId(), State.LOADED);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                states.put(player.getUniqueId(), State.FAILED);
                boolean declined = e.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED;
                // say it out loud: a silent fallback to the plain screens looks like a broken plugin
                player.sendMessage(lang.get(declined ? "pack.declined" : "pack.failed"));
                if (!declined) {
                    plugin.getLogger().warning(player.getName() + " could not load the phone resource pack (" + e.getStatus()
                            + ", delivery " + delivery + "). They get the plain-text phone. Check that the address players"
                            + " join with also reaches the pack (resource-pack in config.yml).");
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }
}
