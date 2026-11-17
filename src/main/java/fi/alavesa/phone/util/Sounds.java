package fi.alavesa.phone.util;

import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/**
 * The phone's sounds: the custom sound events of the pack (assets/phone/sounds.json) for players who have it, a
 * vanilla sound for everyone else. Sounds are played to one player only.
 */
public final class Sounds {

    public enum Cue {
        NOTIFY("phone.notify", "block.note_block.chime", 1.4f),
        SEND("phone.send", "entity.experience_orb.pickup", 1.8f),
        SHUTTER("phone.shutter", "block.dispenser.dispense", 1.6f);

        final Key custom;
        final Key fallback;
        final float fallbackPitch;

        Cue(String custom, String fallback, float fallbackPitch) {
            this.custom = Key.key("phone", custom);
            this.fallback = Key.key("minecraft", fallback);
            this.fallbackPitch = fallbackPitch;
        }
    }

    private final Predicate<Player> hasPack;

    public Sounds(Predicate<Player> hasPack) {
        this.hasPack = hasPack;
    }

    public void play(Player player, Cue cue) {
        boolean custom = hasPack.test(player);
        player.playSound(Sound.sound(custom ? cue.custom : cue.fallback, Sound.Source.PLAYER, custom ? 1.0f : 0.7f,
                custom ? 1.0f : cue.fallbackPitch), Sound.Emitter.self());
    }
}
