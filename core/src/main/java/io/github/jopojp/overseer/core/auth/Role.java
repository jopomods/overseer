package io.github.jopojp.overseer.core.auth;

import java.util.Locale;

/** Global role (ADR-0008, layer 1). */
public enum Role {
    /** Regular player: sees what the mods allow. */
    PLAYER,
    /** Dashboard admin granted by an operator: sees everything. */
    ADMIN,
    /** Server operator or console: sees everything, manages admins. */
    SUPER_ADMIN;

    public boolean isAdmin() {
        return this != PLAYER;
    }

    /** Wire format, e.g. {@code super_admin}. */
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
