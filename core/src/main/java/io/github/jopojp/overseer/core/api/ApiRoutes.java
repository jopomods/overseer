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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
            Clock clock) {
        this.version = version;
        this.auth = auth;
        this.access = access;
        this.latest = latest;
        this.queue = queue;
        this.providers = List.copyOf(providers);
        this.collectors = collectors;
        this.clock = clock;
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
                .get("/api/v1/admin/collectors", this::collectors);
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
        return Response.json(Dtos.snapshot(access.filter(p, snapshot)));
    }

    // --- admin ---------------------------------------------------------------------------

    private Response collectors(Request r) throws SQLException {
        Principal p = principal(r);
        if (!p.isAdmin()) throw HttpError.forbidden("Admins only.");
        return Response.json(new Dtos.Collectors(collectors.get().stream().map(Dtos::collector).toList(),
                queue.dropped(), queue.size()));
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
