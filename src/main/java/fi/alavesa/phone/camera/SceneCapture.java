package fi.alavesa.phone.camera;

import fi.alavesa.phone.camera.assets.Models;
import fi.alavesa.phone.camera.assets.Texture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Transformation;

/**
 * Main thread: copies everything visible besides blocks into a {@link Scene}. Players and simple mobs become
 * {@link Figure}s; display entities (block, item, text), items in players' hands and heads on armour stands become
 * {@link Prop}s; every other creature a two-colour box. Ragdolls and other contraptions made of display entities or
 * armour stands therefore show as what they are made of, in the pose they have at the moment of the picture.
 */
final class SceneCapture implements Listener {

    private static final int MAX_CREATURES = 64;
    private static final int MAX_PROPS = 384;
    /** One text pixel of a text display, in blocks (vanilla scale). */
    private static final double TEXT_PIXEL = 0.025;

    private final SkinCache skins;
    /** Tick of each player's last arm swing. */
    private final Map<UUID, Integer> swings = new HashMap<>();
    private final Map<String, Models.Model> heads = new HashMap<>();

    SceneCapture(SkinCache skins) {
        this.skins = skins;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent e) {
        swings.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        swings.remove(e.getPlayer().getUniqueId());
    }

    /** 0..1 progress of the arm swing under way (a swing takes 6 ticks), -1 for none. */
    private double swing(Player p) {
        Integer at = swings.get(p.getUniqueId());
        if (at == null) return -1;
        int age = Bukkit.getCurrentTick() - at;
        return age >= 0 && age < 6 ? (age + 0.5) / 6.0 : -1;
    }

