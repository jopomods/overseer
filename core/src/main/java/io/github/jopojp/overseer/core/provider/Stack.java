package io.github.jopojp.overseer.core.provider;

import java.util.Objects;

/**
 * One kind of thing in an inventory and how much of it there is (ADR-0011).
 *
 * @param key    stable id: {@code <kind>:<registry id>}, plus {@code #<hash>} of the NBT when present
 * @param kind   {@code item}, {@code fluid} or {@code other} (e.g. chemicals)
 * @param name   display name as the server resolves it (English); for display only
 * @param amount items, or mB for fluids
 */
public record Stack(String key, String kind, String name, long amount) {
    public Stack {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        if (name == null) name = key;
    }
}
