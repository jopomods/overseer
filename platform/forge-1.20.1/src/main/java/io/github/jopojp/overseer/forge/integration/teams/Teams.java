package io.github.jopojp.overseer.forge.integration.teams;

import java.util.Set;
import java.util.UUID;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who plays together with a player. Integrations use it to extend access to a player's team
 * (ADR-0011). Backed by FTB Teams when installed; otherwise nobody has a team.
 * Server thread only.
 */
public interface Teams {
    Teams NONE = player -> Set.of();

    /** The other members of the player's party team; empty without a team. */
    Set<UUID> teammates(UUID player);

    static Teams detect() {
        if (!ModList.get().isLoaded("ftbteams")) return NONE;
        try {
            return new FtbTeams();
        } catch (LinkageError | RuntimeException e) {
            LoggerFactory.getLogger(Teams.class).error("FTB Teams support failed to load; teams are ignored", e);
            return NONE;
        }
    }
}