    void capture(Player photographer, Location eye, Scene scene, boolean includeSelf, int distance) {
        Models models = scene.models;
        List<Entity> near = new ArrayList<>(eye.getWorld().getNearbyEntities(eye, distance, distance, distance,
                e -> (includeSelf || e != photographer)
                        && !(e instanceof Player p && p != photographer && (p.getGameMode() == GameMode.SPECTATOR || !photographer.canSee(p)))));
        near.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(eye)));
        int creatures = 0;
        for (Entity e : near) {
            if (scene.props.size() >= MAX_PROPS) break;
            if (e instanceof Display display) {
                if (models != null) display(display, eye, scene, models);
                continue;
            }
            if (e instanceof ArmorStand stand) {
                if (models != null) armorStand(stand, scene, models);
                if (!stand.isInvisible() && !stand.isMarker() && creatures++ < MAX_CREATURES) box(stand, scene, 0xB08A5A, 0xB08A5A);
                continue;
            }
            if (!(e instanceof LivingEntity living) || living.isInvisible() || creatures >= MAX_CREATURES) continue;
            creatures++;
            if (e instanceof Player p) {
                // players get the real model: skin, second layer, pose, limb swing, what they hold
                Figure f = Figure.of(p, skins.pixels(p), skins.slim(p), swing(p));
                scene.figures.add(f);
                if (models != null) {
                    held(p.getInventory().getItemInMainHand(), f.rightHand, f.rightArm, light(p), scene, models);
                    held(p.getInventory().getItemInOffHand(), f.leftHand, f.leftArm, light(p), scene, models);
                }
                continue;
            }
            String texture = Figure.textureOf(e);
            Texture t = models == null || texture == null ? null : models.texture(texture);
            if (t != null) {
                Figure f = Figure.mob(living, t);
                scene.figures.add(f);
                if (f.rightHand != null) {
                    var equipment = living.getEquipment();
                    if (equipment != null) held(equipment.getItemInMainHand(), f.rightHand, f.rightArm, light(e), scene, models);
                }
                continue;
            }
            int[] c = colours(e);
            box(e, scene, c[0], c[1]);
        }
    }

    private static double light(Entity e) {
        return e.getLocation().getBlock().getLightLevel() / 15.0;
    }

    private static void box(Entity e, Scene scene, int body, int head) {
        BoundingBox b = e.getBoundingBox();
        scene.entities.add(new Scene.Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ(), body, head,
                b.getMinY() + b.getHeight() * 0.75));
    }

    // ---- display entities -------------------------------------------------------------------------------------------

    /** The rotation a display entity is drawn with: its yaw and pitch, or turned to the camera for billboards. */
    private static double[][] orientation(Display d, Location eye) {
        Location at = d.getLocation();
        double yaw = Math.toRadians(at.getYaw()), pitch = Math.toRadians(at.getPitch());
        double dx = eye.getX() - at.getX(), dy = eye.getY() - at.getY(), dz = eye.getZ() - at.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        return switch (d.getBillboard()) {
            case FIXED -> Models.mul(Models.rotY(-yaw), Models.rotX(pitch));
            case VERTICAL -> Models.rotY(Math.atan2(dx, dz));
            case HORIZONTAL -> Models.mul(Models.rotY(-yaw), Models.rotX(-Math.atan2(dy, flat)));
            case CENTER -> Models.mul(Models.rotY(Math.atan2(dx, dz)), Models.rotX(-Math.atan2(dy, flat)));
        };
    }

    private static double[][] matrix(Transformation t) {
        var l = t.getLeftRotation();
        var r = t.getRightRotation();
        var s = t.getScale();
        return Models.mul(Models.mul(Prop.quaternion(l.x, l.y, l.z, l.w), Prop.scale(s.x, s.y, s.z)), Prop.quaternion(r.x, r.y, r.z, r.w));
    }

    private void display(Display d, Location eye, Scene scene, Models models) {
        Location at = d.getLocation();
        Transformation t = d.getTransformation();
        double[][] rot = orientation(d, eye);
        double[][] a = Models.mul(rot, matrix(t));
        double[] tr = Prop.apply(rot, t.getTranslation().x, t.getTranslation().y, t.getTranslation().z);
        double[] b = {at.getX() + tr[0], at.getY() + tr[1], at.getZ() + tr[2]};
        Display.Brightness brightness = d.getBrightness();
        double light = brightness != null ? Math.max(brightness.getBlockLight(), brightness.getSkyLight()) / 15.0 : light(d);
        boolean glow = brightness != null && brightness.getBlockLight() >= 15;
        switch (d) {
            case BlockDisplay block -> {
                List<Models.Placed> list = models.block(block.getBlock().getAsString());
                if (!list.isEmpty()) scene.props.add(new Prop(list, a, b, light, glow, -1));
            }
            case ItemDisplay item -> {
                // items are drawn centred on the entity
                double[] c = Prop.apply(a, 0.5, 0.5, 0.5);
                item(item.getItemStack(), a, new double[] {b[0] - c[0], b[1] - c[1], b[2] - c[2]}, light, glow, scene, models);
            }
            case TextDisplay text -> {
                Models.Model m = textModel(text, models);
                if (m != null) scene.props.add(new Prop(List.of(new Models.Placed(m, null)), a, b, light, true, -1));
            }
            default -> { }
        }
    }

    /** Adds an item drawn in model space [0,1]^3 transformed by a, b. */
    private void item(ItemStack stack, double[][] a, double[] b, double light, boolean glow, Scene scene, Models models) {
        if (stack == null || stack.getType().isAir()) return;
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey key = meta != null && meta.hasItemModel() ? meta.getItemModel() : stack.getType().getKey();
        if (key == null) return;
        Models.ItemLook look = models.item(key.toString());
        if (look.head() || stack.getType() == Material.PLAYER_HEAD) {
            Models.Model head = head(meta);
            if (head != null) scene.props.add(new Prop(List.of(new Models.Placed(head, null)), a, b, light, glow, -1));
            return;
        }
        if (look.model().isEmpty()) return;
        scene.props.add(new Prop(List.of(new Models.Placed(look.model(), null)), a, b, light, glow, tint(meta)));
    }

    /** Leather armour and potions colour their tinted layer. */
    private static int tint(ItemMeta meta) {
        Color c = null;
        if (meta instanceof LeatherArmorMeta leather) c = leather.getColor();
        else if (meta instanceof PotionMeta potion && potion.hasColor()) c = potion.getColor();
        return c == null ? -1 : c.asRGB();
    }

    /** An item in a hand: held out in front of the hand, at half size. */
    private void held(ItemStack stack, double[] hand, double[][] arm, double light, Scene scene, Models models) {
        if (stack == null || stack.getType().isAir() || hand == null || arm == null) return;
        double size = stack.getType().isBlock() ? 0.35 : 0.55;
        double[][] a = Models.mul(arm, Models.mul(Models.rotX(-Math.PI / 2), Prop.scale(size, size, size)));
        double[] c = Prop.apply(a, 0.5, 0.15, 0.5);
        item(stack, a, new double[] {hand[0] - c[0], hand[1] - c[1], hand[2] - c[2]}, light, false, scene, models);
    }

    // ---- armour stands ----------------------------------------------------------------------------------------------

    /** What an armour stand wears on its head (player heads and items), at its head pose. */
    private void armorStand(ArmorStand stand, Scene scene, Models models) {
        var equipment = stand.getEquipment();
        ItemStack helmet = equipment.getHelmet();
        if (helmet == null || helmet.getType().isAir()) return;
        Location at = stand.getLocation();
        double s = stand.isSmall() ? 0.5 : 1.0;
        EulerAngle pose = stand.getHeadPose();
        double[][] rot = Models.mul(Models.rotY(Math.toRadians(-at.getYaw())),
                Models.mul(Models.rotZ(-pose.getZ()), Models.mul(Models.rotY(-pose.getY()), Models.rotX(pose.getX()))));
        double k = 1.1875 * s;          // vanilla draws worn heads slightly larger than the head
        double[][] a = Models.mul(rot, Prop.scale(k, k, k));
        double[] neck = {at.getX(), at.getY() + 1.4 * s, at.getZ()};
        // the head's bottom sits on the neck: model point (0.5, 0, 0.5) goes to the neck
        double[] c = Prop.apply(a, 0.5, 0, 0.5);
        item(helmet, a, new double[] {neck[0] - c[0], neck[1] - c[1], neck[2] - c[2]}, light(stand), false, scene, models);
    }

    // ---- heads and text ---------------------------------------------------------------------------------------------

    /** A player head (8x8x8 + hat layer) from the skin in the item's profile, in model space x,z 0.25..0.75, y 0..0.5. */
    private Models.Model head(ItemMeta meta) {
        String url = null;
        if (meta instanceof SkullMeta skull && skull.getPlayerProfile() != null && skull.getPlayerProfile().getTextures().getSkin() != null) {
            url = skull.getPlayerProfile().getTextures().getSkin().toString();
        }
        int[] pixels = url == null ? null : skins.byUrl(url);
        String cacheKey = url == null || pixels == null ? "steve" : url;
        Models.Model cached = heads.get(cacheKey);
        if (cached != null) return cached;
        Texture skin = new Texture(64, 64, pixels == null ? skins.standInPixels() : pixels);
        Models.Model m = new Models.Model(List.of(headBox(skin, 0, 0), headBox(skin, 32, 0.5 / 16)));
        if (pixels != null || url == null) heads.put(cacheKey, m);
        return m;
    }

    private static Models.Element headBox(Texture skin, int u, double grow) {
        double[] from = {0.25 - grow, -grow, 0.25 - grow};
        double[] to = {0.75 + grow, 0.5 + grow, 0.75 + grow};
        Models.Face[] faces = new Models.Face[6];
        faces[0] = face(skin, u + 16, 0, u + 24, 8);       // bottom
        faces[1] = face(skin, u + 8, 0, u + 16, 8);        // top
        faces[2] = face(skin, u + 24, 8, u + 32, 16);      // north: back of the head
        faces[3] = face(skin, u + 8, 8, u + 16, 16);       // south: the face
        faces[4] = face(skin, u, 8, u + 8, 16);            // west: right side
        faces[5] = face(skin, u + 16, 8, u + 24, 16);      // east: left side
        return new Models.Element(from, to, faces, null, null, true);
    }

    private static Models.Face face(Texture t, int u0, int v0, int u1, int v1) {
        return new Models.Face(t, u0 / 64.0, v0 / 64.0, u1 / 64.0, v1 / 64.0, 0, -1);
    }

    /**
     * The text of a text display drawn with the vanilla ASCII font onto its background, as a flat element facing
     * +z (both sides drawn). Characters outside the font's table show as '?'.
     */
    private Models.Model textModel(TextDisplay d, Models models) {
        Texture font = models.texture("minecraft:font/ascii");
        if (font == null) return null;
        Component text = d.text();
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        TextColor colour = text.color();
        int ink = 0xFF000000 | (colour == null ? 0xFFFFFF : colour.value());
        String[] lines = plain.split("\n", -1);
        int cell = font.width / 16;
        int[] widths = new int[256];
        for (int c = 0; c < 256; c++) widths[c] = c == ' ' ? 4 : glyphWidth(font, c, cell) + 1;
        int w = 0;
        for (String l : lines) {
            int lw = 0;
            for (char ch : l.toCharArray()) lw += widths[ch < 256 ? ch : '?'];
            w = Math.max(w, lw);
        }
        int h = lines.length * 10 + 1;
        w += 2;
        if (w <= 2 && lines.length == 1 && lines[0].isEmpty()) return null;
        Color bg = d.getBackgroundColor();
        int background = d.isDefaultBackground() || bg == null ? 0x40000000 : bg.asARGB();
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, background);
        for (int li = 0; li < lines.length; li++) {
            int x = 1;
            for (char ch : lines[li].toCharArray()) {
                int c = ch < 256 ? ch : '?';
                int cx = (c % 16) * cell, cy = (c / 16) * cell;
                for (int gy = 0; gy < cell; gy++) {
                    for (int gx = 0; gx < cell; gx++) {
                        int src = font.argb[(cy + gy) * font.width + cx + gx];
                        int tx = x + gx * 8 / cell, ty = 1 + li * 10 + gy * 8 / cell;
                        if ((src >>> 24) > 128 && tx < w && ty < h) px[ty * w + tx] = ink;
                    }
                }
                x += widths[c];
            }
        }
        Texture image = new Texture(w, h, px);
        double hw = w * TEXT_PIXEL / 2, top = h * TEXT_PIXEL;
        Models.Face[] faces = new Models.Face[6];
        faces[3] = new Models.Face(image, 0, 0, 1, 1, 0, -1);
        faces[2] = new Models.Face(image, 1, 0, 0, 1, 0, -1);
        return new Models.Model(List.of(new Models.Element(new double[] {-hw, 0, 0}, new double[] {hw, top, 0}, faces, null, null, false)));
    }

    private static int glyphWidth(Texture font, int c, int cell) {
        int cx = (c % 16) * cell, cy = (c / 16) * cell;
        for (int gx = cell - 1; gx >= 0; gx--) {
            for (int gy = 0; gy < cell; gy++) {
                if ((font.argb[(cy + gy) * font.width + cx + gx] >>> 24) > 128) return (gx + 1) * 8 / cell;
            }
        }
        return 0;
    }

    /** Body and head colour of an entity drawn as a box: a handful of recognisable mobs, sensible defaults for the rest. */
    private static int[] colours(Entity e) {
        return switch (e.getType()) {
            case VILLAGER, WANDERING_TRADER -> new int[] {0x7A4F35, 0xC68E6E};
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED -> new int[] {0x3C6E8F, 0x4F7F3A};
            case SKELETON, STRAY, BOGGED -> new int[] {0xBDBDBD, 0xD6D6D6};
            case CREEPER -> new int[] {0x4CAF50, 0x4CAF50};
            case ENDERMAN -> new int[] {0x161616, 0x161616};
            case SPIDER, CAVE_SPIDER -> new int[] {0x34302B, 0x34302B};
            case COW -> new int[] {0x4A3A2A, 0x4A3A2A};
            case PIG -> new int[] {0xF0A5A2, 0xF0A5A2};
            case SHEEP -> new int[] {0xEDEDED, 0xC9B8A4};
            case CHICKEN -> new int[] {0xF5F5F5, 0xF5F5F5};
            case IRON_GOLEM -> new int[] {0xD9D2C5, 0xD9D2C5};
            case PIGLIN, ZOMBIFIED_PIGLIN, PIGLIN_BRUTE -> new int[] {0x8B5A3C, 0xE3A08C};
            default -> e instanceof Monster ? new int[] {0x5A6B4A, 0x5A6B4A}
                    : e instanceof WaterMob ? new int[] {0x6FA8C8, 0x6FA8C8} : new int[] {0x9A7B5A, 0x9A7B5A};
        };
    }
}
