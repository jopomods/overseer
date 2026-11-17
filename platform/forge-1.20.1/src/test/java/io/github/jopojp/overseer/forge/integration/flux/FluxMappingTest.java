package io.github.jopojp.overseer.forge.integration.flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Access;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Device;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.DeviceType;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Member;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Network;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Security;
import io.github.jopojp.overseer.forge.integration.flux.FluxModel.Stats;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FluxMappingTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID BANNED = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private static Device device(String name, DeviceType type, int x, long change) {
        return new Device("minecraft:overworld", x, 64, 0, type, name, OWNER, 0, 1000, 2000, false, false, true,
                false, 10, change);
    }

    private static final Network PRIVATE = new Network(1, "Base", 0xFF7A00, OWNER, Security.PRIVATE,
            new Stats(500, 300, 1000, 50_000, 12, 1, 1, 1, 0),
            List.of(new Member(OWNER, "Owner", Access.OWNER), new Member(GUEST, "Guest", Access.USER),
                    new Member(BANNED, "Banned", Access.BLOCKED)),
            List.of(device("Power plant: Diesel 1", DeviceType.PLUG, 1, 500),
                    device("Factory: Macerator", DeviceType.POINT, 2, -300),
                    device("", DeviceType.STORAGE, 3, 200)));

    private static final Network PUBLIC = new Network(2, "Town", 0x00FF00, GUEST, Security.PUBLIC,
            new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(new Member(BANNED, "Banned", Access.BLOCKED)), List.of());

    @Test
    void mapsNetworksMembersAndDevices() {
        var snapshot = FluxMapping.snapshot(AT, List.of(PRIVATE));
        var network = snapshot.resources().get(FluxMapping.network(1));
        assertEquals("Base", network.get("name"));
        assertEquals("#ff7a00", network.get("color"));
        assertEquals("private", network.get("security"));
        assertEquals("1", network.get("plugs"));

        var plug = snapshot.resources().get(new ResourceRef("flux", "device", "minecraft:overworld@1,64,0"));
        assertEquals("plug", plug.get("type"));
        assertEquals("1", plug.get("network"));
        assertEquals("Power plant: Diesel 1", plug.get("name"));

        assertEquals("owner", snapshot.resources().get(FluxMapping.member(1, OWNER)).get("access"));
        assertEquals(1 + 3 + 3, snapshot.resources().size());
        assertTrue(snapshot.samples().stream().anyMatch(s ->
                s.resource().equals(FluxMapping.network(1)) && s.metric().equals("energy_input") && s.value() == 500));
    }

    @Test
    void membersSeeTheirNetworkAndItsDevices() {
        var index = FluxMapping.AccessIndex.of(List.of(PRIVATE, PUBLIC));
        var plug = new ResourceRef("flux", "device", "minecraft:overworld@1,64,0");
        assertTrue(index.canView(OWNER, FluxMapping.network(1)));
        assertTrue(index.canView(GUEST, plug));
        assertTrue(index.canView(GUEST, FluxMapping.member(1, OWNER)));
    }

    @Test
    void strangersAndBlockedMembersSeeNothingPrivate() {
        var index = FluxMapping.AccessIndex.of(List.of(PRIVATE, PUBLIC));
        assertFalse(index.canView(STRANGER, FluxMapping.network(1)));
        assertFalse(index.canView(BANNED, FluxMapping.network(1)));
        assertFalse(index.canView(STRANGER, new ResourceRef("flux", "device", "minecraft:overworld@1,64,0")));
    }

    @Test
    void publicNetworksAreVisibleToEveryoneButBlockedMembers() {
        var index = FluxMapping.AccessIndex.of(List.of(PRIVATE, PUBLIC));
        assertTrue(index.canView(STRANGER, FluxMapping.network(2)));
        assertFalse(index.canView(BANNED, FluxMapping.network(2)));
    }

    @Test
    void unknownResourcesAreDenied() {
        var index = FluxMapping.AccessIndex.of(List.of(PRIVATE));
        assertFalse(index.canView(OWNER, FluxMapping.network(99)));
        assertFalse(index.canView(OWNER, new ResourceRef("flux", "device", "minecraft:the_end@0,0,0")));
        assertFalse(index.canView(OWNER, new ResourceRef("flux", "member", "garbage")));
        assertFalse(index.canView(OWNER, new ResourceRef("ae2", "network", "1")));
    }
}
