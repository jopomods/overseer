package io.github.jopojp.overseer.core.storage;

import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.MetricDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.provider.Sample;
import io.github.jopojp.overseer.core.time.Timestamp;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single writer of the database (ADR-0004, ADR-0006).
 *
 * <p>Consumes snapshots from the {@link SnapshotQueue} on its own thread and turns them into
 * rows: resources and their attribute changes become events; samples pass the
 * deadband/heartbeat filter. Each batch is one transaction, which also advances the
 * current uptime session.
 *
 * <p>Keeps the last known state in memory (loaded from the database at start), so a restart
 * does not re-record unchanged resources as added.
 */
public final class StorageWriter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(StorageWriter.class);
    private static final long POLL_MILLIS = 250;

    enum EventKind {
        ADDED,
        REMOVED,
        ATTRIBUTE;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private record SeriesKey(long resourceId, String metric) {}

    private static final Comparator<ResourceRef> BY_KEY = Comparator.comparing(ResourceRef::key);

    private final Database db;
    private final Connection connection;
    private final SnapshotQueue queue;
    private final Map<String, Map<String, MetricDescriptor>> metrics = new HashMap<>();
    private final UptimeClock clock;
    private volatile StorageSettings settings;
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> maintenance = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final Map<ResourceRef, Long> resourceIds = new HashMap<>();
    private final Map<ResourceRef, Map<String, String>> attributes = new HashMap<>();
    private final Map<String, Set<ResourceRef>> presentByProvider = new HashMap<>();
    private final Map<SeriesKey, Long> seriesIds = new HashMap<>();
    private SampleFilter filter = new SampleFilter();
    private final InventoryWriter inventories;
    private final FlowWriter flows;

    private PreparedStatement insertResource;
    private PreparedStatement insertEvent;
    private PreparedStatement insertSeries;
    private PreparedStatement insertSample;
    private PreparedStatement updateSession;

    private volatile boolean running;
    private Thread thread;
    private long lastSessionFlushNanos;
    private long lastRollupNanos = System.nanoTime();
    private boolean stateLoaded;

    public StorageWriter(
            Database db,
            SnapshotQueue queue,
            List<ProviderDescriptor> providers,
            UptimeClock clock,
            StorageSettings settings) {
        this.db = Objects.requireNonNull(db);
        this.connection = db.writer();
        this.inventories = new InventoryWriter(connection);
        this.flows = new FlowWriter(connection, inventories);
        this.queue = Objects.requireNonNull(queue);
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
        for (ProviderDescriptor provider : providers) {
            var byKey = new HashMap<String, MetricDescriptor>();
            for (MetricDescriptor metric : provider.metrics()) byKey.put(metric.key(), metric);
            metrics.put(provider.id(), byKey);
        }
    }

    /** Loads known state and starts the writer thread. */
    public synchronized void start() throws SQLException {
        if (running) throw new IllegalStateException("already started");
        loadState();
        running = true;
        lastSessionFlushNanos = System.nanoTime();
        thread = new Thread(this::loop, "overseer-storage");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the thread after writing everything still queued, then persists the uptime. */
    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) LOG.warn("Overseer storage writer did not stop within 10 s");
    }

    private void loop() {
        var batch = new ArrayList<ProviderSnapshot>(settings.batchSize());
        while (running || queue.size() > 0) {
            runMaintenance();
            try {
                ProviderSnapshot first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, settings.batchSize() - 1);
                    write(batch);
                    batch.clear();
                } else if (System.nanoTime() - lastSessionFlushNanos
                        >= TimeUnit.MILLISECONDS.toNanos(settings.sessionFlushMillis())) {
                    write(List.of());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Overseer storage writer failed to write {} snapshots; dropping them", batch.size(), e);
                batch.clear();
                rollbackQuietly();
            }
        }
        try {
            write(List.of());
        } catch (SQLException e) {
            LOG.error("Overseer storage writer failed to persist uptime on shutdown", e);
        }
    }

