package io.github.jopojp.overseer.forge.gametest;

import com.mojang.authlib.GameProfile;
import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.flux.FluxMapping;
import io.github.jopojp.overseer.forge.integration.flux.FluxProvider;
import io.github.jopojp.overseer.forge.integration.server.ServerProvider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import sonar.fluxnetworks.api.network.SecurityLevel;
import sonar.fluxnetworks.common.connection.FluxNetwork;
import sonar.fluxnetworks.common.connection.FluxNetworkData;
import sonar.fluxnetworks.common.device.TileFluxDevice;

/**
 * Runs the providers against a real Forge server with real Flux Networks blocks.
 * {@code ./gradlew :platform:forge-1.20.1:runGameTestServer}
 */
@GameTestHolder(Overseer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FluxGameTests {
    private static final UUID OWNER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("0e1a5ee7-0000-4000-8000-0000000000b2");
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private FluxGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
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
            helper.assertTrue(snapshot.resources().containsKey(FluxMapping.member(network.getNetworkID(), OWNER)),
                    "owner membership missing");

            var policy = provider.access();
            helper.assertTrue(policy.canView(OWNER, networkRef), "owner must see the network");
            helper.assertFalse(policy.canView(STRANGER, networkRef), "stranger must not see a private network");

            FluxNetworkData.getInstance().deleteNetwork(network);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void serverProviderReportsTpsAndDimensions(GameTestHelper helper) {
        var snapshot = new ServerProvider(helper.getLevel().getServer()).collect(AT);
        helper.assertTrue(snapshot.resources().containsKey(new ResourceRef("server", "server", "main")), "server");
        helper.assertTrue(snapshot.resources().containsKey(
                new ResourceRef("server", "dimension", "minecraft:overworld")), "overworld");
        helper.assertTrue(snapshot.samples().stream().anyMatch(s -> s.metric().equals("tps")), "tps sample");
        helper.succeed();
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
