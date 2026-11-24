package fi.alavesa.phone.camera.assets;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.AnaloguePowerable;

/**
 * The colours vanilla multiplies into tinted faces (grass, leaves, water, redstone dust ...), from the grass and
 * foliage colour maps of the client assets and the biome's temperature. Rain ("downfall") is not part of the Bukkit
 * API, so it comes from a short table of biome families.
 */
public final class Tints {

    public static final int WATER = 0x3F76E4;

    private final Texture grass;
    private final Texture foliage;

    public Tints(Models models) {
        this.grass = models.texture("minecraft:colormap/grass");
        this.foliage = models.texture("minecraft:colormap/foliage");
    }

    private static double downfall(Biome biome) {
        String key = biome == null ? "" : biome.getKey().getKey();
        if (key.contains("jungle")) return 0.9;
        if (key.contains("mushroom")) return 1.0;
        if (key.contains("swamp") || key.contains("river") || key.contains("ocean") || key.contains("beach")) return 0.5;
        if (key.contains("forest") || key.contains("taiga") || key.contains("grove") || key.contains("cherry")) return 0.8;
        if (key.contains("desert") || key.contains("savanna") || key.contains("badlands") || key.contains("nether")) return 0.0;
        if (key.contains("snowy") || key.contains("frozen") || key.contains("ice")) return 0.5;
        if (key.contains("meadow")) return 0.8;
        return 0.4;
    }

    private static int map(Texture colormap, double temperature, double downfall, int fallback) {
        if (colormap == null) return fallback;
        double t = Math.clamp(temperature, 0.0, 1.0);
        double d = Math.clamp(downfall, 0.0, 1.0) * t;
        int x = (int) ((1 - t) * (colormap.width - 1));
        int y = (int) ((1 - d) * (colormap.height - 1));
        return colormap.argb[y * colormap.width + x] & 0xFFFFFF;
    }

    public int grass(Biome biome, double temperature) {
        String key = biome == null ? "" : biome.getKey().getKey();
        if (key.contains("swamp")) return 0x6A7039;
        if (key.contains("badlands")) return 0x90814D;
        int c = map(grass, temperature, downfall(biome), 0x91BD59);
        if (key.equals("dark_forest") || key.equals("pale_garden")) c = ((c & 0xFEFEFE) + 0x28340A) >> 1;
        return c;
    }

    public int foliage(Biome biome, double temperature) {
        String key = biome == null ? "" : biome.getKey().getKey();
        if (key.contains("swamp")) return 0x6A7039;
        if (key.contains("badlands")) return 0x9E814D;
        return map(foliage, temperature, downfall(biome), 0x77AB2F);
    }

    public static int water(Biome biome) {
        String key = biome == null ? "" : biome.getKey().getKey();
        if (key.contains("swamp")) return 0x617B64;
        if (key.contains("warm_ocean")) return 0x43D5EE;
        if (key.contains("lukewarm")) return 0x45ADF2;
        if (key.contains("cold_ocean")) return 0x3D57D6;
        if (key.contains("frozen")) return 0x3938C9;
        if (key.contains("mangrove")) return 0x3A7A6A;
        return WATER;
    }

    /** The tint of a tinted face of {@code data} at a place with this biome and temperature (0xRRGGBB). */
    public int block(BlockData data, Biome biome, double temperature) {
        Material m = data.getMaterial();
        String name = m.name().toLowerCase(Locale.ROOT);
        switch (m) {
            case BIRCH_LEAVES -> {
                return 0x80A755;
            }
            case SPRUCE_LEAVES -> {
                return 0x619961;
            }
            case LILY_PAD -> {
                return 0x208030;
            }
            case WATER, WATER_CAULDRON, BUBBLE_COLUMN -> {
                return water(biome);
            }
            case REDSTONE_WIRE -> {
                int power = data instanceof AnaloguePowerable p ? p.getPower() : 0;
                double f = power / 15.0;
                int r = (int) (255 * (f * 0.6 + (f > 0 ? 0.4 : 0.3)));
                int g = (int) (255 * Math.clamp(f * f * 0.7 - 0.5, 0, 1));
                int b = (int) (255 * Math.clamp(f * f * 0.6 - 0.7, 0, 1));
                return (r << 16) | (g << 8) | b;
            }
            case MELON_STEM, PUMPKIN_STEM -> {
                int age = data instanceof Ageable a ? a.getAge() : 0;
                return ((age * 32) << 16) | ((255 - age * 8) << 8) | (age * 4);
            }
            case ATTACHED_MELON_STEM, ATTACHED_PUMPKIN_STEM -> {
                return 0xE0C71C;
            }
            default -> { }
        }
        if (name.endsWith("_leaves") || name.equals("vine")) return foliage(biome, temperature);
        return grass(biome, temperature);
    }
}
