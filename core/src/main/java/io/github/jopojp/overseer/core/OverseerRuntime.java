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
import io.github.jopojp.overseer.core.storage.AccessRequestStore;
import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.storage.NotificationStore;
import io.github.jopojp.overseer.core.storage.OverrideStore;
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

    private OperatorCheck operators = OperatorCheck.NONE;

    private OverseerRuntime() {}

    /** Opens storage, starts the writer and the web server. */
    public static OverseerRuntime start(Config config, List<DataProvider> providers, OperatorCheck operators)
            throws Exception {
        var runtime = new OverseerRuntime();
        runtime.operators = operators;
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

        var sse = new SseHub(access, auth, latest);
        resources.push(sse);

        var router = new ApiRoutes(config.version(), auth, access, latest, queue, descriptors, scheduler::status, wall,
                db, clock::uptimeMillis, overrides, shares, requests, notifications, writer)
                .register(new Router());
        http = new HttpServer(config.http(), router,
                new StaticFiles(config.webRoot(), OverseerRuntime.class.getClassLoader()), sse);
        http.start();
        resources.push(http);

        LOG.info("Overseer {} started (session {}, {} providers)", config.version(), session.session(),
                providers.size());
    }

    /** Collects due providers. Call once per server tick, on the server thread. */
    public void tick() {
        scheduler.tick();
    }

    /** For {@code /ov link}, {@code ov login}, {@code ov admin}. */
    public AuthService auth() {
        return auth;
    }

    /** In-game messages for players; the platform delivers them while players are online. */
    public NotificationStore notifications() {
        return notifications;
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
