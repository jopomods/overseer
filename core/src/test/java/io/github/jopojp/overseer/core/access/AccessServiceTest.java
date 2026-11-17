package io.github.jopojp.overseer.core.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.auth.PlayerRef;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.auth.Role;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final ResourceRef MINE = new ResourceRef("flux", "network", "1");
    private static final ResourceRef THEIRS = new ResourceRef("flux", "network", "2");

    private final FakeProvider flux = new FakeProvider("flux", 1, 1_000_000, new FakeTime())
            .resource(MINE, Map.of("name", "Base"))
            .resource(THEIRS, Map.of("name", "Neighbour"))
            .power(MINE, 10)
            .power(THEIRS, 20)
            .policy((player, ref) -> player.equals(ALEX) && ref.equals(MINE));
    private final AccessService access = new AccessService(List.of(flux));

    private static Principal as(Role role) {
        return new Principal(new PlayerRef(ALEX, "Alex"), role, 1, false);
    }

    @Test
    void playersSeeOnlyWhatTheModAllows() {
        var filtered = access.filter(as(Role.PLAYER), flux.collect(new Timestamp(Instant.EPOCH, 0, 1)));
        assertEquals(Map.of(MINE, Map.of("name", "Base")), filtered.resources());
        assertEquals(1, filtered.samples().size());
        assertEquals(MINE, filtered.samples().get(0).resource());
    }

    @Test
    void adminsSeeEverything() {
        assertTrue(access.canView(as(Role.ADMIN), THEIRS));
        assertTrue(access.canView(as(Role.SUPER_ADMIN), THEIRS));
    }

    @Test
    void unknownProvidersAreDeniedByDefault() {
        assertFalse(access.canView(as(Role.PLAYER), new ResourceRef("ae2", "grid", "x")));
    }

    @Test
    void throwingPolicyDenies() {
        var broken = new FakeProvider("broken", 1, 1, new FakeTime()).policy((p, r) -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(new AccessService(List.of(broken)).canView(as(Role.PLAYER), new ResourceRef("broken", "x", "1")));
    }
}
