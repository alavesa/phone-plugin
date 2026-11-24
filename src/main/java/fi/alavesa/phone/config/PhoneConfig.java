package fi.alavesa.phone.config;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml. Invalid values fall back to the default with a console warning. */
public final class PhoneConfig {

    public enum Hint { ACTIONBAR, CHAT, NONE }

    public enum ZoomMethod { POST_EFFECT, FOV }

    public enum PackMode { AUTO, SAME_PORT, BUILTIN, URL, NONE }

    public final String language;

    public final Material itemMaterial;
    public final boolean useItemModel;
    public final boolean recipeEnabled;
    public final List<String> recipeShape;
    public final Map<Character, Material> recipeIngredients;

    public final boolean screenEffect;
    public final boolean playerHeads;
    public final ZoneId zone;
    public final DateTimeFormatter timeFormat;
    public final DateTimeFormatter dateFormat;
    public final int messagesPerPage;
    public final int maxLinesPerPage;
    public final int contactsPerPage;

    public final int maxLength;
    public final int rateMessages;
    public final int rateSeconds;
    public final Hint hint;
    public final boolean autoRefresh;
    public final int refreshDelayTicks;
    public final boolean showOfflineContacts;
    public final int retentionDays;

    public final boolean cameraEnabled;
    public final int cooldownSeconds;
    public final double baseFov;
    public final List<Integer> zoomLevels;
    public final ZoomMethod zoomMethod;
    public final int maxDistance;
    public final int chunksPerTick;
    public final int maxChunks;
    public final boolean renderEntities;
    public final boolean dither;
    public final boolean requirePhotoPaper;
    public final boolean locationInLore;
    public final boolean dropWhenFull;

    public final String modelNormal;
    public final String modelUnread;
    public final String modelCamera;

    public final boolean passcodeEnabled;
    public final int passcodeAttempts;
    public final int passcodeLockoutSeconds;

    public final int scrollStep;
    public final int editableMessages;
    public final boolean allowEdit;
    public final boolean allowDelete;
    public final int editWindowMinutes;

    public final boolean playerSkins;
    public final boolean texturesEnabled;
    public final boolean texturesDownload;
    public final List<String> texturesExtraPacks;
    public final boolean videoEnabled;
    public final int videoFps;
    public final int videoMaxSeconds;
    public final int videoMaxDistance;

    public final int photosLimit;
    public final int playbackFps;

    public final boolean gramEnabled;
    public final int gramCaptionLength;
    public final int gramCommentLength;
    public final int gramBioLength;
    public final int gramCommentsPerMinute;
    public final int gramPostCooldownSeconds;
    public final boolean gramAutoplay;
    public final boolean gramNotify;

    public final boolean packSendOnJoin;
    public final PackMode packMode;
    public final String packUrl;
    public final boolean packRequired;
    public final String packPrompt;
    public final String packBind;
    public final int packPort;
    public final String packPublicHost;
    public final boolean packAssumeLoaded;

