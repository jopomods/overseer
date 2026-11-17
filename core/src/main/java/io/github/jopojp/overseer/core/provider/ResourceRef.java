package io.github.jopojp.overseer.core.provider;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identity of something a provider observes, e.g. a Flux network or a Flux device
 * (ADR-0005). Identity never depends on display names: renaming a device must not split
 * its history. Names and zones are attributes recorded as events.
 *
 * @param provider provider id, e.g. {@code flux}
 * @param type     resource type within the provider, e.g. {@code network}, {@code device}
 * @param id       provider-defined stable id, e.g. a network id or {@code minecraft:overworld@12,64,-3}
 */
public record ResourceRef(String provider, String type, String id) {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");

    public ResourceRef {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (!KEY.matcher(provider).matches()) throw new IllegalArgumentException("invalid provider: " + provider);
        if (!KEY.matcher(type).matches()) throw new IllegalArgumentException("invalid type: " + type);
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    }

    /** Canonical string form {@code provider/type/id}, used as storage and API key. */
    public String key() {
        return provider + "/" + type + "/" + id;
    }
}
