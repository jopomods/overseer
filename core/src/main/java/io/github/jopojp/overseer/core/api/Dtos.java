package io.github.jopojp.overseer.core.api;

import io.github.jopojp.overseer.core.auth.AuthStore;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.pipeline.CollectorStatus;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.storage.HistoryQuery;
import io.github.jopojp.overseer.core.storage.Sessions;
import io.github.jopojp.overseer.core.storage.ShareStore;
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

    /** One key of an inventory: current amount and net changes over the last hour and day. */
    public record InventoryItem(String key, String kind, String name, long amount, long change1h, long change24h) {}

    /** A watched item with its net change over the last hour and day (for the forecast). */
    public record Watch(String item, String name, String kind, Long target, Instant createdAt, long change1h,
            long change24h) {}

    public record AddWatch(String resource, String item, Long target) {}

    public record JobLine(String key, String kind, String name, long stored, long active, long pending) {}

    public record Job(String resource, TimestampDto at, List<JobLine> lines) {}

    public record Details(String resource, TimestampDto at, List<Map<String, String>> rows) {}

    /** mode: off, timed, continuous; until: end of a timed run; active: measuring right now. */
    public record Profiler(String mode, Instant until, int intervalSeconds, boolean active) {}

    public record SetProfiler(String mode, Long durationSeconds, Integer intervalSeconds) {}

    public record Event(TimestampDto at, String kind, String attribute, String value) {}

    /** points: [uptime of the minute, amount at its end]. */
    public record InventoryHistory(String resource, String item, long from, long to, long start, Long now,
            List<long[]> points) {}

    public record Inventory(String resource, TimestampDto scannedAt, int total, List<InventoryItem> items) {}

    public record Resource(Ref ref, Map<String, String> attributes, Map<String, Double> metrics, boolean canEdit) {}

    public record OverrideDto(String resource, String key, String value) {}

    public record SetOverride(String resource, String key, String value) {}

    public record ShareDto(String player, String name, String grantedBy, String grantedAt) {}

    public record AddShare(String resource, String name) {}

    /** A world action as sent by the client (ADR-0016). */
    public record RunAction(String type, String resource, com.google.gson.JsonObject params) {}

    public record ChangeDto(String resource, String field, String before, String after) {}

    public record ActionResultDto(String status, String message, List<ChangeDto> changes, com.google.gson.JsonObject data) {}

    public record ActionLogEntryDto(long id, String at, String actor, String actorName, boolean operator,
            String action, String resource, String status, String message, List<ChangeDto> changes) {}

    public record AllowedActionsDto(String resource, List<String> types) {}

    static List<ChangeDto> changes(List<io.github.jopojp.overseer.core.action.ActionResult.Change> changes) {
        return changes.stream().map(c -> new ChangeDto(c.resource().key(), c.field(), c.before(), c.after())).toList();
    }

    public static ActionResultDto actionResult(io.github.jopojp.overseer.core.action.ActionResult r) {
        return new ActionResultDto(r.status().name().toLowerCase(java.util.Locale.ROOT), r.message(), changes(r.changes()),
                r.data());
    }

    /** Parameters are left out: they may hold secrets, and the changes say what happened. */
    public static ActionLogEntryDto actionLogEntry(io.github.jopojp.overseer.core.storage.ActionLogStore.Entry e) {
        return new ActionLogEntryDto(e.id(), e.at().toString(), e.actor().toString(), e.actorName(), e.operator(),
                e.action(), e.target().key(), e.status().name().toLowerCase(java.util.Locale.ROOT), e.message(),
                changes(e.changes()));
    }

    /** A resource the caller cannot see but may ask for. No id: ids contain positions. */
    public record RequestableDto(String provider, String handle, String name, String ownerName, String request) {}

    public record CreateRequest(String provider, String handle) {}

    public record PendingRequestDto(String resource, String resourceName, String player, String name, String requestedAt) {}

    public record Decision(String resource, String player, Boolean approve) {}

    public record Register(String resource, String name) {}

    public static ShareDto share(ShareStore.Share s) {
        return new ShareDto(s.player().toString(), s.name(), s.grantedBy() == null ? null : s.grantedBy().toString(),
                s.grantedAt().toString());
    }

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

    public record HistoryPoint(long t, Instant wall, int session, double min, double max, double avg, double last) {}

    public record HistorySeries(String resource, String metric, List<HistoryPoint> points) {}

    public record History(String resource, String resolution, long from, long to, long bucketMillis,
            List<HistorySeries> series) {}

    public record SessionDto(int session, Instant startedAt, long startedUptime, Instant lastAt, long lastUptime) {}

    public record SessionsDto(long uptimeMillis, List<SessionDto> sessions) {}

    public record StorageSettingsDto(long rawMillis, long minuteMillis, long hourMillis, long warnBytes) {}

    public record ProviderUsageDto(String provider, long series, long raw, long minute, long hour, long inventoryChanges) {}

    public record StorageDto(long fileBytes, long walBytes, StorageSettingsDto settings, List<ProviderUsageDto> providers,
            long icons, long iconBytes) {}

    public record PurgeRequest(String provider, Long olderThanMillis) {}

    public record PurgeResult(long deletedRows) {}

    /** Combines the histories of several resources; all share one bucket grid (same range and points). */
    public static History history(List<Map.Entry<ResourceRef, HistoryQuery.History>> results) {
        var first = results.get(0).getValue();
        var series = new ArrayList<HistorySeries>();
        for (var result : results) {
            for (var s : result.getValue().series()) {
                series.add(new HistorySeries(result.getKey().key(), s.metric(), s.points().stream()
                        .map(p -> new HistoryPoint(p.uptime(), Instant.ofEpochMilli(p.wall()), p.session(), p.min(), p.max(),
                                p.avg(), p.last()))
                        .toList()));
            }
        }
        return new History(results.get(0).getKey().key(), first.resolution().name().toLowerCase(Locale.ROOT), first.from(),
                first.to(), first.bucketMillis(), series);
    }

    public static SessionsDto sessions(long uptime, List<Sessions.Session> sessions) {
        return new SessionsDto(uptime, sessions.stream().map(s -> new SessionDto(s.number(),
                Instant.ofEpochMilli(s.startedWall()), s.startedUptime(), Instant.ofEpochMilli(s.lastWall()), s.lastUptime()))
                .toList());
    }

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
        return snapshot(snapshot, ref -> false);
    }

    /** @param canEdit whether the viewer may edit a resource (shown as edit controls in the UI) */
    public static Snapshot snapshot(ProviderSnapshot snapshot, java.util.function.Predicate<ResourceRef> canEdit) {
        Map<ResourceRef, Map<String, Double>> metrics = new LinkedHashMap<>();
        for (Sample sample : snapshot.samples()) {
            metrics.computeIfAbsent(sample.resource(), r -> new LinkedHashMap<>()).put(sample.metric(), sample.value());
        }
        var resources = new ArrayList<Resource>(snapshot.resources().size());
        snapshot.resources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceRef::key)))
                .forEach(e -> resources.add(new Resource(ref(e.getKey()), e.getValue(),
                        metrics.getOrDefault(e.getKey(), Map.of()), canEdit.test(e.getKey()))));
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
