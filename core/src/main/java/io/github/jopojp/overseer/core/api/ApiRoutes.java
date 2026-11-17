package io.github.jopojp.overseer.core.api;

import io.github.jopojp.overseer.core.access.AccessService;
import io.github.jopojp.overseer.core.auth.AuthService;
import io.github.jopojp.overseer.core.auth.LinkCodes;
import io.github.jopojp.overseer.core.auth.Principal;
import io.github.jopojp.overseer.core.auth.RateLimiter;
import io.github.jopojp.overseer.core.http.Cookies;
import io.github.jopojp.overseer.core.http.HttpError;
import io.github.jopojp.overseer.core.http.Json;
import io.github.jopojp.overseer.core.http.Request;
import io.github.jopojp.overseer.core.http.Response;
import io.github.jopojp.overseer.core.http.Router;
import io.github.jopojp.overseer.core.pipeline.CollectorStatus;
import io.github.jopojp.overseer.core.pipeline.LatestState;
import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.storage.HistoryQuery;
import io.github.jopojp.overseer.core.storage.OverrideStore;
import io.github.jopojp.overseer.core.storage.StorageAdmin;
import io.github.jopojp.overseer.core.storage.StorageWriter;
import io.github.jopojp.overseer.core.storage.Sessions;
import java.util.function.LongSupplier;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code /api/v1} routes as specified in {@code api/openapi.yaml} (ADR-0010).
 * Every route except health and the login entry points requires a principal; data
 * routes filter by permission (ADR-0008).
 */
public final class ApiRoutes {
    static final Duration SESSION_COOKIE_MAX_AGE = Duration.ofDays(400);
    static final String LINK_COOKIE_PATH = "/api/v1/auth";
    static final Duration STALE_AFTER = Duration.ofSeconds(30);

    private final String version;
    private final AuthService auth;
    private final AccessService access;
    private final LatestState latest;
    private final SnapshotQueue queue;
    private final List<ProviderDescriptor> providers;
    private final Supplier<List<CollectorStatus>> collectors;
    private final Clock clock;
    private final Database db;
    private final LongSupplier uptime;
    private final OverrideStore overrides;
    private final StorageWriter writer;

    private final RateLimiter linkCreate = RateLimiter.perMinute(10);
    private final RateLimiter linkPoll = RateLimiter.perMinute(90);
    private final RateLimiter tokenLogin = RateLimiter.perMinute(20);

    public ApiRoutes(
            String version,
            AuthService auth,
            AccessService access,
            LatestState latest,
            SnapshotQueue queue,
            List<ProviderDescriptor> providers,
            Supplier<List<CollectorStatus>> collectors,
            Clock clock,
            Database db,
            LongSupplier uptime,
            OverrideStore overrides,
            StorageWriter writer) {
        this.version = version;
        this.auth = auth;
        this.access = access;
        this.latest = latest;
        this.queue = queue;
        this.providers = List.copyOf(providers);
        this.collectors = collectors;
        this.clock = clock;
        this.db = db;
        this.uptime = uptime;
        this.overrides = overrides;
        this.writer = writer;
    }

    public Router register(Router router) {
        return router
                .get("/api/v1/health", r -> Response.json(health()))
                .post("/api/v1/auth/link-codes", this::createLinkCode)
                .get("/api/v1/auth/link-codes/current", this::pollLinkCode)
                .get("/api/v1/auth/login", this::loginWithToken)
                .post("/api/v1/auth/logout", this::logout)
                .get("/api/v1/me", r -> Response.json(Dtos.me(principal(r))))
                .get("/api/v1/me/credentials", this::listCredentials)
                .post("/api/v1/me/credentials/revoke-all", this::revokeAll)
                .delete("/api/v1/me/credentials/{id}", this::revokeCredential)
                .post("/api/v1/me/device-tokens", this::createDeviceToken)
                .get("/api/v1/providers", this::listProviders)
                .get("/api/v1/providers/{provider}/snapshot", this::snapshot)
                .get("/api/v1/stream", r -> Response.sse(principal(r)))
                .get("/api/v1/history", this::history)
                .get("/api/v1/sessions", this::sessions)
                .get("/api/v1/overrides", this::listOverrides)
                .put("/api/v1/overrides", this::setOverride)
                .get("/api/v1/admin/collectors", this::collectors)
                .get("/api/v1/admin/storage", this::storage)
                .put("/api/v1/admin/storage/settings", this::storageSettings)
                .post("/api/v1/admin/storage/purge", this::purge)
                .post("/api/v1/admin/storage/vacuum", this::vacuum)
                .get("/api/v1/admin/storage/backup", this::backup);
    }

