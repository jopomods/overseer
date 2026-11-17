package io.github.jopojp.overseer.core.provider;

import java.util.Objects;

/** One metric value for one resource at the snapshot's timestamp. */
public record Sample(ResourceRef resource, String metric, double value) {
    public Sample {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(metric, "metric");
    }
}
