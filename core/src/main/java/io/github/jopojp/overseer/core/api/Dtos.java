package io.github.jopojp.overseer.core.api;

import io.github.jopojp.overseer.core.auth.AuthStore;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.pipeline.CollectorStatus;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON shapes of the API. Each record mirrors a schema in {@code api/openapi.yaml};
 * keep them in sync (the contract test checks the important ones).
 */
public final class Dtos {
    private Dtos() {}

    public record Health(String status, String version, Long dataAgeMillis) {}

    public record LinkCode(String code, Instant expiresAt) {}

    public record LinkCodeStatus(String status) {}

    public record Me(String uuid, String name, String role, boolean deviceToken) {}

    public record Credential(long id, String kind, String name, Instant createdAt, Instant lastUsedAt, boolean current) {}

    public record CreateDeviceToken(String name) {}

    public record CreatedDeviceToken(long id, String token) {}

    public record Provider(String id, String displayName, List<String> capabilities) {}

    public record TimestampDto(Instant wall, long uptimeMillis, int session) {}

    public record Ref(String provider, String type, String id, String key) {}

    public record Resource(Ref ref, Map<String, String> attributes, Map<String, Double> metrics) {}

    public record Snapshot(String provider, TimestampDto at, List<Resource> resources) {}

    public record Collector(
            String provider,
            int baseIntervalTicks,
            int currentIntervalTicks,
            long budgetNanos,
            long runs,
            long errors,
            long overruns,
            long lastNanos,
            long avgNanos,
            long maxNanos,
            boolean throttled,
            String lastError) {}

    public record Collectors(List<Collector> collectors, long droppedSnapshots, int queuedSnapshots) {}

    public static Me me(Principal principal) {
        return new Me(principal.player().uuid().toString(), principal.player().name(), principal.role().json(),
                principal.deviceToken());
    }

    public static Credential credential(AuthStore.Info info, long currentId) {
        return new Credential(info.id(), info.kind().json(), info.name(), info.createdAt(), info.lastUsedAt(),
                info.id() == currentId);
    }

    public static Provider provider(ProviderDescriptor descriptor) {
        return new Provider(descriptor.id(), descriptor.displayName(), descriptor.capabilities().stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList());
    }

    public static Snapshot snapshot(ProviderSnapshot snapshot) {
        Map<ResourceRef, Map<String, Double>> metrics = new LinkedHashMap<>();
        for (Sample sample : snapshot.samples()) {
            metrics.computeIfAbsent(sample.resource(), r -> new LinkedHashMap<>()).put(sample.metric(), sample.value());
        }
        var resources = new ArrayList<Resource>(snapshot.resources().size());
        snapshot.resources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceRef::key)))
                .forEach(e -> resources.add(new Resource(ref(e.getKey()), e.getValue(),
                        metrics.getOrDefault(e.getKey(), Map.of()))));
        return new Snapshot(snapshot.provider(), timestamp(snapshot.at()), resources);
    }

    public static Collector collector(CollectorStatus s) {
        return new Collector(s.provider(), s.baseIntervalTicks(), s.currentIntervalTicks(), s.budgetNanos(), s.runs(),
                s.errors(), s.overruns(), s.lastNanos(), s.avgNanos(), s.maxNanos(), s.throttled(), s.lastError());
    }

    static TimestampDto timestamp(Timestamp t) {
        return new TimestampDto(t.wall(), t.uptimeMillis(), t.session());
    }

    static Ref ref(ResourceRef r) {
        return new Ref(r.provider(), r.type(), r.id(), r.key());
    }
}