    // --- system --------------------------------------------------------------------------

    Dtos.Health health() {
        Long age = latest.all().values().stream()
                .map(s -> Duration.between(s.at().wall(), clock.instant()).toMillis())
                .min(Long::compare)
                .orElse(null);
        boolean throttled = collectors.get().stream().anyMatch(CollectorStatus::throttled);
        boolean stale = age != null && age > STALE_AFTER.toMillis();
        return new Dtos.Health(throttled || stale ? "degraded" : "ok", version, age);
    }

    // --- auth ----------------------------------------------------------------------------

    private Response createLinkCode(Request r) {
        if (!linkCreate.tryAcquire(r.clientAddress())) throw HttpError.rateLimited();
        LinkCodes.Created created;
        try {
            created = auth.startLink();
        } catch (IllegalStateException e) {
            throw new HttpError(503, "unavailable", "Too many pending logins. Try again later.");
        }
        return Response.json(201, new Dtos.LinkCode(created.code(), created.expiresAt()))
                .cookie(Cookies.set(Cookies.LINK, created.pollSecret(), LINK_COOKIE_PATH, LinkCodes.TTL, r.secure()));
    }

    private Response pollLinkCode(Request r) throws SQLException {
        if (!linkPoll.tryAcquire(r.clientAddress())) throw HttpError.rateLimited();
        var poll = auth.pollLink(r.cookie(Cookies.LINK).orElse(null), r.header("user-agent").orElse(null));
        String status = poll.state().name().toLowerCase(java.util.Locale.ROOT);
        Response response = Response.json(new Dtos.LinkCodeStatus(status));
        if (poll.session().isPresent()) {
            response.cookie(Cookies.set(Cookies.SESSION, poll.session().get().token(), "/", SESSION_COOKIE_MAX_AGE,
                    r.secure()));
            response.cookie(Cookies.clear(Cookies.LINK, LINK_COOKIE_PATH, r.secure()));
        } else if (poll.state() == LinkCodes.State.EXPIRED) {
            response.cookie(Cookies.clear(Cookies.LINK, LINK_COOKIE_PATH, r.secure()));
        }
        return response;
    }

    private Response loginWithToken(Request r) throws SQLException {
        if (!tokenLogin.tryAcquire(r.clientAddress())) throw HttpError.rateLimited();
        String token = r.queryParam("token").orElseThrow(HttpError::unauthorized);
        String cookie = auth.exchangeLoginToken(token, r.header("user-agent").orElse(null))
                .orElseThrow(HttpError::unauthorized);
        return Response.redirect("/")
                .cookie(Cookies.set(Cookies.SESSION, cookie, "/", SESSION_COOKIE_MAX_AGE, r.secure()));
    }

    private Response logout(Request r) throws SQLException {
        var principal = auth.authenticate(r.bearerToken().orElse(null), r.cookie(Cookies.SESSION).orElse(null));
        if (principal.isPresent() && !principal.get().deviceToken()) {
            auth.revoke(principal.get(), principal.get().credentialId());
        }
        return Response.noContent().cookie(Cookies.clear(Cookies.SESSION, "/", r.secure()));
    }

    // --- me ------------------------------------------------------------------------------

    private Response listCredentials(Request r) throws SQLException {
        Principal p = principal(r);
        return Response.json(auth.credentials(p).stream().map(i -> Dtos.credential(i, p.credentialId())).toList());
    }

    private Response revokeCredential(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        long id;
        try {
            id = Long.parseLong(r.pathParam("id"));
        } catch (NumberFormatException e) {
            throw HttpError.notFound();
        }
        if (!auth.revoke(p, id)) throw HttpError.notFound();
        return Response.noContent();
    }

    private Response revokeAll(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        auth.revokeAllSessions(p.player().uuid());
        return Response.noContent().cookie(Cookies.clear(Cookies.SESSION, "/", r.secure()));
    }

