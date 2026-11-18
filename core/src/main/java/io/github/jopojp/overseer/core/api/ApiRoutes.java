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
import io.github.jopojp.overseer.core.pipeline.InventoryState;
import io.github.jopojp.overseer.core.pipeline.LatestState;
import io.github.jopojp.overseer.core.pipeline.SnapshotQueue;
import io.github.jopojp.overseer.core.provider.ProviderDescriptor;
import io.github.jopojp.overseer.core.provider.ProviderSnapshot;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.AccessRequestStore;
import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.storage.EventQuery;
import io.github.jopojp.overseer.core.storage.HistoryQuery;
import io.github.jopojp.overseer.core.storage.InventoryQuery;
import io.github.jopojp.overseer.core.storage.NotificationStore;
import io.github.jopojp.overseer.core.storage.OverrideStore;
import io.github.jopojp.overseer.core.storage.RegistrationStore;
import io.github.jopojp.overseer.core.storage.Sessions;
import io.github.jopojp.overseer.core.storage.ShareStore;
import io.github.jopojp.overseer.core.storage.StorageAdmin;
import io.github.jopojp.overseer.core.storage.StorageWriter;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
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
    private final InventoryState inventories;
    private final SnapshotQueue queue;
    private final List<ProviderDescriptor> providers;
    private final Supplier<List<CollectorStatus>> collectors;
    private final Clock clock;
    private final Database db;
    private final LongSupplier uptime;
    private final OverrideStore overrides;
    private final ShareStore shares;
    private final AccessRequestStore requests;
    private final NotificationStore notifications;
    private final RegistrationStore registrations;
    private final io.github.jopojp.overseer.core.storage.WatchStore watches;
    private final io.github.jopojp.overseer.core.storage.IconStore icons;
    private final io.github.jopojp.overseer.core.storage.ProfilerStore profiler;
    private final StorageWriter writer;

    private final RateLimiter linkCreate = RateLimiter.perMinute(10);
    private final RateLimiter linkPoll = RateLimiter.perMinute(90);
    private final RateLimiter tokenLogin = RateLimiter.perMinute(20);
    private final RateLimiter requestLimit = RateLimiter.perMinute(10);

    public ApiRoutes(
            String version,
            AuthService auth,
            AccessService access,
            LatestState latest,
            InventoryState inventories,
            SnapshotQueue queue,
            List<ProviderDescriptor> providers,
            Supplier<List<CollectorStatus>> collectors,
            Clock clock,
            Database db,
            LongSupplier uptime,
            OverrideStore overrides,
            ShareStore shares,
            AccessRequestStore requests,
            NotificationStore notifications,
            RegistrationStore registrations,
            io.github.jopojp.overseer.core.storage.WatchStore watches,
            io.github.jopojp.overseer.core.storage.IconStore icons,
            io.github.jopojp.overseer.core.storage.ProfilerStore profiler,
            StorageWriter writer) {
        this.version = version;
        this.auth = auth;
        this.access = access;
        this.latest = latest;
        this.inventories = inventories;
        this.queue = queue;
        this.providers = List.copyOf(providers);
        this.collectors = collectors;
        this.clock = clock;
        this.db = db;
        this.uptime = uptime;
        this.overrides = overrides;
        this.shares = shares;
        this.requests = requests;
        this.notifications = notifications;
        this.registrations = registrations;
        this.watches = watches;
        this.icons = icons;
        this.profiler = profiler;
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
                .get("/api/v1/inventory", this::inventory)
                .get("/api/v1/inventory/history", this::inventoryHistory)
                .get("/api/v1/events", this::events)
                .get("/api/v1/jobs", this::job)
                .get("/api/v1/watches", this::listWatches)
                .get("/api/v1/icon", this::icon)
                .get("/api/v1/details", this::details)
                .get("/api/v1/admin/profiler", this::getProfiler)
                .put("/api/v1/admin/profiler", this::setProfiler)
                .post("/api/v1/watches", this::addWatch)
                .delete("/api/v1/watches", this::removeWatch)
                .get("/api/v1/overrides", this::listOverrides)
                .put("/api/v1/overrides", this::setOverride)
                .get("/api/v1/shares", this::listShares)
                .post("/api/v1/shares", this::addShare)
                .delete("/api/v1/shares", this::removeShare)
                .get("/api/v1/access/requestable", this::listRequestable)
                .get("/api/v1/access/requests", this::listRequests)
                .post("/api/v1/access/requests", this::createRequest)
                .post("/api/v1/access/decisions", this::decide)
                .post("/api/v1/registrations", this::register)
                .delete("/api/v1/registrations", this::unregister)
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

    /** Lines of the running job of a resource (live, never stored); 404 when idle or invisible. */
    private Response job(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        var job = inventories.job(ref).orElseThrow(HttpError::notFound);
        var lines = job.lines().get(ref).stream()
                .map(l -> new Dtos.JobLine(l.key(), l.kind(), l.name(), l.stored(), l.active(), l.pending()))
                .toList();
        return Response.json(new Dtos.Job(ref.key(), Dtos.timestamp(job.at()), lines));
    }

    /** The live table of a resource (e.g. the profiler's top list); 404 when none or not visible. */
    private Response details(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        var details = inventories.details(ref).orElseThrow(HttpError::notFound);
        return Response.json(new Dtos.Details(ref.key(), Dtos.timestamp(details.at()), details.tables().get(ref)));
    }

    // --- tick profiler (ADR-0014) ----------------------------------------------------------

    private Dtos.Profiler profilerDto() {
        var s = profiler.current();
        return new Dtos.Profiler(s.mode().name().toLowerCase(java.util.Locale.ROOT),
                s.mode() == io.github.jopojp.overseer.core.storage.ProfilerStore.Mode.TIMED
                        ? java.time.Instant.ofEpochMilli(s.untilWallMs()) : null,
                s.intervalSeconds(), s.active(clock.instant()));
    }

    private Response getProfiler(Request r) throws SQLException {
        admin(r);
        return Response.json(profilerDto());
    }

    /** Start (timed or continuous) or stop the tick profiler. Admins only. */
    private Response setProfiler(Request r) throws SQLException {
        admin(r);
        var body = Json.parse(r.bodyText(), Dtos.SetProfiler.class);
        if (body.mode() == null) throw HttpError.badRequest("mode is required.");
        var mode = switch (body.mode()) {
            case "off" -> io.github.jopojp.overseer.core.storage.ProfilerStore.Mode.OFF;
            case "timed" -> io.github.jopojp.overseer.core.storage.ProfilerStore.Mode.TIMED;
            case "continuous" -> io.github.jopojp.overseer.core.storage.ProfilerStore.Mode.CONTINUOUS;
            default -> throw HttpError.badRequest("mode must be off, timed or continuous.");
        };
        int interval = body.intervalSeconds() == null ? profiler.current().intervalSeconds() : body.intervalSeconds();
        long until = 0;
        if (mode == io.github.jopojp.overseer.core.storage.ProfilerStore.Mode.TIMED) {
            long duration = body.durationSeconds() == null ? 0 : body.durationSeconds();
            if (duration < 10 || duration > io.github.jopojp.overseer.core.storage.ProfilerStore.MAX_DURATION_SECONDS) {
                throw HttpError.badRequest("durationSeconds must be 10 to 86400.");
            }
            until = clock.instant().toEpochMilli() + duration * 1000;
        }
        try {
            profiler.set(new io.github.jopojp.overseer.core.storage.ProfilerStore.Settings(mode, until, interval));
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest(e.getMessage());
        }
        return Response.json(profilerDto());
    }

    /** Icon of a stack key as PNG. Missing icons are cached too, for an hour. */
    private Response icon(Request r) throws SQLException {
        principal(r);
        String key = r.queryParam("key").orElseThrow(() -> HttpError.badRequest("key is required."));
        if (key.length() > 512) throw HttpError.badRequest("key is too long.");
        var png = icons.find(key);
        if (png.isEmpty()) {
            return Response.error(HttpError.notFound()).header("Cache-Control", "private, max-age=3600");
        }
        return Response.bytes(200, "image/png", png.get()).header("Cache-Control", "private, max-age=86400");
    }

    // --- watchlist (ADR-0011) -------------------------------------------------------------

    private Response listWatches(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        var list = watches.list(ref);
        Map<String, InventoryQuery.Change> changes = Map.of();
        if (!list.isEmpty()) {
            long now = uptime.getAsLong();
            try (var reader = db.openReader()) {
                changes = InventoryQuery.changes(reader, ref, now - CHANGE_RECENT, now - CHANGE_LONGER);
            }
        }
        var byKey = changes;
        return Response.json(list.stream().map(w -> {
            var c = byKey.get(w.item());
            return new Dtos.Watch(w.item(), w.name(), w.kind(), w.target(), w.createdAt(),
                    c == null ? 0 : c.recent(), c == null ? 0 : c.longer());
        }).toList());
    }

    /** Watches an item that is (or was) in the inventory, or changes its target. Owners only. */
    private Response addWatch(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var body = Json.parse(r.bodyText(), Dtos.AddWatch.class);
        if (body.resource() == null || body.item() == null) throw HttpError.badRequest("resource and item are required.");
        ResourceRef ref = parseRef(body.resource());
        if (!access.canView(p, ref)) throw HttpError.notFound();
        if (!access.canEdit(p, ref)) throw HttpError.forbidden("Only owners may change the watchlist.");
        if (body.target() != null && body.target() < 0) throw HttpError.badRequest("target must be >= 0.");
        var known = inventories.get(ref).flatMap(scan -> scan.stacks().stream()
                .filter(s -> s.key().equals(body.item())).findFirst());
        String name;
        String kind;
        if (known.isPresent()) {
            name = known.get().name();
            kind = known.get().kind();
        } else {
            var existing = watches.list(ref).stream().filter(w -> w.item().equals(body.item())).findFirst();
            if (existing.isEmpty()) throw HttpError.badRequest("Unknown item: it is not in this inventory.");
            name = existing.get().name();
            kind = existing.get().kind();
        }
        boolean isNew = watches.list(ref).stream().noneMatch(w -> w.item().equals(body.item()));
        if (isNew && watches.list(ref).size() >= io.github.jopojp.overseer.core.storage.WatchStore.MAX_PER_RESOURCE) {
            throw HttpError.badRequest("At most " + io.github.jopojp.overseer.core.storage.WatchStore.MAX_PER_RESOURCE
                    + " watched items per network.");
        }
        watches.put(ref, body.item(), name, kind, body.target(), p.player().uuid(), clock.instant());
        return Response.noContent();
    }

    private Response removeWatch(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        String item = r.queryParam("item").orElseThrow(() -> HttpError.badRequest("item is required."));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        if (!access.canEdit(p, ref)) throw HttpError.forbidden("Only owners may change the watchlist.");
        if (!watches.remove(ref, item)) throw HttpError.notFound();
        return Response.noContent();
    }

    static final int MAX_EVENTS = 500;

    /** Recorded changes of one resource, newest first, e.g. the job history of a crafting CPU. */
    private Response events(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        List<String> attributes = r.query().getOrDefault("attribute", List.of());
        if (attributes.size() > 20) throw HttpError.badRequest("Give at most 20 attribute parameters.");
        int limit = (int) longParam(r, "limit", 100);
        if (limit < 1 || limit > MAX_EVENTS) throw HttpError.badRequest("limit must be 1 to " + MAX_EVENTS + ".");
        try (var reader = db.openReader()) {
            return Response.json(EventQuery.events(reader, ref, attributes, limit).stream()
                    .map(e -> new Dtos.Event(Dtos.timestamp(e.at()), e.kind(), e.attribute(), e.value()))
                    .toList());
        }
    }

    static final int MAX_INVENTORY_PAGE = 500;
    static final long CHANGE_RECENT = 3_600_000L;
    static final long CHANGE_LONGER = 24 * 3_600_000L;
    private static final java.util.Set<String> INVENTORY_KINDS = java.util.Set.of("item", "fluid", "other");
    private static final java.util.Comparator<Dtos.InventoryItem> BY_NAME = java.util.Comparator
            .comparing((Dtos.InventoryItem i) -> plain(i.name()).toLowerCase(java.util.Locale.ROOT))
            .thenComparing(Dtos.InventoryItem::key);

    /** How one item's amount moved, minute by minute, for any item (watched or not). */
    private Response inventoryHistory(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        String item = r.queryParam("item").orElseThrow(() -> HttpError.badRequest("item is required."));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        long to = longParam(r, "to", uptime.getAsLong());
        long from = longParam(r, "from", to - CHANGE_LONGER);
        if (to <= from) throw HttpError.badRequest("to must be after from.");
        InventoryQuery.ItemHistory history;
        try (var reader = db.openReader()) {
            history = InventoryQuery.history(reader, ref, item, Math.max(0, from), to);
        }
        Long now = inventories.get(ref).flatMap(scan -> scan.stacks().stream()
                .filter(s -> s.key().equals(item)).findFirst()).map(s -> s.amount()).orElse(null);
        return Response.json(new Dtos.InventoryHistory(ref.key(), item, from, to, history.start(), now, history.points()));
    }

    /** Current contents from the newest scan, with net changes from the database (ADR-0011). */
    private Response inventory(Request r) throws SQLException {
        Principal p = principal(r);
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (!access.canView(p, ref)) throw HttpError.notFound();
        String q = r.queryParam("q").map(s -> s.strip().toLowerCase(java.util.Locale.ROOT)).orElse("");
        String kind = r.queryParam("kind").orElse(null);
        if (kind != null && !INVENTORY_KINDS.contains(kind)) throw HttpError.badRequest("kind must be item, fluid or other.");
        String sort = r.queryParam("sort").orElse("amount");
        java.util.Comparator<Dtos.InventoryItem> order = switch (sort) {
            case "amount" -> java.util.Comparator.comparingLong(Dtos.InventoryItem::amount).reversed().thenComparing(BY_NAME);
            case "name" -> BY_NAME;
            case "change" -> java.util.Comparator.comparingLong((Dtos.InventoryItem i) -> -Math.abs(i.change24h()))
                    .thenComparingLong(i -> -Math.abs(i.change1h())).thenComparing(BY_NAME);
            default -> throw HttpError.badRequest("sort must be amount, name or change.");
        };
        int limit = (int) longParam(r, "limit", 100);
        int offset = (int) longParam(r, "offset", 0);
        if (limit < 1 || limit > MAX_INVENTORY_PAGE) throw HttpError.badRequest("limit must be 1 to " + MAX_INVENTORY_PAGE + ".");
        if (offset < 0) throw HttpError.badRequest("offset must be >= 0.");

        long now = uptime.getAsLong();
        Map<String, InventoryQuery.Change> changes;
        try (var reader = db.openReader()) {
            changes = InventoryQuery.changes(reader, ref, now - CHANGE_RECENT, now - CHANGE_LONGER);
        }
        var scan = inventories.get(ref);
        var items = new java.util.ArrayList<Dtos.InventoryItem>();
        var seen = new java.util.HashSet<String>();
        for (var stack : scan.map(InventoryState.Scan::stacks).orElse(List.of())) {
            if (!seen.add(stack.key())) continue;
            var change = changes.get(stack.key());
            items.add(new Dtos.InventoryItem(stack.key(), stack.kind(), stack.name(), stack.amount(),
                    change == null ? 0 : change.recent(), change == null ? 0 : change.longer()));
        }
        // Keys used up completely: no longer in the scan, but they changed.
        changes.forEach((key, change) -> {
            if (!seen.contains(key)) items.add(new Dtos.InventoryItem(key, change.kind(), change.name(), 0,
                    change.recent(), change.longer()));
        });
        var matching = items.stream()
                .filter(i -> kind == null || kind.equals(i.kind()))
                .filter(i -> q.isEmpty() || plain(i.name()).toLowerCase(java.util.Locale.ROOT).contains(q)
                        || i.key().contains(q))
                .sorted(order)
                .toList();
        var page = matching.subList(Math.min(offset, matching.size()), Math.min(offset + limit, matching.size()));
        return Response.json(new Dtos.Inventory(ref.key(),
                scan.map(s -> Dtos.timestamp(s.at())).orElse(null), matching.size(), List.copyOf(page)));
    }

    public static final int MAX_OVERRIDE_LENGTH = 48;

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

    /** The resource of a share request; the caller must be allowed to manage its shares. */
    private ResourceRef manageableShare(Principal p, String resource) {
        if (resource == null) throw HttpError.badRequest("resource is required.");
        ResourceRef ref = parseRef(resource);
        if (!access.canView(p, ref)) throw HttpError.notFound();
        if (!access.isShareable(ref)) throw HttpError.badRequest("This resource cannot be shared in Overseer.");
        if (!access.canEdit(p, ref)) throw HttpError.forbidden("Only the owner may manage access.");
        return ref;
    }

    private Response listShares(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        ResourceRef ref = manageableShare(p, r.queryParam("resource").orElse(null));
        return Response.json(shares.list(ref).stream().map(Dtos::share).toList());
    }

    private Response addShare(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var body = Json.parse(r.bodyText(), Dtos.AddShare.class);
        ResourceRef ref = manageableShare(p, body.resource());
        String name = body.name() == null ? "" : body.name().strip();
        if (name.isEmpty() || name.length() > 16) throw HttpError.badRequest("name must be a player name.");
        UUID player = shares.playerByName(name).orElseThrow(() -> HttpError.badRequest(
                "Unknown player. They must log in to the dashboard once (/ov link) before you can share with them."));
        shares.add(ref, player, p.player().uuid(), clock.instant());
        return Response.json(201, shares.list(ref).stream()
                .filter(s -> s.player().equals(player)).findFirst().map(Dtos::share).orElseThrow());
    }

    /** Owners remove anyone; a player may also remove their own share (leave). */
    private Response removeShare(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        UUID player;
        try {
            player = UUID.fromString(r.queryParam("player").orElseThrow(() -> HttpError.badRequest("player is required.")));
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest("player must be a UUID.");
        }
        if (!player.equals(p.player().uuid())) manageableShare(p, ref.key());
        if (!shares.remove(ref, player)) throw HttpError.notFound();
        return Response.noContent();
    }

    // --- access requests (ADR-0011) ------------------------------------------------------

    /** Shareable resources of a provider that the caller cannot see and that have an owner. */
    private List<Map.Entry<ResourceRef, Map<String, String>>> requestable(Principal p, String provider) {
        var snapshot = latest.get(provider).orElse(null);
        if (snapshot == null) return List.of();
        return snapshot.resources().entrySet().stream()
                .filter(e -> access.isShareable(e.getKey()))
                .filter(e -> !e.getValue().getOrDefault("owner", "").isEmpty())
                .filter(e -> !access.canView(p, e.getKey()))
                .toList();
    }

    /** The Overseer name of a resource (override), else its own name attribute, else null. */
    private String resourceName(ResourceRef ref, Map<String, String> attributes) throws SQLException {
        for (var o : overrides.list(ref.provider())) {
            if (o.resource().equals(ref) && o.key().equals("name")) return o.value();
        }
        String name = attributes == null ? null : attributes.get("name");
        return name == null || name.isEmpty() ? null : name;
    }

    private Response listRequestable(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        String provider = r.queryParam("provider").orElseThrow(() -> HttpError.badRequest("provider is required."));
        var result = new java.util.ArrayList<Dtos.RequestableDto>();
        for (var e : requestable(p, provider)) {
            var status = requests.status(e.getKey(), p.player().uuid(), clock.instant());
            result.add(new Dtos.RequestableDto(provider, requests.handle(e.getKey()), resourceName(e.getKey(), e.getValue()),
                    e.getValue().getOrDefault("ownerName", ""), status.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return Response.json(result);
    }

    private Response createRequest(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        if (!requestLimit.tryAcquire(p.player().uuid().toString())) throw HttpError.rateLimited();
        var body = Json.parse(r.bodyText(), Dtos.CreateRequest.class);
        if (body.provider() == null || body.handle() == null) throw HttpError.badRequest("provider and handle are required.");
        var match = requestable(p, body.provider()).stream()
                .filter(e -> requests.handle(e.getKey()).equals(body.handle()))
                .findFirst()
                .orElseThrow(HttpError::notFound);
        ResourceRef ref = match.getKey();
        var outcome = requests.request(ref, p.player().uuid(), clock.instant());
        if (outcome == AccessRequestStore.Outcome.DENIED_RECENTLY) {
            throw HttpError.conflict("The owner declined recently. Try again later.");
        }
        if (outcome == AccessRequestStore.Outcome.CREATED) {
            UUID owner = UUID.fromString(match.getValue().get("owner"));
            String name = resourceName(ref, match.getValue());
            notifications.add(owner, "[Overseer] " + p.player().name() + " asks for access to "
                    + (name == null ? "your " + ref.type() + " at " + ref.id() : "\"" + name + "\"")
                    + ". Decide in the dashboard.", clock.instant());
        }
        return Response.json(201, Map.of("request", "pending"));
    }

    /** Pending requests on resources the caller may manage. */
    private Response listRequests(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var result = new java.util.ArrayList<Dtos.PendingRequestDto>();
        for (var pending : requests.pending()) {
            ResourceRef ref = pending.resource();
            if (!access.isShareable(ref) || !access.canEdit(p, ref)) continue;
            var attributes = latest.get(ref.provider()).map(s -> s.resources().get(ref)).orElse(null);
            result.add(new Dtos.PendingRequestDto(ref.key(), resourceName(ref, attributes), pending.player().toString(),
                    pending.name(), pending.requestedAt().toString()));
        }
        return Response.json(result);
    }

    private Response decide(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        var body = Json.parse(r.bodyText(), Dtos.Decision.class);
        if (body.resource() == null || body.player() == null || body.approve() == null) {
            throw HttpError.badRequest("resource, player and approve are required.");
        }
        ResourceRef ref = manageableShare(p, body.resource());
        UUID player;
        try {
            player = UUID.fromString(body.player());
        } catch (IllegalArgumentException e) {
            throw HttpError.badRequest("player must be a UUID.");
        }
        if (requests.find(ref, player).isEmpty()) throw HttpError.notFound();
        var attributes = latest.get(ref.provider()).map(s -> s.resources().get(ref)).orElse(null);
        String name = resourceName(ref, attributes);
        String what = name == null ? "a " + ref.type() : "\"" + name + "\"";
        if (body.approve()) {
            shares.add(ref, player, p.player().uuid(), clock.instant());
            requests.approve(ref, player);
            notifications.add(player, "[Overseer] " + p.player().name() + " gave you access to " + what + ".", clock.instant());
        } else {
            requests.deny(ref, player, clock.instant());
            notifications.add(player, "[Overseer] Your access request for " + what + " was declined.", clock.instant());
        }
        return Response.noContent();
    }

    // --- registrations (ADR-0011) ---------------------------------------------------------

    /** Admins adopt a hidden resource (e.g. an AE2 grid without controller); they become its owner. */
    private Response register(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        if (!p.isAdmin()) throw HttpError.forbidden("Only dashboard admins may adopt networks.");
        var body = Json.parse(r.bodyText(), Dtos.Register.class);
        if (body.resource() == null) throw HttpError.badRequest("resource is required.");
        ResourceRef ref = parseRef(body.resource());
        if (!access.isShareable(ref)) throw HttpError.badRequest("This resource cannot be registered.");
        boolean exists = latest.get(ref.provider()).map(s -> s.resources().containsKey(ref)).orElse(false);
        if (!exists) throw HttpError.notFound();
        String name = body.name() == null ? null : body.name().strip();
        if (name != null && name.length() > MAX_OVERRIDE_LENGTH) {
            throw HttpError.badRequest("Name must be at most " + MAX_OVERRIDE_LENGTH + " characters.");
        }
        registrations.put(ref, p.player().uuid(), clock.instant());
        if (name != null && !name.isEmpty()) overrides.set(ref, "name", name, p.player().uuid().toString(), clock.instant());
        return Response.noContent();
    }

    /** The owner or an admin turns a registered resource back into a hidden one. */
    private Response unregister(Request r) throws SQLException {
        Principal p = humanOnly(principal(r));
        ResourceRef ref = parseRef(r.queryParam("resource").orElseThrow(() -> HttpError.badRequest("resource is required.")));
        if (registrations.find(ref).isEmpty() || !access.canView(p, ref)) throw HttpError.notFound();
        if (!access.canEdit(p, ref)) throw HttpError.forbidden("Only the owner may remove the registration.");
        registrations.remove(ref);
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
                usage.providers().stream().map(u -> new Dtos.ProviderUsageDto(u.provider(), u.series(), u.raw(), u.minute(), u.hour(),
                        u.inventoryChanges())).toList(), usage.icons(), usage.iconBytes()));
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

    private static final java.util.regex.Pattern FORMATTING = java.util.regex.Pattern.compile("(?i)§[0-9a-fk-orx]");

    /** A game name without legacy formatting codes ({@code §5EV Energy Hatch} -> {@code EV Energy Hatch}). */
    static String plain(String name) {
        return FORMATTING.matcher(name).replaceAll("");
    }

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
