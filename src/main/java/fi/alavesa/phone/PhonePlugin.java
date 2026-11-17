package fi.alavesa.phone;

import fi.alavesa.phone.camera.CameraService;
import fi.alavesa.phone.camera.PhotoStore;
import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import fi.alavesa.phone.messaging.MessageService;
import fi.alavesa.phone.phone.PhoneCommand;
import fi.alavesa.phone.phone.PhoneItems;
import fi.alavesa.phone.phone.PhoneListener;
import fi.alavesa.phone.resourcepack.PackService;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.ui.FontWidths;
import fi.alavesa.phone.ui.Glyphs;
import fi.alavesa.phone.ui.UiController;
import fi.alavesa.phone.util.PostFx;
import fi.alavesa.phone.util.Sounds;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires the parts together. Each part lives in its own package; see the README for the map. */
public final class PhonePlugin extends JavaPlugin {

    private static final NamespacedKey RECIPE = new NamespacedKey("phone", "phone");

    private PhoneConfig cfg;
    private Lang lang;
    private Glyphs glyphs;
    private Database database;
    private PackService pack;
    private PostFx postFx;
    private PhoneItems items;
    private MessageService messages;
    private PhotoStore photos;
    private CameraService camera;
    private UiController ui;
    private PhoneListener phoneListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FontWidths widths;
        try (InputStream g = getResource("font/glyphs.properties"); InputStream w = getResource("font/default_widths.tsv")) {
            glyphs = new Glyphs(g);
            widths = new FontWidths(w);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "The jar is missing its font tables; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cfg = new PhoneConfig(getConfig(), glyphs.zoomLevels, getLogger());
        lang = new Lang(this, cfg.language);

        database = new Database(getLogger());
        Map<java.util.UUID, Long> mutes;
        java.util.Set<Integer> photoIds;
        try {
            database.open(new File(getDataFolder(), "phone.db"));
            mutes = database.loadMutes();
            photoIds = database.loadPhotoIds();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open plugins/Phone/phone.db; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (cfg.retentionDays > 0) {
            database.purgeOlderThan(System.currentTimeMillis() - cfg.retentionDays * 86_400_000L).thenAccept(n -> {
                if (n > 0) getLogger().info("Deleted " + n + " messages older than " + cfg.retentionDays + " days");
            });
        }

        pack = new PackService(this, cfg);
        pack.start();
        postFx = new PostFx();
        Sounds sounds = new Sounds(pack::hasPack);
        items = new PhoneItems(cfg, lang);
        messages = new MessageService(this, database, cfg, mutes);
        photos = new PhotoStore(this, database, items, cfg, lang, photoIds);
        camera = new CameraService(this, database, items, pack, postFx, sounds, photos, cfg, lang);
        ui = new UiController(this, database, messages, items, pack, postFx, sounds, camera, glyphs, widths, cfg, lang);
        phoneListener = new PhoneListener(this, items, messages, ui, camera);
        messages.setNotifier(ui);
        camera.setResultScreen(ui);
        camera.setItemSync(phoneListener::sync);

        for (var listener : List.of(pack, photos, camera, ui, phoneListener)) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new PhoneCommand(this).build(), "Phone: open it, give it, moderate it"));
        registerRecipe();

        // players already online (the plugin was loaded late)
        for (Player p : Bukkit.getOnlinePlayers()) messages.join(p);
    }

    @Override
    public void onDisable() {
        if (ui != null) ui.closeAll();
        if (camera != null) camera.shutdown();
        if (postFx != null) postFx.clearAll();
        if (pack != null) pack.stop();
        Bukkit.removeRecipe(RECIPE);
        if (database != null) database.close();
    }

    /** /phone reload: config.yml and the language file. The pack host and the database keep running as they are. */
    public void reload() {
        reloadConfig();
        cfg = new PhoneConfig(getConfig(), glyphs.zoomLevels, getLogger());
        lang = new Lang(this, cfg.language);
        ui.closeAll();
        pack.reload(cfg);
        items.reload(cfg, lang);
        messages.reload(cfg);
        photos.reload(cfg, lang);
        camera.reload(cfg, lang);
        ui.reload(cfg, lang);
        registerRecipe();
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(RECIPE);
        if (!cfg.recipeEnabled) return;
        try {
            ShapedRecipe recipe = new ShapedRecipe(RECIPE, items.createPhone());
            recipe.shape(cfg.recipeShape.toArray(String[]::new));
            for (var e : cfg.recipeIngredients.entrySet()) {
                if (String.join("", cfg.recipeShape).indexOf(e.getKey()) >= 0) recipe.setIngredient(e.getKey(), e.getValue());
            }
            Bukkit.addRecipe(recipe);
        } catch (IllegalArgumentException e) {
            getLogger().warning("config: item.recipe is not a valid shaped recipe (" + e.getMessage() + "); the phone is not craftable");
        }
    }

    public Lang lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public PackService pack() {
        return pack;
    }

    public PhoneItems items() {
        return items;
    }

    public MessageService messages() {
        return messages;
    }

    public CameraService camera() {
        return camera;
    }

    public UiController ui() {
        return ui;
    }

    public PhoneListener phoneListener() {
        return phoneListener;
    }
}