    private Response createDeviceToken(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var body = Json.parse(r.bodyText(), Dtos.CreateDeviceToken.class);
        String name = body.name() == null ? "" : body.name().strip();
        if (name.isEmpty() || name.length() > 64) throw HttpError.badRequest("Name must be 1 to 64 characters.");
        var issued = auth.createDeviceToken(p, name);
        return Response.json(201, new Dtos.CreatedDeviceToken(issued.id(), issued.token()));
    }

    // --- data ----------------------------------------------------------------------------

    private Response listProviders(Request r) throws SQLException {
        principal(r);
        return Response.json(providers.stream().map(Dtos::provider).toList());
    }

    private Response snapshot(Request r) throws SQLException {
        Principal p = principal(r);
        String id = r.pathParam("provider");
        ProviderSnapshot snapshot = latest.get(id).orElseThrow(HttpError::notFound);
        return Response.json(Dtos.snapshot(access.filter(p, snapshot), ref -> access.canEdit(p, ref)));
    }

    static final int MAX_METRICS = 10;
    static final int MAX_RESOURCES = 250;
    static final long DEFAULT_RANGE = 24 * 3_600_000L;

    private Response history(Request r) throws SQLException {
        Principal p = principal(r);
        List<String> keys = r.query().getOrDefault("resource", List.of());
        if (keys.isEmpty() || keys.size() > MAX_RESOURCES) {
            throw HttpError.badRequest("Give 1 to " + MAX_RESOURCES + " resource parameters.");
        }
        // Invisible resources are skipped; if none is visible, the answer is 404.
        List<ResourceRef> refs = keys.stream().map(ApiRoutes::parseRef).filter(ref -> access.canView(p, ref)).toList();
        if (refs.isEmpty()) throw HttpError.notFound();
        List<String> metrics = r.query().getOrDefault("metric", List.of());
        if (metrics.isEmpty() || metrics.size() > MAX_METRICS) {
            throw HttpError.badRequest("Give 1 to " + MAX_METRICS + " metric parameters.");
        }
        long to = longParam(r, "to", uptime.getAsLong());
        long from = longParam(r, "from", to - DEFAULT_RANGE);
        int points = (int) longParam(r, "points", 240);
        if (to <= from) throw HttpError.badRequest("to must be after from.");
        if (points < 10 || points > 1000) throw HttpError.badRequest("points must be 10 to 1000.");
        try (var reader = db.openReader()) {
            var results = new java.util.ArrayList<Map.Entry<ResourceRef, HistoryQuery.History>>();
            for (ResourceRef ref : refs) {
                results.add(Map.entry(ref, HistoryQuery.query(reader, ref, metrics, Math.max(0, from), to, points)));
            }
            return Response.json(Dtos.history(results));
        }
    }

    private Response sessions(Request r) throws SQLException {
        principal(r);
        try (var reader = db.openReader()) {
            return Response.json(Dtos.sessions(uptime.getAsLong(), Sessions.list(reader)));
        }
    }

    static final int MAX_OVERRIDE_LENGTH = 48;

    private Response listOverrides(Request r) throws SQLException {
        Principal p = principal(r);
        String provider = r.queryParam("provider").orElseThrow(() -> HttpError.badRequest("provider is required."));
        return Response.json(overrides.list(provider).stream()
                .filter(o -> access.canView(p, o.resource()))
                .map(o -> new Dtos.OverrideDto(o.resource().key(), o.key(), o.value()))
                .toList());
    }

    private Response setOverride(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var body = Json.parse(r.bodyText(), Dtos.SetOverride.class);
        if (body.resource() == null || body.key() == null) throw HttpError.badRequest("resource and key are required.");
        ResourceRef ref = parseRef(body.resource());
        if (!OverrideStore.KEYS.contains(body.key())) throw HttpError.badRequest("Unknown key.");
        if (!access.canView(p, ref)) throw HttpError.notFound();
        if (!access.canEdit(p, ref)) throw HttpError.forbidden("You may not change this.");
        String value = body.value() == null ? null : body.value().strip();
        if (value != null && value.isEmpty()) value = null;
        if (value != null && value.length() > MAX_OVERRIDE_LENGTH) {
            throw HttpError.badRequest("Value must be at most " + MAX_OVERRIDE_LENGTH + " characters.");
        }
        overrides.set(ref, body.key(), value, p.player().uuid().toString(), clock.instant());
        return Response.noContent();
    }

