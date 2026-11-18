package io.github.jopojp.overseer.forge.gametest;

import appeng.api.features.IPlayerRegistry;
import appeng.blockentity.networking.ControllerBlockEntity;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Mapping;
import io.github.jopojp.overseer.forge.integration.ae2.Ae2Provider;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * AE2 and FTB Teams together (suite {@code ae2-teams}, ADR-0015): a party member of a grid's owner
 * sees the grid in Overseer, but cannot edit it; a stranger sees nothing. Uses FTB internals to
 * build a party (test only).
 */
@PrefixGameTestTemplate(false)
public final class Ae2TeamGameTests {
    private static final Timestamp AT = new Timestamp(Instant.EPOCH, 0, 1);

    private Ae2TeamGameTests() {}

    @GameTest(template = "empty", templateNamespace = "overseer", timeoutTicks = 200)
    public static void partyMembersSeeTheOwnersGrid(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID mate = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        var level = helper.getLevel();
        var ownerPlayer = FakePlayerFactory.get(level, new GameProfile(owner, "OvGridOwner"));
        var matePlayer = FakePlayerFactory.get(level, new GameProfile(mate, "OvGridMate"));
        var manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
        manager.playerLoggedIn(ownerPlayer, owner, "OvGridOwner");
        manager.playerLoggedIn(matePlayer, mate, "OvGridMate");
        PartyTeam party = manager.createParty(ownerPlayer, "Overseer grid " + owner.toString().substring(0, 8));
        party.invite(ownerPlayer, List.of(matePlayer.getGameProfile()));
        party.join(matePlayer);

        BlockPos controller = new BlockPos(1, 1, 1);
        place(helper, controller, "controller");
        place(helper, new BlockPos(1, 2, 1), "creative_energy_cell");
        if (helper.getBlockEntity(controller) instanceof ControllerBlockEntity c) {
            c.getMainNode().setOwningPlayerId(IPlayerRegistry.getMapping(level).getPlayerId(owner));
        }

        helper.runAfterDelay(40, () -> {
            var provider = new Ae2Provider(level.getServer(), Teams.detect(), Map::of);
            provider.collect(AT);
            BlockPos at = helper.absolutePos(controller);
            var ref = Ae2Mapping.grid(level.dimension().location() + "@" + at.getX() + "," + at.getY() + "," + at.getZ());
            var access = provider.access();
            helper.assertTrue(access.canView(owner, ref) && access.canEdit(owner, ref), "the owner sees and edits");
            helper.assertTrue(access.canView(mate, ref), "a party member sees the owner's grid");
            helper.assertFalse(access.canEdit(mate, ref), "but does not edit it");
            helper.assertFalse(access.canView(stranger, ref), "a stranger sees nothing");
            helper.succeed();
        });
    }

    private static void place(GameTestHelper helper, BlockPos relative, String block) {
        var ae2 = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryBuild("ae2", block));
        helper.assertTrue(ae2 != null, "unknown AE2 block " + block);
        helper.setBlock(relative, ae2);
    }
}
