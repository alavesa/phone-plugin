package fi.alavesa.phone.storage;

import fi.alavesa.phone.storage.Database.PlayerData;
import fi.alavesa.phone.util.Tasks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Per-player settings of online players, kept in memory and written through to the database: camera FOV, UI mode
 * and the phone passcode. Main thread only.
 *
 * <p>The passcode is stored as SHA-256(salt + code). With only 10 000 possible codes that does not stop someone
 * who has the database file from trying them all; it does keep the code out of plain sight in the file and in logs.
 */
public final class PlayerSettings implements Listener {

    private final Plugin plugin;
    private final Database db;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public PlayerSettings(Plugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
    }

    private void load(Player player) {
        UUID id = player.getUniqueId();
        db.rememberPlayer(id, player.getName());      // the row the settings live in
        Tasks.onMain(plugin, db.playerData(id), loaded -> {
            if (player.isOnline()) data.put(id, loaded);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    /** False for the first moments after joining, until the database has answered. */
    public boolean loaded(Player player) {
        return data.containsKey(player.getUniqueId());
    }

    private PlayerData get(UUID id) {
        return data.getOrDefault(id, PlayerData.EMPTY);
    }

    public Double fov(UUID id) {
        return get(id).fov();
    }

    public void setFov(UUID id, Double fov) {
        PlayerData d = get(id);
        data.put(id, new PlayerData(fov, d.ui(), d.passHash(), d.passSalt()));
        db.setFov(id, fov);
    }

    /** "fancy", "plain" or null (automatic). */
    public String ui(UUID id) {
        return get(id).ui();
    }

    public void setUi(UUID id, String ui) {
        PlayerData d = get(id);
        data.put(id, new PlayerData(d.fov(), ui, d.passHash(), d.passSalt()));
        db.setUi(id, ui);
    }

    public boolean hasPasscode(UUID id) {
        return get(id).passHash() != null;
    }

    public boolean checkPasscode(UUID id, String code) {
        PlayerData d = get(id);
        if (d.passHash() == null || code == null) return false;
        return MessageDigest.isEqual(hash(d.passSalt(), code).getBytes(StandardCharsets.US_ASCII),
                d.passHash().getBytes(StandardCharsets.US_ASCII));
    }

    /** @param code four digits, or null to remove the passcode */
    public void setPasscode(UUID id, String code) {
        PlayerData d = get(id);
        String salt = null;
        String hash = null;
        if (code != null) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            salt = HexFormat.of().formatHex(bytes);
            hash = hash(salt, code);
        }
        if (data.containsKey(id)) data.put(id, new PlayerData(d.fov(), d.ui(), hash, salt));
        db.setPasscode(id, hash, salt);
    }

    public static boolean validCode(String code) {
        return code != null && code.matches("[0-9]{4}");
    }

    private static String hash(String salt, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((salt + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);     // every JDK has SHA-256
        }
    }
}