    public PhoneConfig(FileConfiguration c, List<Integer> packZoomLevels, Logger log) {
        language = c.getString("language", "en");

        itemMaterial = material(c.getString("item.material"), Material.ECHO_SHARD, "item.material", log);
        useItemModel = c.getBoolean("item.use-item-model", true);
        recipeEnabled = c.getBoolean("item.recipe.enabled", false);
        recipeShape = c.getStringList("item.recipe.shape");
        recipeIngredients = new LinkedHashMap<>();
        ConfigurationSection ing = c.getConfigurationSection("item.recipe.ingredients");
        if (ing != null) {
            for (String key : ing.getKeys(false)) {
                Material m = Material.matchMaterial(String.valueOf(ing.getString(key)));
                if (key.length() == 1 && m != null) recipeIngredients.put(key.charAt(0), m);
                else log.warning("config: item.recipe.ingredients." + key + " is not a single character with a valid material");
            }
        }

        screenEffect = c.getBoolean("ui.screen-effect", true);
        playerHeads = c.getBoolean("ui.player-heads", true);
        ZoneId z = ZoneId.systemDefault();
        String zoneName = c.getString("ui.time-zone", "system");
        if (!"system".equalsIgnoreCase(zoneName)) {
            try {
                z = ZoneId.of(zoneName);
            } catch (RuntimeException e) {
                log.warning("config: ui.time-zone '" + zoneName + "' is not a time zone id; using the system zone");
            }
        }
        zone = z;
        timeFormat = formatter(c.getString("ui.time-format"), "HH:mm", "ui.time-format", log);
        dateFormat = formatter(c.getString("ui.date-format"), "d MMM", "ui.date-format", log);
        messagesPerPage = clamp(c.getInt("ui.messages-per-page", 6), 1, 20);
        maxLinesPerPage = clamp(c.getInt("ui.max-lines-per-page", 16), 4, 60);
        contactsPerPage = clamp(c.getInt("ui.contacts-per-page", 5), 1, 12);

        maxLength = clamp(c.getInt("messaging.max-length", 160), 1, 500);
        rateMessages = clamp(c.getInt("messaging.rate-limit.messages", 5), 1, 1000);
        rateSeconds = clamp(c.getInt("messaging.rate-limit.per-seconds", 10), 1, 3600);
        hint = enumOf(Hint.class, c.getString("messaging.hint"), Hint.ACTIONBAR, "messaging.hint", log);
        autoRefresh = c.getBoolean("messaging.auto-refresh", true);
        refreshDelayTicks = clamp(c.getInt("messaging.refresh-delay-ticks", 20), 1, 200);
        showOfflineContacts = c.getBoolean("messaging.show-offline-contacts", true);
        retentionDays = Math.max(0, c.getInt("messaging.retention-days", 0));

        cameraEnabled = c.getBoolean("camera.enabled", true);
        cooldownSeconds = Math.max(0, c.getInt("camera.cooldown-seconds", 5));
        baseFov = Math.clamp(c.getDouble("camera.base-fov", 70), 30, 110);
        zoomMethod = enumOf(ZoomMethod.class, c.getString("camera.zoom-method"), ZoomMethod.POST_EFFECT, "camera.zoom-method", log);
        List<Integer> zooms = new ArrayList<>();
        for (int zoom : c.getIntegerList("camera.zoom-levels")) {
            if (zoom < 1 || zoom > 32 || zooms.contains(zoom)) continue;
            if (zoomMethod == ZoomMethod.POST_EFFECT && !packZoomLevels.contains(zoom)) {
                log.warning("config: camera.zoom-levels " + zoom + "x has no post effect in the pack (available: " + packZoomLevels + "); skipped");
                continue;
            }
            zooms.add(zoom);
        }
        if (zooms.isEmpty()) zooms.add(1);
        zoomLevels = List.copyOf(zooms);
        maxDistance = clamp(c.getInt("camera.max-distance", 96), 16, 256);
        chunksPerTick = clamp(c.getInt("camera.chunks-per-tick", 6), 1, 64);
        maxChunks = clamp(c.getInt("camera.max-chunks", 120), 4, 1024);
        renderEntities = c.getBoolean("camera.entities", true);
        dither = c.getBoolean("camera.dither", true);
        requirePhotoPaper = c.getBoolean("camera.require-photo-paper", false);
        locationInLore = c.getBoolean("camera.location-in-lore", true);
        dropWhenFull = c.getBoolean("camera.drop-when-full", true);

        modelNormal = modelKey(c.getString("item.models.normal"), "phone:phone", "item.models.normal", log);
        modelUnread = modelKey(c.getString("item.models.unread"), "phone:phone_unread", "item.models.unread", log);
        modelCamera = modelKey(c.getString("item.models.camera"), "phone:phone_camera", "item.models.camera", log);

        passcodeEnabled = c.getBoolean("passcode.enabled", true);
        passcodeAttempts = clamp(c.getInt("passcode.max-attempts", 5), 1, 100);
        passcodeLockoutSeconds = clamp(c.getInt("passcode.lockout-seconds", 30), 0, 86400);

        scrollStep = clamp(c.getInt("ui.scroll-step", 3), 1, 20);
        editableMessages = clamp(c.getInt("ui.editable-messages", 5), 1, 8);
        allowEdit = c.getBoolean("messaging.allow-edit", true);
        allowDelete = c.getBoolean("messaging.allow-delete", true);
        editWindowMinutes = Math.max(0, c.getInt("messaging.edit-window-minutes", 0));

        playerSkins = c.getBoolean("camera.player-skins", true);
        texturesEnabled = c.getBoolean("camera.textures.enabled", true);
        texturesDownload = c.getBoolean("camera.textures.download", true);
        texturesExtraPacks = List.copyOf(c.getStringList("camera.textures.extra-packs"));
        videoEnabled = c.getBoolean("camera.video.enabled", true);
        videoFps = clamp(c.getInt("camera.video.fps", 4), 1, 10);
        videoMaxSeconds = clamp(c.getInt("camera.video.max-seconds", 6), 1, 30);
        videoMaxDistance = clamp(c.getInt("camera.video.max-distance", 48), 16, 128);

        photosLimit = clamp(c.getInt("photos.max-per-player", 100), 1, 10_000);
        playbackFps = clamp(c.getInt("photos.playback-fps", 4), 1, 10);

        gramEnabled = c.getBoolean("minestagram.enabled", true);
        gramCaptionLength = clamp(c.getInt("minestagram.caption-length", 150), 1, 500);
        gramCommentLength = clamp(c.getInt("minestagram.comment-length", 120), 1, 500);
        gramBioLength = clamp(c.getInt("minestagram.bio-length", 60), 1, 200);
        gramCommentsPerMinute = clamp(c.getInt("minestagram.comments-per-minute", 6), 1, 1000);
        gramPostCooldownSeconds = clamp(c.getInt("minestagram.post-cooldown-seconds", 30), 0, 86_400);
        gramAutoplay = c.getBoolean("minestagram.autoplay-videos", true);
        gramNotify = c.getBoolean("minestagram.notify", true);

        packSendOnJoin = c.getBoolean("resource-pack.send-on-join", true);
        packMode = enumOf(PackMode.class, c.getString("resource-pack.mode"), PackMode.AUTO, "resource-pack.mode", log);
        packUrl = c.getString("resource-pack.url", "").trim();
        packRequired = c.getBoolean("resource-pack.required", false);
        packPrompt = c.getString("resource-pack.prompt", "");
        packBind = c.getString("resource-pack.builtin.bind", "0.0.0.0");
        packPort = clamp(c.getInt("resource-pack.builtin.port", 8765), 1, 65535);
        packPublicHost = c.getString("resource-pack.builtin.public-host", "").trim();
        packAssumeLoaded = c.getBoolean("resource-pack.assume-loaded", false);
    }

    private static String modelKey(String value, String def, String path, Logger log) {
        if (value == null || value.isBlank()) return def;
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            log.warning("config: " + path + " '" + value + "' is not a namespace:path id; using " + def);
            return def;
        }
        return value;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Material material(String name, Material def, String path, Logger log) {
        Material m = name == null ? null : Material.matchMaterial(name);
        if (m == null || !m.isItem() || m.isAir()) {
            log.warning("config: " + path + " '" + name + "' is not an item; using " + def);
            return def;
        }
        return m;
    }

    private static DateTimeFormatter formatter(String pattern, String def, String path, Logger log) {
        try {
            return DateTimeFormatter.ofPattern(pattern == null ? def : pattern, Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            log.warning("config: " + path + " '" + pattern + "' is not a date pattern; using " + def);
            return DateTimeFormatter.ofPattern(def, Locale.ENGLISH);
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E def, String path, Logger log) {
        if (name != null) {
            try {
                return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                log.warning("config: " + path + " '" + name + "' is not valid; using " + def.name().toLowerCase(Locale.ROOT));
            }
        }
        return def;
    }
}
