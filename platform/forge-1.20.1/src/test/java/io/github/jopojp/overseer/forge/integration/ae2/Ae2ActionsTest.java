package io.github.jopojp.overseer.forge.integration.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.Invalid;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Actions.Mode;
import org.junit.jupiter.api.Test;

class Ae2ActionsTest {
    private static final String CPU = "minecraft:overworld@1,64,-3";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void actionsTargetGrids() {
        assertEquals("minecraft:overworld@1,2,3", Ae2Actions.gridId(Ae2Mapping.grid("minecraft:overworld@1,2,3")));
        assertThrows(Invalid.class, () -> Ae2Actions.gridId(Ae2Mapping.cpu("minecraft:overworld@1,2,3")));
        assertThrows(Invalid.class, () -> Ae2Actions.gridId(new ResourceRef("flux", "grid", "minecraft:overworld@1,2,3")));
        assertThrows(Invalid.class, () -> Ae2Actions.gridId(Ae2Mapping.grid("nowhere")));
    }

    @Test
    void cpusAreNamedByPositionOrIndex() {
        assertEquals(CPU, Ae2Actions.cpu(json("{\"cpu\":\"" + CPU + "\"}")));
        assertEquals(CPU + "#2", Ae2Actions.cpu(json("{\"cpu\":\"" + CPU + "#2\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpu(json("{}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpu(json("{\"cpu\":\"somewhere\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpu(json("{\"cpu\":5}")));
    }

    @Test
    void cpuEditAcceptsAnvilNamesAndModes() {
        var edit = Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"name\":\"  §5Circuits \",\"mode\":\"Player_Only\"}"));
        assertEquals("§5Circuits", edit.name());
        assertEquals(Mode.PLAYER_ONLY, edit.mode());
        assertEquals("", Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"name\":\"\"}")).name(), "empty removes the name");
        assertNull(Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"mode\":\"any\"}")).name());
        assertEquals("x".repeat(50), Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"name\":\"" + "x".repeat(50) + "\"}")).name());
        assertThrows(Invalid.class, () -> Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"name\":\"" + "x".repeat(51) + "\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"name\":\"a\nb\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\",\"mode\":\"fast\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpuEdit(json("{\"cpu\":\"" + CPU + "\"}")), "nothing to change");
    }

    @Test
    void suspendNeedsAFlag() {
        assertTrue(Ae2Actions.cpuSuspend(json("{\"cpu\":\"" + CPU + "\",\"suspended\":true}")).suspended());
        assertFalse(Ae2Actions.cpuSuspend(json("{\"cpu\":\"" + CPU + "\",\"suspended\":false}")).suspended());
        assertThrows(Invalid.class, () -> Ae2Actions.cpuSuspend(json("{\"cpu\":\"" + CPU + "\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.cpuSuspend(json("{\"cpu\":\"" + CPU + "\",\"suspended\":\"yes\"}")));
    }

    @Test
    void driveEditTakesAnIntPriority() {
        var edit = Ae2Actions.driveEdit(json("{\"drive\":\"minecraft:the_nether@0,5,0\",\"priority\":-20}"));
        assertEquals("minecraft:the_nether@0,5,0", edit.drive());
        assertEquals(-20, edit.priority());
        assertThrows(Invalid.class, () -> Ae2Actions.driveEdit(json("{\"drive\":\"minecraft:the_nether@0,5,0\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.driveEdit(json("{\"drive\":\"minecraft:the_nether@0,5,0\",\"priority\":1.5}")));
        assertThrows(Invalid.class,
                () -> Ae2Actions.driveEdit(json("{\"drive\":\"minecraft:the_nether@0,5,0\",\"priority\":3000000000}")));
        assertThrows(Invalid.class, () -> Ae2Actions.driveEdit(json("{\"drive\":\"x#1\",\"priority\":1}")));
    }
}
