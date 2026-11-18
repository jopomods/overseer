package io.github.jopojp.overseer.forge.gametest;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.action.ActionHandler;
import io.github.jopojp.overseer.core.action.ActionRequest;
import io.github.jopojp.overseer.core.action.ActionResult;
import io.github.jopojp.overseer.core.action.ActionResult.Status;
import io.github.jopojp.overseer.core.action.Actor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.flux.FluxActions;
import io.github.jopojp.overseer.forge.integration.flux.FluxMapping;
import io.github.jopojp.overseer.forge.integration.flux.FluxProvider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import sonar.fluxnetworks.api.network.AccessLevel;
import sonar.fluxnetworks.api.network.SecurityLevel;
import sonar.fluxnetworks.common.connection.FluxNetwork;
import sonar.fluxnetworks.common.connection.FluxNetworkData;
import sonar.fluxnetworks.common.device.TileFluxDevice;

/**
 * Runs the providers against a real Forge server with real Flux Networks blocks.
 * {@code ./gradlew :platform:forge-1.20.1:runGameTestServer}
 */
@PrefixGameTestTemplate(false)
public final class FluxGameTests {
    private static final UUID OWNER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000b2");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private FluxGameTests() {}

    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void fluxNetworkDevicesAndAccessAreCollected(GameTestHelper helper) {
        var owner = FakePlayerFactory.get(helper.getLevel(), new GameProfile(OWNER, "OverseerOwner"));
        FluxNetwork network = FluxNetworkData.getInstance()
                .createNetwork(owner, "GameTest Grid", 0xFF7A00, SecurityLevel.PRIVATE, "");
        helper.assertTrue(network != null, "Flux refused to create the network");

        BlockPos plug = new BlockPos(0, 1, 0);
        BlockPos point = new BlockPos(2, 1, 0);
        BlockPos storage = new BlockPos(4, 1, 0);
        connect(helper, plug, "flux_plug", network);
        connect(helper, point, "flux_point", network);
        connect(helper, storage, "basic_flux_storage", network);

        helper.runAfterDelay(20, () -> {
            var provider = new FluxProvider();
            ProviderSnapshot snapshot = provider.collect(AT);
            ResourceRef networkRef = FluxMapping.network(network.getNetworkID());

            Map<String, String> attributes = snapshot.resources().get(networkRef);
            helper.assertTrue(attributes != null, "network resource missing");
            helper.assertTrue("GameTest Grid".equals(attributes.get("name")), "network name");
            helper.assertTrue("private".equals(attributes.get("security")), "security level");

            long devices = snapshot.resources().keySet().stream()
                    .filter(r -> r.type().equals("device"))
                    .filter(r -> String.valueOf(network.getNetworkID()).equals(snapshot.resources().get(r).get("network")))
                    .count();
            helper.assertTrue(devices == 3, "expected 3 devices, got " + devices);
            BlockPos storageAt = helper.absolutePos(storage);
            String storageCapacity = snapshot.resources().entrySet().stream()
                    .filter(e -> e.getKey().type().equals("device") && e.getKey().id().endsWith(
                            "@" + storageAt.getX() + "," + storageAt.getY() + "," + storageAt.getZ()))
                    .map(e -> e.getValue().get("capacity")).findFirst().orElse(null);
            helper.assertTrue(storageCapacity != null && Long.parseLong(storageCapacity) > 0,
                    "storage capacity: " + storageCapacity);
            helper.assertTrue(snapshot.resources().containsKey(FluxMapping.member(network.getNetworkID(), OWNER)),
                    "owner membership missing");

            var policy = provider.access();
            helper.assertTrue(policy.canView(OWNER, networkRef), "owner must see the network");
            helper.assertFalse(policy.canView(STRANGER, networkRef), "stranger must not see a private network");

            FluxNetworkData.getInstance().deleteNetwork(network);
            helper.succeed();
        });
    }