    private static ResourceRef parseRef(String key) {
        String[] parts = key.split("/", 3);
        try {
            if (parts.length == 3) return new ResourceRef(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw HttpError.badRequest("resource must look like provider/type/id.");
    }

    private static long longParam(Request r, String name, long fallback) {
        var value = r.queryParam(name);
        if (value.isEmpty()) return fallback;
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException e) {
            throw HttpError.badRequest(name + " must be a number.");
        }
    }

    // --- admin ---------------------------------------------------------------------------

    private Response collectors(Request r) throws SQLException {
        Principal p = principal(r);
        if (!p.isAdmin()) throw HttpError.forbidden("Admins only.");
        return Response.json(new Dtos.Collectors(collectors.get().stream().map(Dtos::collector).toList(),
                queue.dropped(), queue.size()));
    }

    private Principal admin(Request r) throws SQLException {
        Principal p = principal(r);
        if (!p.isAdmin() || p.deviceToken()) throw HttpError.forbidden("Admins only.");
        return p;
    }

    private Response storage(Request r) throws SQLException {
        admin(r);
        StorageAdmin.Usage usage;
        StorageAdmin.Settings settings;
        try (var reader = db.openReader()) {
            usage = StorageAdmin.usage(reader, db.file());
            settings = StorageAdmin.load(reader, writer.settings());
        }
        return Response.json(new Dtos.StorageDto(usage.fileBytes(), usage.walBytes(),
                new Dtos.StorageSettingsDto(settings.rawMillis(), settings.minuteMillis(), settings.hourMillis(), settings.warnBytes()),
                usage.providers().stream().map(u -> new Dtos.ProviderUsageDto(u.provider(), u.series(), u.raw(), u.minute(), u.hour())).toList()));
    }

    private Response storageSettings(Request r) throws Exception {
        admin(r);
        var body = Json.parse(r.bodyText(), Dtos.StorageSettingsDto.class);
        StorageAdmin.Settings settings;
        try {
            settings = new StorageAdmin.Settings(body.rawMillis(), body.minuteMillis(), body.hourMillis(), body.warnBytes());
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest("Values must be 0 (forever) or positive.");
        }
        if (settings.rawMillis() != 0 && settings.rawMillis() < 3_600_000) {
            throw HttpError.badRequest("Raw data must be kept at least 1 hour.");
        }
        await(writer.submit(c -> {
            StorageAdmin.save(c, settings);
            return null;
        }));
        writer.updateSettings(StorageAdmin.apply(writer.settings(), settings));
        return Response.noContent();
    }

    private Response purge(Request r) throws Exception {
        admin(r);
        var body = Json.parse(r.bodyText(), Dtos.PurgeRequest.class);
        if (body.provider() == null || body.provider().isBlank()) throw HttpError.badRequest("provider is required.");
        Long before = body.olderThanMillis() == null ? null : uptime.getAsLong() - body.olderThanMillis();
        long deleted = await(writer.submit(c -> StorageAdmin.purge(c, body.provider(), before)));
        return Response.json(new Dtos.PurgeResult(deleted));
    }

    private Response vacuum(Request r) throws Exception {
        admin(r);
        await(writer.submit(c -> {
            c.commit();
            StorageAdmin.vacuum(db);
            return null;
        }));
        return Response.noContent();
    }

    private Response backup(Request r) throws Exception {
        admin(r);
        java.nio.file.Path target = db.file().resolveSibling("overseer-backup-" + System.nanoTime() + ".db");
        try {
            await(writer.submit(c -> {
                c.commit();
                StorageAdmin.backup(db, target);
                return null;
            }));
            byte[] bytes = java.nio.file.Files.readAllBytes(target);
            String name = "overseer-" + java.time.LocalDate.now(clock) + ".db";
            return Response.bytes(200, "application/vnd.sqlite3", bytes)
                    .header("Content-Disposition", "attachment; filename=\"" + name + "\"");
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        try {
            return future.get(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private Principal principal(Request r) throws SQLException {
        return auth.authenticate(r.bearerToken().orElse(null), r.cookie(Cookies.SESSION).orElse(null))
                .orElseThrow(HttpError::unauthorized);
    }

    /** Device tokens are read-only and cannot manage credentials. */
    private static Principal humanOnly(Principal p) {
        if (p.deviceToken()) throw HttpError.forbidden("Not available with a device token.");
        return p;
    }
}
