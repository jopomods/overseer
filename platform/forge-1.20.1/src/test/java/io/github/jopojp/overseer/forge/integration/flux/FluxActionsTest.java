package io.github.jopojp.overseer.forge.integration.flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.Invalid;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions.Role;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Security;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FluxActionsTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void networkEditAcceptsFluxNamesColoursAndSecurity() {
        var edit = FluxActions.networkEdit(json(
                "{\"name\":\"§aMain Grid\",\"color\":\"#FF7a00\",\"security\":\"Encrypted\",\"password\":\"secret\"}"));
        assertEquals("§aMain Grid", edit.name());
        assertEquals(0xFF7A00, edit.color());
        assertEquals(Security.ENCRYPTED, edit.security());
        assertEquals("secret", edit.password());
        assertEquals(0x00FF00, FluxActions.networkEdit(json("{\"color\":\"00ff00\"}")).color());
        assertNull(FluxActions.networkEdit(json("{\"color\":\"00ff00\"}")).name());
    }

    @Test
    void networkEditRefusesWhatFluxWouldCutOffOrRefuse() {
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"name\":\"" + "x".repeat(25) + "\"}")));
        assertEquals("x".repeat(24), FluxActions.networkEdit(json("{\"name\":\"" + "x".repeat(24) + "\"}")).name());
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"name\":\"  \"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"name\":\"a\\nb\"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"color\":\"orange\"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"security\":\"secret\"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"password\":\"" + "p".repeat(17) + "\"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"password\":\"\"}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{\"name\":7}")));
        assertThrows(Invalid.class, () -> FluxActions.networkEdit(json("{}")));
    }

    @Test
    void deviceEditsParseEachDevice() {
        var edits = FluxActions.deviceEdits(json("{\"devices\":["
                + "{\"id\":\"minecraft:overworld@1,64,-3\",\"name\":\"\",\"priority\":-9999,\"limit\":0,\"surge\":true},"
                + "{\"id\":\"minecraft:the_nether@-10,5,2\",\"disableLimit\":false,\"chunkLoading\":true}]}"));
        assertEquals(2, edits.size());
        var first = edits.get(0);
        assertEquals("minecraft:overworld@1,64,-3", first.pos().id());
        assertEquals("", first.name());
        assertEquals(-9999, first.priority());
        assertEquals(0L, first.limit());
        assertTrue(first.surge());
        assertNull(first.chunkLoading());
        assertEquals(-10, edits.get(1).pos().x());
        assertTrue(edits.get(1).chunkLoading());
    }

    @Test
    void deviceEditsRefuseWrongValues() {
        String id = "\"id\":\"minecraft:overworld@1,64,-3\"";
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[{" + id + "}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[{" + id + ",\"priority\":10000}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[{" + id + ",\"priority\":1.5}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[{" + id + ",\"limit\":-1}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json("{\"devices\":[{" + id + ",\"surge\":\"yes\"}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json(
                "{\"devices\":[{" + id + ",\"surge\":true},{" + id + ",\"surge\":false}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json(
                "{\"devices\":[{\"id\":\"overworld 1 2 3\",\"surge\":true}]}")));
        assertThrows(Invalid.class, () -> FluxActions.deviceEdits(json(
                "{\"devices\":[{" + id + ",\"name\":\"" + "n".repeat(25) + "\"}]}")));
    }

    @Test
    void connectListsDevicesOnce() {
        var devices = FluxActions.connect(json(
                "{\"devices\":[\"minecraft:overworld@1,2,3\",\"minecraft:overworld@1,2,3\",\"minecraft:overworld@4,5,6\"]}"));
        assertEquals(2, devices.size());
        assertThrows(Invalid.class, () -> FluxActions.connect(json("{\"devices\":[{\"id\":\"x\"}]}")));
    }

    @Test
    void memberEditTakesUuidOrName() {
        UUID uuid = UUID.randomUUID();
        var byUuid = FluxActions.memberEdit(json("{\"player\":\"" + uuid + "\",\"role\":\"admin\"}"));
        assertEquals(uuid, byUuid.player());
        assertNull(byUuid.name());
        assertEquals(Role.ADMIN, byUuid.role());
        var byName = FluxActions.memberEdit(json("{\"name\":\" Steve_2 \",\"role\":\"REMOVE\"}"));
        assertEquals("Steve_2", byName.name());
        assertEquals(Role.REMOVE, byName.role());

        assertThrows(Invalid.class, () -> FluxActions.memberEdit(json("{\"name\":\"Steve\"}")));
        assertThrows(Invalid.class, () -> FluxActions.memberEdit(json("{\"name\":\"Steve\",\"role\":\"king\"}")));
        assertThrows(Invalid.class, () -> FluxActions.memberEdit(json("{\"role\":\"user\"}")));
        var both = FluxActions.memberEdit(json("{\"name\":\"Steve\",\"player\":\"" + uuid + "\",\"role\":\"user\"}"));
        assertEquals(uuid, both.player());
        assertEquals("Steve", both.name());
        assertThrows(Invalid.class, () -> FluxActions.memberEdit(json("{\"name\":\"no spaces\",\"role\":\"user\"}")));
        assertThrows(Invalid.class, () -> FluxActions.memberEdit(json("{\"player\":\"nope\",\"role\":\"user\"}")));
    }

    @Test
    void actionsTargetNetworks() {
        assertEquals(7, FluxActions.networkId(new ResourceRef("flux", "network", "7")));
        assertThrows(Invalid.class, () -> FluxActions.networkId(new ResourceRef("flux", "device", "minecraft:overworld@1,2,3")));
        assertThrows(Invalid.class, () -> FluxActions.networkId(new ResourceRef("ae2", "network", "7")));
    }
}
