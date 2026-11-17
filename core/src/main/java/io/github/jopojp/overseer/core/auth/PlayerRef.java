package io.github.jopojp.overseer.core.auth;

import java.util.Objects;
import java.util.UUID;

/** A Minecraft account: stable UUID plus the name it had when last seen. */
public record PlayerRef(UUID uuid, String name) {
    public PlayerRef {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
