package fi.alavesa.phone.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Per-player post effects from the pack (assets/phone/post_effect/&lt;name&gt;.json) through Paper's
 * {@code Player.postEffects()}, the API behind the vanilla 26.3 {@code /posteffect} command. Other plugins share
 * that list, so this class only adds and removes the ids it added itself and never clears the list.
 */
public final class PostFx {

    /** What we added, per player, so it can be taken back exactly. */
    private final Map<UUID, Set<Key>> applied = new HashMap<>();

    public void set(Player player, Key effect, boolean on) {
        Set<Key> mine = applied.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (on) {
            if (mine.add(effect)) player.postEffects().add(effect);
        } else if (mine.remove(effect) && player.isOnline()) {
            player.postEffects().remove(effect);
        }
    }

    public void forget(Player player) {
        applied.remove(player.getUniqueId());
    }

    /** Removes every effect this plugin added, from everyone online. */
    public void clearAll() {
        for (var e : applied.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (Key effect : e.getValue()) p.postEffects().remove(effect);
        }
        applied.clear();
    }
}
