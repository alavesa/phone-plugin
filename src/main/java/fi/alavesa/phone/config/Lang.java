package fi.alavesa.phone.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Player-facing text from lang/&lt;language&gt;.yml, MiniMessage. Keys missing from the server's file fall back to the
 * en.yml inside the jar, so an old language file keeps working after an update.
 */
public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final Map<String, String> text = new HashMap<>();

    public Lang(Plugin plugin, String language) {
        int bundled = 0;
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                bundled = yaml.getInt("version", 0);
                read(yaml);
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read the bundled lang/en.yml: " + e);
        }
        // the English file on disk follows the plugin: an outdated one is set aside, not mixed with new screens
        File english = new File(plugin.getDataFolder(), "lang/en.yml");
        if (english.exists()) {
            int onDisk = YamlConfiguration.loadConfiguration(english).getInt("version", 1);
            if (onDisk < bundled) {
                File backup = new File(plugin.getDataFolder(), "lang/en.yml.old-" + onDisk);
                if (english.renameTo(backup)) {
                    plugin.getLogger().info("lang/en.yml was from an older version: kept as " + backup.getName() + ", new texts written");
                }
            }
        }
        if (!english.exists() && plugin.getResource("lang/en.yml") != null) plugin.saveResource("lang/en.yml", false);
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (file.exists()) read(YamlConfiguration.loadConfiguration(file));
        else plugin.getLogger().warning("lang/" + language + ".yml not found; using English");
    }

    private void read(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) text.put(key, yaml.getString(key));
        }
    }

    /** The raw MiniMessage string, or the key itself when it is missing everywhere. */
    public String raw(String key) {
        return text.getOrDefault(key, key);
    }

    /**
     * A "ui.*" string as plain text: those are measured and coloured by the phone theme, so they carry no tags.
     * {@code pairs} are name, value, name, value ... and replace {@code <name>}.
     */
    public String plain(String key, Object... pairs) {
        String s = raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("<" + pairs[i] + ">", String.valueOf(pairs[i + 1]));
        }
        return s;
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    /** Parses an arbitrary MiniMessage string (config values). */
    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Placeholder whose value is inserted as plain text (never parsed: safe for player input). */
    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver ph(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