    /** World actions (ADR-0016) through the provider, as the dashboard runs them. */
    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void fluxActionsChangeNetworksDevicesAndMembers(GameTestHelper helper) {
        var owner = FakePlayerFactory.get(helper.getLevel(), new GameProfile(OWNER, "OverseerOwner"));
        FluxNetwork network = FluxNetworkData.getInstance()
                .createNetwork(owner, "Actions Grid", 0xFF7A00, SecurityLevel.PRIVATE, "");
        FluxNetwork other = FluxNetworkData.getInstance()
                .createNetwork(owner, "Other Grid", 0x00FF00, SecurityLevel.PRIVATE, "");
        helper.assertTrue(network != null && other != null, "Flux refused to create the networks");
        BlockPos plug = new BlockPos(0, 1, 0);
        BlockPos point = new BlockPos(2, 1, 0);
        BlockPos storage = new BlockPos(4, 1, 0);
        connect(helper, plug, "flux_plug", network);
        connect(helper, point, "flux_point", network);
        connect(helper, storage, "basic_flux_storage", network);

        helper.runAfterDelay(5, () -> {
            var provider = new FluxProvider();
            provider.collect(AT);
            ActionHandler actions = provider.actions();
            ResourceRef ref = FluxMapping.network(network.getNetworkID());
            Actor ownerActor = new Actor(OWNER, "OverseerOwner", false);
            Actor stranger = new Actor(STRANGER, "OverseerStranger", false);

            helper.assertTrue(actions.canPerform(OWNER, ref, FluxActions.NETWORK_EDIT), "owner may edit");
            helper.assertFalse(actions.canPerform(STRANGER, ref, FluxActions.NETWORK_EDIT), "stranger may not edit");

            // Network settings, with Flux's own setters.
            var edited = run(actions, ownerActor, FluxActions.NETWORK_EDIT, ref,
                    "{\"name\":\"§aRenamed\",\"color\":\"#123456\",\"security\":\"encrypted\",\"password\":\"secret\"}");
            expect(helper, edited, Status.OK);
            helper.assertTrue("§aRenamed".equals(network.getNetworkName()), "name: " + network.getNetworkName());
            helper.assertTrue((network.getNetworkColor() & 0xFFFFFF) == 0x123456, "colour");
            helper.assertTrue(network.getSecurityLevel() == SecurityLevel.ENCRYPTED, "security");
            helper.assertTrue(edited.changes().size() == 4, "changes: " + edited.changes());
            expect(helper, run(actions, stranger, FluxActions.NETWORK_EDIT, ref, "{\"name\":\"Mine\"}"), Status.DENIED);
            expect(helper, run(actions, new Actor(STRANGER, "OverseerStranger", true), FluxActions.NETWORK_EDIT, ref,
                    "{\"name\":\"By an operator\"}"), Status.OK);
            helper.assertTrue("By an operator".equals(network.getNetworkName()), "operators may edit");

            // Devices: one bad entry (chunk loading on a storage) changes nothing at all.
            String plugId = id(helper, plug);
            String storageId = id(helper, storage);
            expect(helper, run(actions, ownerActor, FluxActions.DEVICE_EDIT, ref, "{\"devices\":["
                    + "{\"id\":\"" + plugId + "\",\"name\":\"Plug A\"},"
                    + "{\"id\":\"" + storageId + "\",\"chunkLoading\":true}]}"), Status.INVALID);
            var plugTile = (TileFluxDevice) helper.getBlockEntity(plug);
            helper.assertTrue(plugTile.getCustomName().isEmpty(), "nothing may change on an invalid batch");
            var devices = run(actions, ownerActor, FluxActions.DEVICE_EDIT, ref, "{\"devices\":["
                    + "{\"id\":\"" + plugId + "\",\"name\":\"Plug A\",\"priority\":42,\"limit\":1000,\"surge\":true},"
                    + "{\"id\":\"" + storageId + "\",\"disableLimit\":true}]}");
            expect(helper, devices, Status.OK);
            helper.assertTrue("Plug A".equals(plugTile.getCustomName()), "device name");
            helper.assertTrue(plugTile.getRawPriority() == 42, "priority");
            helper.assertTrue(plugTile.getRawLimit() == 1000, "limit");
            helper.assertTrue(plugTile.getSurgeMode(), "surge");
            helper.assertTrue(((TileFluxDevice) helper.getBlockEntity(storage)).getDisableLimit(), "bypass limit");
            helper.assertTrue(devices.changes().size() == 5, "changes: " + devices.changes());

            // Members: the stranger is offline and unknown to the server, named by the dashboard.
            expect(helper, run(actions, ownerActor, FluxActions.MEMBER_EDIT, ref,
                    "{\"player\":\"" + STRANGER + "\",\"name\":\"OverseerStranger\",\"role\":\"user\"}"), Status.OK);
            helper.assertTrue("OverseerStranger".equals(network.getMemberByUUID(STRANGER).getCachedName()), "member name");
            helper.assertTrue(network.getMemberByUUID(STRANGER) != null, "offline player added");
            var outsider = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "Outsider"));
            helper.assertTrue(network.canPlayerAccess(outsider, "secret"), "the password survives adding a member");
            expect(helper, run(actions, ownerActor, FluxActions.MEMBER_EDIT, ref,
                    "{\"player\":\"" + STRANGER + "\",\"role\":\"admin\"}"), Status.OK);
            helper.assertTrue(network.getMemberByUUID(STRANGER).getAccessLevel() == AccessLevel.ADMIN, "promoted");
            expect(helper, run(actions, stranger, FluxActions.MEMBER_EDIT, ref,
                    "{\"player\":\"" + STRANGER + "\",\"role\":\"owner\"}"), Status.DENIED);
            expect(helper, run(actions, ownerActor, FluxActions.MEMBER_EDIT, ref,
                    "{\"player\":\"" + STRANGER + "\",\"role\":\"remove\"}"), Status.OK);
            helper.assertTrue(network.getMemberByUUID(STRANGER) == null, "removed");
            expect(helper, run(actions, ownerActor, FluxActions.MEMBER_EDIT, ref,
                    "{\"name\":\"NobodyKnowsMe\",\"role\":\"user\"}"), Status.INVALID);

            // Moving a device to another network.
            var moved = run(actions, ownerActor, FluxActions.DEVICE_CONNECT, FluxMapping.network(other.getNetworkID()),
                    "{\"devices\":[\"" + id(helper, point) + "\"]}");
            expect(helper, moved, Status.OK);
            helper.assertTrue(((TileFluxDevice) helper.getBlockEntity(point)).getNetworkID() == other.getNetworkID(),
                    "device moved");

            FluxNetworkData.getInstance().deleteNetwork(network);
            FluxNetworkData.getInstance().deleteNetwork(other);
            helper.succeed();
        });
    }

    private static ActionResult run(ActionHandler actions, Actor actor, String type, ResourceRef target, String params) {
        return actions.perform(actor, new ActionRequest(type, target, JsonParser.parseString(params).getAsJsonObject()));
    }

    private static void expect(GameTestHelper helper, ActionResult result, Status status) {
        helper.assertTrue(result.status() == status, "expected " + status + ", got " + result);
    }

    private static String id(GameTestHelper helper, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        return helper.getLevel().dimension().location() + "@" + at.getX() + "," + at.getY() + "," + at.getZ();
    }

    private static void connect(GameTestHelper helper, BlockPos relative, String block, FluxNetwork network) {
        Block flux = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryBuild("fluxnetworks", block));
        helper.assertTrue(flux != null, "unknown Flux block " + block);
        helper.setBlock(relative, flux);
        if (!(helper.getBlockEntity(relative) instanceof TileFluxDevice device)) {
            helper.fail("no Flux device at " + relative);
            return;
        }
        device.setOwnerUUID(OWNER);
        helper.assertTrue(device.connect(network), "could not connect " + block);
    }
}
