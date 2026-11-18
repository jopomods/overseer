package io.github.jopojp.overseer.forge.gametest;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.github.jopojp.overseer.core.Overseer;
import io.github.jopojp.overseer.forge.integration.teams.Teams;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Team lookup against real FTB Teams. Uses FTB internals to build a party (test only). */
@PrefixGameTestTemplate(false)
public final class TeamGameTests {
    private TeamGameTests() {}

    @GameTest(template = "empty", templateNamespace = "overseer")
    public static void ftbPartyMembersAreTeammates(GameTestHelper helper) throws Exception {
        // Fresh ids per run: the GameTest world may keep FTB data between runs.
        UUID owner = UUID.randomUUID();
        UUID mate = UUID.randomUUID();
        UUID loner = UUID.randomUUID();
        var level = helper.getLevel();
        var ownerPlayer = FakePlayerFactory.get(level, new GameProfile(owner, "OvOwner"));
        var matePlayer = FakePlayerFactory.get(level, new GameProfile(mate, "OvMate"));

        var manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
        manager.playerLoggedIn(ownerPlayer, owner, "OvOwner");
        manager.playerLoggedIn(matePlayer, mate, "OvMate");
        PartyTeam party = manager.createParty(ownerPlayer, "Overseer test " + owner.toString().substring(0, 8));
        party.invite(ownerPlayer, List.of(matePlayer.getGameProfile()));
        party.join(matePlayer);

        Teams teams = Teams.detect();
        helper.assertTrue(teams.teammates(owner).equals(Set.of(mate)), "owner's teammates: " + teams.teammates(owner));
        helper.assertTrue(teams.teammates(mate).equals(Set.of(owner)), "mate's teammates: " + teams.teammates(mate));
        helper.assertTrue(teams.teammates(loner).isEmpty(), "a player without a party has no teammates");
        helper.succeed();
    }
}
