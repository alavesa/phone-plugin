package fi.alavesa.phone.camera;

import fi.alavesa.phone.ui.Thumb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FiltersTest {

    private static int[] solid(int size, int rgb) {
        int[] a = new int[size * size];
        java.util.Arrays.fill(a, rgb);
        return a;
    }

    private static Filters.Filter filter(String id) {
        return Filters.ALL.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void numberZeroIsNoFilterAndTheRestWrapAround() {
        assertNull(Filters.at(0));
        assertEquals("mono", Filters.at(1).id());
        assertEquals("pixel", Filters.at(Filters.ALL.size()).id());
        assertNull(Filters.at(Filters.count()));
        assertEquals("pixel", Filters.at(-1).id());
        int[] same = solid(8, 0x336699);
        assertSame(same, Filters.apply(null, same, 8));
    }

    @Test
    void monoMakesEveryChannelEqual() {
        int[] out = Filters.apply(filter("mono"), solid(4, 0xFF3010), 4);
        int c = out[5];
        assertEquals(c >> 16 & 255, c >> 8 & 255);
        assertEquals(c >> 8 & 255, c & 255);
    }

    @Test
    void warmAddsRedAndTakesBlueAway() {
        int[] out = Filters.apply(filter("warm"), solid(4, 0x808080), 4);
        int c = out[5];
        assertTrue((c >> 16 & 255) > (c & 255));
    }

    @Test
    void pixelAveragesBlocksAndVignetteDarkensTheCorners() {
        int[] src = new int[16];
        for (int i = 0; i < 16; i++) src[i] = (i % 4 < 2) ? 0xFFFFFF : 0x000000;
        Filters.Filter pixel = filter("pixel");
        int[] out = Filters.apply(new Filters.Filter("p", 0, 0, 1, 1, 0, new double[] {1, 1, 1}, 0, 0, 2), src, 4);
        assertEquals(out[0], out[1]);
        assertEquals(out[0], out[4]);
        assertEquals(0xFFFFFF, out[0]);
        assertEquals(0x000000, out[2]);
        assertNotNull(pixel);
        int[] v = Filters.apply(filter("vignette"), solid(32, 0xFFFFFF), 32);
        assertTrue((v[0] & 255) < (v[16 * 32 + 16] & 255));
    }

    @Test
    void thumbnailAveragesTwoByTwoBlocks() {
        int[] src = new int[128 * 128];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) src[y * 128 + x] = (x % 2 == 0) ? 0xFF0000 : 0x0000FF;
        int[] t = Thumb.of(src);
        assertEquals(Thumb.SIZE * Thumb.SIZE, t.length);
        assertEquals(0x7F007F, t[0]);
    }
}
