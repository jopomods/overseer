package io.github.jopojp.overseer.core.auth;

import java.util.Objects;

/**
 * The authenticated caller of a request.
 *
 * @param player       the Minecraft account
 * @param role         global role at authentication time
 * @param credentialId id of the session or device token used
 * @param deviceToken  authenticated by a device token (read-only, cannot manage credentials)
 */
public record Principal(PlayerRef player, Role role, long credentialId, boolean deviceToken) {
    public Principal {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
    }

    public boolean isAdmin() {
        return role.isAdmin();
    }
}
