package io.github.jopojp.overseer.forge.integration.teams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * FTB Teams (2001.x for 1.20.1). Only party teams count: every player also has a personal
 * team of their own, which adds nobody. Loaded only when FTB Teams is present.
 */
final class FtbTeams implements Teams {
    FtbTeams() {
        FTBTeamsAPI.api(); // fail fast here, not on the first collection
    }

    @Override
    public Set<UUID> teammates(UUID player) {
        var api = FTBTeamsAPI.api();
        if (player == null || !api.isManagerLoaded()) return Set.of();
        Team team = api.getManager().getTeamForPlayerID(player).orElse(null);
        if (team == null || !team.isPartyTeam()) return Set.of();
        var members = new HashSet<>(team.getMembers());
        members.remove(player);
        return Set.copyOf(members);
    }
}
