package io.github.jopojp.overseer.core;

import io.github.jopojp.overseer.core.access.AccessService;
import io.github.jopojp.overseer.core.api.ApiRoutes;
import io.github.jopojp.overseer.core.auth.AuthService;
import io.github.jopojp.overseer.core.auth.AuthStore;
import io.github.jopojp.overseer.core.auth.OperatorCheck;
import io.github.jopojp.overseer.core.http.HttpConfig;
import io.github.jopojp.overseer.core.http.HttpServer;
import io.github.jopojp.overseer.core.http.Router;
import io.github.jopojp.overseer.core.http.SseHub;
import io.github.jopojp.overseer.core.http.StaticFiles;
import io.github.jopojp.overseer.core.pipeline.CollectorScheduler;
import io.github.jopojp.overseer.core.pipeline.LatestState;
import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.DataProvider;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.AccessRequestStore;
import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.storage.NotificationStore;
import io.github.jopojp.overseer.core.storage.OverrideStore;
import io.github.jopojp.overseer.core.storage.RegistrationStore;
import io.github.jopojp.overseer.core.storage.ShareStore;
import io.github.jopojp.overseer.core.storage.StorageAdmin;
import io.github.jopojp.overseer.core.storage.StorageSettings;
import io.github.jopojp.overseer.core.storage.StorageWriter;
import io.github.jopojp.overseer.core.time.UptimeClock;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything Overseer runs, wired together (ADR-0004). A platform creates one at server
 * start, calls {@link #tick()} on every server tick and {@link #close()} at shutdown.
 * The simulator uses the same class without Minecraft.
 */
public final class OverseerRuntime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OverseerRuntime.class);

    /**
     * @param dataDir            where {@code overseer.db} lives, e.g. {@code <world>/overseer}
     * @param http               web server settings
     * @param storage            storage writer tuning
     * @param sessionIdleTimeout sessions unused for this long expire
     * @param queueCapacity      snapshots buffered between tick and storage
     * @param version            Overseer version reported by the API
     * @param webRoot            classpath directory of the UI bundle
     */
    public record Config(
            Path dataDir,
            HttpConfig http,
            StorageSettings storage,
            Duration sessionIdleTimeout,
            int queueCapacity,
            String version,
            String webRoot) {
        public Config {
            Objects.requireNonNull(dataDir, "dataDir");
            Objects.requireNonNull(http, "http");
            Objects.requireNonNull(storage, "storage");
            Objects.requireNonNull(sessionIdleTimeout, "sessionIdleTimeout");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(webRoot, "webRoot");
        }

        public static Config defaults(Path dataDir, String version) {
            return new Config(dataDir, HttpConfig.DEFAULTS, StorageSettings.DEFAULTS, Duration.ofDays(30), 1024,
                    version, "overseer-web");
        }
    }

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private CollectorScheduler scheduler;
    private AuthService auth;
    private HttpServer http;
    private NotificationStore notifications;
    private RegistrationStore registrations;
    private io.github.jopojp.overseer.core.storage.WatchStore watches;
    private io.github.jopojp.overseer.core.storage.IconStore icons;
    private io.github.jopojp.overseer.core.storage.ProfilerStore profiler;
    private java.util.function.Function<String, byte[]> iconFallback;
    private OverrideStore overrides;
    private Clock wall;
    private io.github.jopojp.overseer.core.action.ActionQueue actionQueue;

    /** Time the server thread may spend on world actions per tick (ADR-0016). */
    static final long ACTION_BUDGET_NANOS = 5_000_000;
    /** How long a dashboard request waits for the server thread to run its action. */
    static final Duration ACTION_TIMEOUT = Duration.ofSeconds(10);

    private OperatorCheck operators = OperatorCheck.NONE;

    private OverseerRuntime() {}

    /** Opens storage, starts the writer and the web server. */
    public static OverseerRuntime start(Config config, List<DataProvider> providers, OperatorCheck operators)
            throws Exception {
        return start(config, providers, operators, null);
    }

    /**
     * @param iconFallback icon (PNG) for a base stack key when none was exported, e.g. a texture
     *                     from a mod jar; {@code null} for none. Called off the server thread.
     */
    public static OverseerRuntime start(Config config, List<DataProvider> providers, OperatorCheck operators,
            java.util.function.Function<String, byte[]> iconFallback) throws Exception {
        var runtime = new OverseerRuntime();
        runtime.operators = operators;
        runtime.iconFallback = iconFallback;
        try {
            runtime.init(config, providers);
            return runtime;
        } catch (Exception | Error e) {
            runtime.close();
            throw e;
        }
    }

    private void init(Config config, List<DataProvider> providers) throws Exception {
        Clock wall = Clock.systemUTC();
        Database db = Database.open(config.dataDir().resolve("overseer.db"));
        resources.push(db);

        var session = db.startSession(wall.instant());
        var clock = UptimeClock.system(session.baseUptimeMillis(), session.session());
        var queue = new SnapshotQueue(config.queueCapacity());
        var latest = new LatestState();
        var inventories = new io.github.jopojp.overseer.core.pipeline.InventoryState();
        latest.addListener(inventories);
        scheduler = new CollectorScheduler(providers, clock, queue, latest, System::nanoTime);
        List<ProviderDescriptor> descriptors = providers.stream().map(DataProvider::descriptor).toList();

        // Admin-edited retention (stored in the database) overrides the defaults.
        StorageSettings storage;
        try (var reader = db.openReader()) {
            storage = StorageAdmin.apply(config.storage(), StorageAdmin.load(reader, config.storage()));
        }
        var writer = new StorageWriter(db, queue, descriptors, clock, storage);
        writer.start();
        resources.push(writer);

        var authStore = new AuthStore(db.openControlWriter(), config.sessionIdleTimeout());
        resources.push(authStore);
        auth = new AuthService(authStore, operators, wall);
        var overrides = new OverrideStore(db.openControlWriter());
        resources.push(overrides);
        var shares = new ShareStore(db.openControlWriter());
        resources.push(shares);
        var access = new AccessService(providers, shares);
        var requests = new AccessRequestStore(db.openControlWriter());
        resources.push(requests);
        notifications = new NotificationStore(db.openControlWriter());
        resources.push(notifications);
        registrations = new RegistrationStore(db.openControlWriter());
        resources.push(registrations);
        watches = new io.github.jopojp.overseer.core.storage.WatchStore(db.openControlWriter());
        resources.push(watches);
        icons = new io.github.jopojp.overseer.core.storage.IconStore(db.openControlWriter(), iconFallback);
        resources.push(icons);
        profiler = new io.github.jopojp.overseer.core.storage.ProfilerStore(db.openControlWriter());
        resources.push(profiler);
        this.overrides = overrides;
        this.wall = wall;
        var actionLog = new io.github.jopojp.overseer.core.storage.ActionLogStore(db.openControlWriter());
        resources.push(actionLog);
        actionQueue = new io.github.jopojp.overseer.core.action.ActionQueue(64);
        var actions = new io.github.jopojp.overseer.core.action.ActionService(providers, access, actionQueue, actionLog,
                wall, ACTION_TIMEOUT);

        var sse = new SseHub(access, auth, latest);
        resources.push(sse);

        var router = new ApiRoutes(config.version(), auth, access, latest, inventories, queue, descriptors, scheduler::status, wall,
                db, clock::uptimeMillis, overrides, shares, requests, notifications, registrations, watches, icons, profiler, writer,
                actions, actionLog)
                .register(new Router());
        http = new HttpServer(config.http(), router,
                new StaticFiles(config.webRoot(), OverseerRuntime.class.getClassLoader()), sse);
        http.start();
        resources.push(http);
        // Closed before the web server, so waiting requests get an answer instead of hanging.
        resources.push(actionQueue);

        LOG.info("Overseer {} started (session {}, {} providers)", config.version(), session.session(),
                providers.size());
    }

    /** Collects due providers. Call once per server tick, on the server thread. */
    public void tick() {
        scheduler.tick();
        actionQueue.drain(ACTION_BUDGET_NANOS, System::nanoTime);
    }

    /** For {@code /ov link}, {@code ov login}, {@code ov admin}. */
    public AuthService auth() {
        return auth;
    }

    /** In-game messages for players; the platform delivers them while players are online. */
    public NotificationStore notifications() {
        return notifications;
    }

    /** Resources registered in Overseer (e.g. AE2 grids without a controller). */
    public RegistrationStore registrations() {
        return registrations;
    }

    /** Tick profiler settings; the platform reads them every collection. */
    public io.github.jopojp.overseer.core.storage.ProfilerStore profiler() {
        return profiler;
    }

    /** Item icons; the platform stores exported ones here. */
    public io.github.jopojp.overseer.core.storage.IconStore icons() {
        return icons;
    }

    /** Watched items per resource; providers read them every collection. */
    public io.github.jopojp.overseer.core.storage.WatchStore watches() {
        return watches;
    }

    /**
     * Registers a resource for {@code owner} and names it. Used by {@code /ov ae2 register}
     * and by admins adopting a hidden grid. Not on the server thread (database write).
     */
    public void register(ResourceRef ref, UUID owner, String name) throws java.sql.SQLException {
        registrations.put(ref, owner, wall.instant());
        if (name != null && !name.isBlank()) overrides.set(ref, "name", name.strip(), owner.toString(), wall.instant());
    }

    public int httpPort() {
        return http.port();
    }

    /** Stops the web server, flushes storage and closes the database, in reverse start order. */
    @Override
    public void close() {
        while (!resources.isEmpty()) {
            AutoCloseable resource = resources.pop();
            try {
                resource.close();
            } catch (Exception e) {
                LOG.warn("Overseer failed to close {}", resource.getClass().getSimpleName(), e);
            }
        }
    }
}
