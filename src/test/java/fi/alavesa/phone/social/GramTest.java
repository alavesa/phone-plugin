package fi.alavesa.phone.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GramTest {

    @Test
    void handles() {
        assertTrue(Gram.validHandle("glso"));
        assertTrue(Gram.validHandle("Pim_2.0"));
        assertFalse(Gram.validHandle("ab"));
        assertFalse(Gram.validHandle("seventeen_chars__"));
        assertFalse(Gram.validHandle("no spaces"));
        assertFalse(Gram.validHandle("abc"));
        assertFalse(Gram.validHandle(null));
    }

    @Test
    void suggestedHandlesAreValid() {
        for (String name : new String[] {"Pim", "a", "Very_Long_Minecraft_Name", "x-y"}) {
            String h = Gram.suggestHandle(name);
            assertTrue(Gram.validHandle(h), name + " -> " + h);
        }
        assertEquals("Pim", Gram.suggestHandle("Pim"));
    }
}
