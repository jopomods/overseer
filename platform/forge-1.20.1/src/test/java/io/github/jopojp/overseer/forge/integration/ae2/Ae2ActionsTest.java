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

    @Test
    void providerEditTakesEverySettingAndAnIcon() {
        var edit = Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3/north\",\"name\":\"§6Assembler HV\","
                + "\"priority\":-3,\"blocking\":true,\"lock\":\"LOCK_UNTIL_RESULT\",\"visible\":false,\"icon\":\" gtceu:hv_assembler \"}"));
        assertEquals("minecraft:overworld@1,2,3/north", edit.provider());
        assertEquals("§6Assembler HV", edit.name());
        assertEquals(-3, edit.priority());
        assertTrue(edit.blocking());
        assertEquals(Ae2Actions.Lock.LOCK_UNTIL_RESULT, edit.lock());
        assertFalse(edit.visible());
        assertEquals("gtceu:hv_assembler", edit.icon());
        assertEquals("", Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3\",\"icon\":\"\"}")).icon(),
                "empty removes the icon");
        assertThrows(Invalid.class, () -> Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3/inside\",\"priority\":1}")));
        assertThrows(Invalid.class, () -> Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3\",\"icon\":\"Diamond\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3\",\"lock\":\"always\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.providerEdit(json("{\"provider\":\"minecraft:overworld@1,2,3\"}")), "nothing to change");
        assertThrows(Invalid.class, () -> Ae2Actions.providerEdit(json("{\"priority\":1}")));
    }

    @Test
    void craftingTakesAStackKeyAnAmountAndAPlan() {
        var plan = Ae2Actions.craftPlan(json("{\"key\":\"item:minecraft:iron_ingot#1a2b\",\"amount\":64}"));
        assertEquals("item:minecraft:iron_ingot#1a2b", plan.key());
        assertEquals(64, plan.amount());
        assertEquals("fluid:gtceu:oxygen", Ae2Actions.craftPlan(json("{\"key\":\"fluid:gtceu:oxygen\",\"amount\":1000}")).key());
        assertThrows(Invalid.class, () -> Ae2Actions.craftPlan(json("{\"key\":\"Iron Ingot\",\"amount\":1}")));
        assertThrows(Invalid.class, () -> Ae2Actions.craftPlan(json("{\"key\":\"item:minecraft:iron_ingot\",\"amount\":0}")));
        assertThrows(Invalid.class,
                () -> Ae2Actions.craftPlan(json("{\"key\":\"item:minecraft:iron_ingot\",\"amount\":1000000000001}")));
        assertThrows(Invalid.class, () -> Ae2Actions.craftPlan(json("{\"key\":\"item:minecraft:iron_ingot\"}")));

        var start = Ae2Actions.craftStart(json("{\"plan\":\"0123456789abcdef\",\"cpu\":\"minecraft:overworld@1,2,3\"}"));
        assertEquals("0123456789abcdef", start.plan());
        assertEquals("minecraft:overworld@1,2,3", start.cpu());
        assertNull(Ae2Actions.craftStart(json("{\"plan\":\"0123456789abcdef\"}")).cpu(), "AE2 chooses");
        assertThrows(Invalid.class, () -> Ae2Actions.craftStart(json("{\"plan\":\"nope\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.craftStart(json("{\"plan\":\"0123456789abcdef\",\"cpu\":\"x\"}")));
    }

    @Test
    void busEditReplacesTheFilterAndSetsModes() {
        var edit = Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"filter\":[\"item:minecraft:raw_iron\","
                + "\"fluid:minecraft:water\"],\"redstone\":\"HIGH_SIGNAL\",\"craftOnly\":true,\"scheduling\":\"roundrobin\",\"name\":\"Ore\"}"));
        assertEquals(java.util.List.of("item:minecraft:raw_iron", "fluid:minecraft:water"), edit.filter());
        assertEquals(Ae2Actions.Redstone.HIGH_SIGNAL, edit.redstone());
        assertEquals(Ae2Actions.Scheduling.ROUNDROBIN, edit.scheduling());
        assertTrue(edit.craftOnly());
        assertEquals(java.util.List.of(), Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"filter\":[]}")).filter(),
                "an empty list clears the filter");
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3\",\"redstone\":\"ignore\"}")),
                "buses are parts: a side is required");
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"filter\":["
                + "\"item:a:b\",\"item:a:c\",\"item:a:d\",\"item:a:e\",\"item:a:f\",\"item:a:g\",\"item:a:h\",\"item:a:i\",\"item:a:j\",\"item:a:k\"]}")));
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"filter\":[\"item:a:b\",\"item:a:b\"]}")));
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"filter\":\"item:a:b\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\",\"redstone\":\"loud\"}")));
        assertThrows(Invalid.class, () -> Ae2Actions.busEdit(json("{\"bus\":\"minecraft:overworld@1,2,3/east\"}")), "nothing to change");
    }
}
