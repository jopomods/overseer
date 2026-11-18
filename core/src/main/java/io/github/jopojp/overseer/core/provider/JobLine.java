package io.github.jopojp.overseer.core.provider;

import java.util.Objects;

/**
 * One line of a running crafting job, as in AE2's crafting status screen (ADR-0011).
 *
 * @param key     stack key (see {@link Stack})
 * @param stored  already in the CPU
 * @param active  sent to machines, waiting for them to return
 * @param pending still to be crafted
 */
public record JobLine(String key, String kind, String name, long stored, long active, long pending) {
    public JobLine {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        if (name == null) name = key;
    }
}
