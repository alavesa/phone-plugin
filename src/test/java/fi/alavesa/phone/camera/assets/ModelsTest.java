package fi.alavesa.phone.camera.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelsTest {

    @TempDir
    Path dir;

    private void write(String path, String text) throws Exception {
        Path p = dir.resolve("assets/minecraft/" + path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private Models models() throws Exception {
        Path tex = dir.resolve("assets/minecraft/textures/block/thing.png");
        Files.createDirectories(tex.getParent());
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        ImageIO.write(img, "png", tex.toFile());
        write("models/block/cube.json", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#up"},"north":{"texture":"#side","tintindex":0}}}]}""");
        write("models/block/thing.json", """
                {"parent":"block/cube","textures":{"up":{"sprite":"minecraft:block/thing","force_translucent":true},"side":"#up"}}""");
        write("blockstates/thing.json", """
                {"variants":{"facing=north":{"model":"minecraft:block/thing"},"facing=east":{"model":"minecraft:block/thing","y":90}}}""");
        write("blockstates/wire.json", """
                {"multipart":[{"apply":{"model":"minecraft:block/thing"}},{"when":{"east":"side|up"},"apply":{"model":"minecraft:block/thing","x":90}}]}""");
        AssetSource source = new AssetSource(Logger.getLogger("test"));
        source.open(new File(dir.toFile(), "cache"), "none", false, List.of(dir.toFile()));
        return new Models(source);
    }

    @Test
    void resolvesParentsTexturesAndVariants() throws Exception {
        Models m = models();
        List<Models.Placed> north = m.block("minecraft:thing[facing=north]");
        assertEquals(1, north.size());
        assertNull(north.getFirst().rotation());
        Models.Element e = north.getFirst().model().elements().getFirst();
        assertNotNull(e.faces()[1], "up face (sprite object reference)");
        assertNotNull(e.faces()[2], "north face (#side -> #up -> sprite)");
        assertEquals(0, e.faces()[2].tint());
        assertEquals(0xFFFF0000, e.faces()[1].texture().argb[0]);
        assertEquals(1.0, e.to()[0], 1e-9);

        Models.Placed east = m.block("minecraft:thing[facing=east]").getFirst();
        assertNotNull(east.rotation());
        // y 90 turns north into east
        double[][] r = east.rotation();
        double[] north2 = {r[0][2] * -1, r[1][2] * -1, r[2][2] * -1};
        assertEquals(1, north2[0], 1e-9);
    }

    @Test
    void multipartAppliesMatchingParts() throws Exception {
        Models m = models();
        assertEquals(1, m.block("minecraft:wire[east=none]").size());
        assertEquals(2, m.block("minecraft:wire[east=up]").size());
        assertTrue(m.block("minecraft:missing").isEmpty());
    }
}