/** Work that needs the writer connection, e.g. purge, VACUUM, backup. */
    @FunctionalInterface
    public interface Task<T> {
        T run(java.sql.Connection connection) throws Exception;
    }

    /**
     * Runs a task on the writer thread between batches (ADR-0006: single writer). The task
     * owns its transaction: the connection is in auto-commit-off mode; commit when done.
     */
    public <T> java.util.concurrent.CompletableFuture<T> submit(Task<T> task) {
        var future = new java.util.concurrent.CompletableFuture<T>();
        maintenance.add(() -> {
            try {
                future.complete(task.run(connection));
            } catch (Exception e) {
                rollbackQuietly();
                future.completeExceptionally(e);
            }
        });
        if (!running) runMaintenance(); // not started (tests) or stopped: run inline
        return future;
    }

    private void runMaintenance() {
        Runnable job;
        while ((job = maintenance.poll()) != null) job.run();
    }

    public StorageSettings settings() {
        return settings;
    }

    /** Takes effect with the next rollup run. */
    public void updateSettings(StorageSettings updated) {
        this.settings = Objects.requireNonNull(updated);
    }

    /** Writes a batch in one transaction. Package-private so tests can write synchronously. */
    void write(List<ProviderSnapshot> batch) throws SQLException {
        if (!stateLoaded) loadState();
        try {
            for (ProviderSnapshot snapshot : batch) apply(snapshot);
            flushSession(clock.now());
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            // In-memory caches may now be ahead of the database; reload them.
            filter = new SampleFilter();
            stateLoaded = false;
            throw e;
        }
        lastSessionFlushNanos = System.nanoTime();
        if (System.nanoTime() - lastRollupNanos >= TimeUnit.MILLISECONDS.toNanos(settings.rollupIntervalMillis())) {
            rollup(clock.uptimeMillis());
        }
    }

    /** Rollups and retention in their own transaction (ADR-0005). Package-private for tests. */
    void rollup(long nowUptime) throws SQLException {
        lastRollupNanos = System.nanoTime();
        try {
            Rollups.run(connection, nowUptime, settings);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly();
            LOG.error("Overseer rollup failed; retrying on the next run", e);
        }
    }

    private void apply(ProviderSnapshot snapshot) throws SQLException {
        Timestamp at = snapshot.at();
        Set<ResourceRef> present = presentByProvider.computeIfAbsent(snapshot.provider(), k -> new HashSet<>());

        // Snapshot maps have no defined iteration order; sort so events are deterministic.
        var refs = new ArrayList<>(snapshot.resources().keySet());
        refs.sort(BY_KEY);
        for (ResourceRef ref : refs) {
            Map<String, String> current = snapshot.resources().get(ref);
            long id = resourceId(ref, at);
            Map<String, String> known = attributes.get(ref);
            if (known == null) {
                event(id, EventKind.ADDED, null, null, at);
                known = Map.of();
            }
            for (String name : new TreeSet<>(current.keySet())) {
                if (!Objects.equals(known.get(name), current.get(name))) {
                    event(id, EventKind.ATTRIBUTE, name, current.get(name), at);
                }
            }
            for (String name : new TreeSet<>(known.keySet())) {
                if (!current.containsKey(name)) event(id, EventKind.ATTRIBUTE, name, null, at);
            }
            attributes.put(ref, Map.copyOf(current));
            present.add(ref);
        }

        var gone = new ArrayList<ResourceRef>();
        for (ResourceRef ref : present) {
            if (!snapshot.resources().containsKey(ref)) gone.add(ref);
        }
        gone.sort(BY_KEY);
        for (ResourceRef ref : gone) {
            event(resourceId(ref, at), EventKind.REMOVED, null, null, at);
            attributes.remove(ref);
            present.remove(ref);
        }

        Map<String, MetricDescriptor> known = metrics.getOrDefault(snapshot.provider(), Map.of());
        for (Sample sample : snapshot.samples()) {
            if (!Double.isFinite(sample.value())) continue;
            long seriesId = seriesId(resourceId(sample.resource(), at), sample.metric());
            MetricDescriptor descriptor = known.get(sample.metric());
            double deadband = descriptor == null ? 0 : descriptor.relativeDeadband();
            if (filter.accept(seriesId, sample.value(), at.uptimeMillis(), deadband, settings.heartbeatMillis())) {
                PreparedStatement ps = insertSample;
                ps.setLong(1, seriesId);
                ps.setLong(2, at.uptimeMillis());
                ps.setLong(3, at.wall().toEpochMilli());
                ps.setInt(4, at.session());
                ps.setDouble(5, sample.value());
                ps.executeUpdate();
            }
        }

        var scanned = new ArrayList<>(snapshot.inventories().keySet());
        scanned.sort(BY_KEY);
        for (ResourceRef ref : scanned) {
            inventories.apply(resourceId(ref, at), snapshot.inventories().get(ref), at);
        }

        var flowing = new ArrayList<>(snapshot.flows().keySet());
        flowing.sort(BY_KEY);
        for (ResourceRef ref : flowing) {
            flows.apply(resourceId(ref, at), snapshot.flows().get(ref), at);
        }
    }

    private long resourceId(ResourceRef ref, Timestamp at) throws SQLException {
        Long id = resourceIds.get(ref);
        if (id != null) return id;
        PreparedStatement ps = insertResource;
        ps.setString(1, ref.provider());
        ps.setString(2, ref.type());
        ps.setString(3, ref.id());
        ps.setLong(4, at.wall().toEpochMilli());
        ps.setLong(5, at.uptimeMillis());
        ps.executeUpdate();
        long newId = generatedKey(ps);
        resourceIds.put(ref, newId);
        return newId;
    }

    private long seriesId(long resourceId, String metric) throws SQLException {
        var key = new SeriesKey(resourceId, metric);
        Long id = seriesIds.get(key);
        if (id != null) return id;
        PreparedStatement ps = insertSeries;
        ps.setLong(1, resourceId);
        ps.setString(2, metric);
        ps.executeUpdate();
        long newId = generatedKey(ps);
        seriesIds.put(key, newId);
        return newId;
    }

    private void event(long resourceId, EventKind kind, String attribute, String value, Timestamp at)
            throws SQLException {
        PreparedStatement ps = insertEvent;
        ps.setLong(1, resourceId);
        ps.setString(2, kind.db());
        if (attribute == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, attribute);
        if (value == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, value);
        ps.setLong(5, at.wall().toEpochMilli());
        ps.setLong(6, at.uptimeMillis());
        ps.setInt(7, at.session());
        ps.executeUpdate();
    }

    private void flushSession(Timestamp now) throws SQLException {
        PreparedStatement ps = updateSession;
        ps.setLong(1, now.wall().toEpochMilli());
        ps.setLong(2, now.uptimeMillis());
        ps.setInt(3, now.session());
        ps.executeUpdate();
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (var keys = ps.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("no generated key");
            return keys.getLong(1);
        }
    }

    /** Rebuilds the in-memory state from the database. */
    private void loadState() throws SQLException {
        prepareStatements();
        resourceIds.clear();
        attributes.clear();
        presentByProvider.clear();
        seriesIds.clear();

        var refsById = new HashMap<Long, ResourceRef>();
        try (Statement st = connection.createStatement()) {
            try (var rs = st.executeQuery("SELECT id, provider, type, rid FROM resource")) {
                while (rs.next()) {
                    var ref = new ResourceRef(rs.getString(2), rs.getString(3), rs.getString(4));
                    resourceIds.put(ref, rs.getLong(1));
                    refsById.put(rs.getLong(1), ref);
                }
            }
            // A resource is present if its latest added/removed event is "added".
            try (var rs = st.executeQuery("SELECT resource_id FROM event WHERE id IN ("
                    + "SELECT MAX(id) FROM event WHERE kind IN ('added', 'removed') GROUP BY resource_id)"
                    + " AND kind = 'added'")) {
                while (rs.next()) {
                    ResourceRef ref = refsById.get(rs.getLong(1));
                    attributes.put(ref, new HashMap<>());
                    presentByProvider.computeIfAbsent(ref.provider(), k -> new HashSet<>()).add(ref);
                }
            }
            // Latest value of each attribute since the resource was last added.
            try (var rs = st.executeQuery("SELECT e.resource_id, e.attribute, e.value FROM event e"
                    + " WHERE e.id IN (SELECT MAX(id) FROM event WHERE kind = 'attribute' GROUP BY resource_id, attribute)"
                    + " AND e.id > (SELECT MAX(a.id) FROM event a WHERE a.resource_id = e.resource_id AND a.kind = 'added')")) {
                while (rs.next()) {
                    Map<String, String> attrs = attributes.get(refsById.get(rs.getLong(1)));
                    if (attrs != null && rs.getString(3) != null) attrs.put(rs.getString(2), rs.getString(3));
                }
            }
            try (var rs = st.executeQuery("SELECT id, resource_id, metric FROM series")) {
                while (rs.next()) seriesIds.put(new SeriesKey(rs.getLong(2), rs.getString(3)), rs.getLong(1));
            }
        }
        attributes.replaceAll((ref, attrs) -> Map.copyOf(attrs));
        inventories.load();
        flows.load();
        connection.commit();
        stateLoaded = true;
    }

    private void prepareStatements() throws SQLException {
        if (insertResource != null) return;
        insertResource = connection.prepareStatement("INSERT INTO resource "
                + "(provider, type, rid, first_seen_wall_ms, first_seen_uptime_ms) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
        insertEvent = connection.prepareStatement("INSERT INTO event "
                + "(resource_id, kind, attribute, value, wall_ms, uptime_ms, session) VALUES (?, ?, ?, ?, ?, ?, ?)");
        insertSeries = connection.prepareStatement("INSERT INTO series (resource_id, metric) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS);
        insertSample = connection.prepareStatement("INSERT OR REPLACE INTO sample "
                + "(series_id, uptime_ms, wall_ms, session, value) VALUES (?, ?, ?, ?, ?)");
        updateSession = connection.prepareStatement(
                "UPDATE uptime_session SET last_wall_ms = ?, last_uptime_ms = ? WHERE session = ?");
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("Overseer storage rollback failed", e);
        }
    }
}
