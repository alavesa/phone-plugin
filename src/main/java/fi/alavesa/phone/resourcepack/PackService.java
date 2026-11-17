package fi.alavesa.phone.resourcepack;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
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
 * Delivers the resource pack and remembers who has it loaded.
 *
 * <p>The zip is built by Gradle (task packZip) and rides in the jar; on start it is written to
 * plugins/Phone/phone-pack.zip and its SHA-1 computed from those bytes, so the hash sent to clients always matches
 * the file. Mode "builtin" serves it from a small HTTP server; mode "url" expects the admin to host that same file.
 * The pack is sent with {@code replace(false)}: packs of the server and of other plugins stay.
 */
public final class PackService implements Listener {

    private final Plugin plugin;
    private PhoneConfig cfg;
    private final Set<UUID> loaded = new HashSet<>();
    private byte[] zip;
    private String sha1 = "";
    private UUID packId;
    private PackHttpServer http;

    public PackService(Plugin plugin, PhoneConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void reload(PhoneConfig cfg) {
        this.cfg = cfg;
    }

    /** Unpacks the zip and starts the host. Blocks briefly (one small file): called from onEnable. */
    public void start() {
        try (InputStream in = plugin.getResource("pack/phone-pack.zip")) {
            if (in == null) {
                plugin.getLogger().warning("The jar has no pack/phone-pack.zip; the resource pack will not be sent");
                return;
            }
            zip = in.readAllBytes();
            File out = new File(plugin.getDataFolder(), "phone-pack.zip");
            Files.write(out.toPath(), zip);
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
            packId = UUID.nameUUIDFromBytes(("phone-pack:" + sha1).getBytes(StandardCharsets.UTF_8));
            Files.writeString(new File(plugin.getDataFolder(), "phone-pack.zip.sha1").toPath(), sha1 + "\n");
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            plugin.getLogger().warning("Could not prepare the resource pack: " + e);
            return;
        }
        switch (cfg.packMode) {
            case BUILTIN -> {
                try {
                    http = new PackHttpServer(cfg.packBind, cfg.packPort, zip, sha1);
                    plugin.getLogger().info("Resource pack served on port " + cfg.packPort + " (" + http.path() + "), SHA-1 " + sha1);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not start the resource pack host on " + cfg.packBind + ":" + cfg.packPort + " (" + e
                            + "). Free the port, change resource-pack.builtin.port, or host plugins/Phone/phone-pack.zip yourself (mode: url).");
                }
            }
            case URL -> {
                if (cfg.packUrl.isEmpty()) {
                    plugin.getLogger().warning("resource-pack.mode is 'url' but resource-pack.url is empty. Upload plugins/Phone/phone-pack.zip"
                            + " to a web host that serves it directly and put the link in config.yml. SHA-1: " + sha1);
                } else {
                    plugin.getLogger().info("Resource pack: " + cfg.packUrl + " must serve plugins/Phone/phone-pack.zip (SHA-1 " + sha1
                            + "). Upload the file again after every plugin update.");
                }
            }
            case NONE -> plugin.getLogger().info("Resource pack is not sent (mode: none). The file is plugins/Phone/phone-pack.zip, SHA-1 " + sha1);
        }
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
    }

    public String sha1() {
        return sha1;
    }

    /** True when this player's client reported the pack as loaded (or the config says to assume so). */
    public boolean hasPack(Player player) {
        return cfg.packAssumeLoaded || loaded.contains(player.getUniqueId());
    }

    private URI uriFor(Player player) {
        if (cfg.packMode == PhoneConfig.PackMode.URL) {
            return cfg.packUrl.isEmpty() ? null : URI.create(cfg.packUrl);
        }
        if (cfg.packMode != PhoneConfig.PackMode.BUILTIN || http == null) return null;
        String host = cfg.packPublicHost;
        if (host.isEmpty()) {
            // the address the player typed into the server list reaches this machine by definition
            InetSocketAddress virtual = player.getVirtualHost();
            if (virtual != null) host = virtual.getHostString();
        }
        if (host == null || host.isEmpty()) host = Bukkit.getIp().isEmpty() ? "127.0.0.1" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";   // IPv6 literal
        return URI.create("http://" + host + ":" + cfg.packPort + http.path());
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
        UUID id = e.getPlayer().getUniqueId();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(id);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                loaded.remove(id);
                if (e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
                    plugin.getLogger().warning(e.getPlayer().getName() + " could not download the resource pack. Check that the address"
                            + " in resource-pack (config.yml) is reachable from outside and that the port is open.");
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
    }
}
