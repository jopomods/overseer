package io.github.jopojp.overseer.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jopojp.overseer.core.OverseerRuntime;
import io.github.jopojp.overseer.core.auth.PlayerRef;
import io.github.jopojp.overseer.core.http.HttpConfig;
import io.github.jopojp.overseer.core.provider.ResourceRef;
import io.github.jopojp.overseer.core.storage.StorageSettings;
import io.github.jopojp.overseer.core.testing.FakeProvider;
import io.github.jopojp.overseer.core.testing.FakeTime;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** End to end through Netty: login flow, credentials, permissions, SSE, hardening. */
@Timeout(30)
class ApiIntegrationTest {
    private static final PlayerRef ALEX = new PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"), "Alex");
    private static final PlayerRef STEVE = new PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000057e7"), "Steve");
    private static final ResourceRef ALEX_NET = new ResourceRef("flux", "network", "1");
    private static final ResourceRef STEVE_NET = new ResourceRef("flux", "network", "2");

    @TempDir
    Path dir;

    private OverseerRuntime runtime;
    private final Set<UUID> operators = new HashSet<>();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String base;

    @BeforeEach
    void start() throws Exception {
        var flux = new FakeProvider("flux", 1, 50_000_000, new FakeTime())
                .resource(ALEX_NET, Map.of("name", "Alex Base"))
                .resource(STEVE_NET, Map.of("name", "Steve Base"))
                .power(ALEX_NET, 100)
                .power(STEVE_NET, 200)
                .policy((player, ref) -> ref.equals(player.equals(ALEX.uuid()) ? ALEX_NET : STEVE_NET));
        var config = new OverseerRuntime.Config(dir, HttpConfig.DEFAULTS.withPort(0), StorageSettings.DEFAULTS,
                Duration.ofDays(30), 64, "0.0.0-test", "test-web");
        runtime = OverseerRuntime.start(config, List.of(flux), operators::contains);
        base = "http://127.0.0.1:" + runtime.httpPort();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    // --- helpers ------------------------------------------------------------------------

    private final class Browser {
        final Map<String, String> cookies = new HashMap<>();

        HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (!cookies.isEmpty()) {
                builder.header("Cookie", String.join("; ", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            if (!method.equals("GET")) builder.header("Origin", base);
            headers.forEach(builder::setHeader);
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            for (String setCookie : response.headers().allValues("set-cookie")) {
                String pair = setCookie.split(";")[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty() || setCookie.contains("Max-Age=0")) cookies.remove(name); else cookies.put(name, value);
            }
            return response;
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, Map.of());
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return send("POST", path, body, Map.of("Content-Type", "application/json"));
        }

        /** Full link-code login as {@code player}. */
        Browser loginAs(PlayerRef player) throws Exception {
            var created = post("/api/v1/auth/link-codes", null);
            assertEquals(201, created.statusCode());
            String code = json(created).get("code").getAsString();
            assertEquals("pending", json(get("/api/v1/auth/link-codes/current")).get("status").getAsString());
            runtime.auth().confirmLink(code, player);
            assertEquals("confirmed", json(get("/api/v1/auth/link-codes/current")).get("status").getAsString());
            assertTrue(cookies.containsKey("ov_session"));
            assertFalse(cookies.containsKey("ov_link"));
            return this;
        }
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void collect() {
        for (int i = 0; i < 3; i++) runtime.tick();
    }

    // --- tests --------------------------------------------------------------------------

    @Test
    void healthIsPublicAndUiIsServed() throws Exception {
        var browser = new Browser();
        var health = browser.get("/api/v1/health");
        assertEquals(200, health.statusCode());
        assertEquals("0.0.0-test", json(health).get("version").getAsString());
        assertEquals("no-store", health.headers().firstValue("cache-control").orElseThrow());
        assertEquals("nosniff", health.headers().firstValue("x-content-type-options").orElseThrow());

        var page = browser.get("/energy");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Overseer test"));
    }

    @Test
    void protectedRoutesRequireLogin() throws Exception {
        var browser = new Browser();
        for (String path : List.of("/api/v1/me", "/api/v1/providers", "/api/v1/providers/flux/snapshot",
                "/api/v1/me/credentials", "/api/v1/admin/collectors")) {
            var response = browser.get(path);
            assertEquals(401, response.statusCode(), path);
            assertEquals("unauthorized", json(response).getAsJsonObject("error").get("code").getAsString());
        }
        assertEquals(404, browser.get("/api/v1/nope").statusCode());
    }

    @Test
    void linkCodeLoginAndMe() throws Exception {
        var browser = new Browser().loginAs(ALEX);
        var me = json(browser.get("/api/v1/me"));
        assertEquals("Alex", me.get("name").getAsString());
        assertEquals("player", me.get("role").getAsString());
        assertFalse(me.get("deviceToken").getAsBoolean());
    }

    @Test
    void playersOnlySeeTheirOwnNetworksAdminsSeeAll() throws Exception {
        collect();
        var alex = new Browser().loginAs(ALEX);
        var snapshot = json(alex.get("/api/v1/providers/flux/snapshot"));
        JsonArray resources = snapshot.getAsJsonArray("resources");
        assertEquals(1, resources.size());
        assertEquals("Alex Base", resources.get(0).getAsJsonObject().getAsJsonObject("attributes").get("name").getAsString());
        assertEquals(100, resources.get(0).getAsJsonObject().getAsJsonObject("metrics").get("power").getAsInt());
        assertEquals(403, alex.get("/api/v1/admin/collectors").statusCode());

        operators.add(ALEX.uuid());
        assertEquals(2, json(alex.get("/api/v1/providers/flux/snapshot")).getAsJsonArray("resources").size());
        var collectors = json(alex.get("/api/v1/admin/collectors"));
        assertEquals("flux", collectors.getAsJsonArray("collectors").get(0).getAsJsonObject().get("provider").getAsString());
    }

    @Test
    void deviceTokensAreReadOnlyBearerCredentials() throws Exception {
        collect();
        var alex = new Browser().loginAs(ALEX);
        var created = alex.post("/api/v1/me/device-tokens", "{\"name\":\"Wall tablet\"}");
        assertEquals(201, created.statusCode());
        String token = json(created).get("token").getAsString();

        var tablet = new Browser();
        var bearer = Map.of("Authorization", "Bearer " + token);
        var me = tablet.send("GET", "/api/v1/me", null, bearer);
        assertTrue(json(me).get("deviceToken").getAsBoolean());
        // Bearer requests need no Origin; but device tokens cannot manage credentials.
        var noOrigin = HttpRequest.newBuilder(URI.create(base + "/api/v1/me/device-tokens"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build();
        assertEquals(403, client.send(noOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());

        assertEquals(400, alex.post("/api/v1/me/device-tokens", "{\"name\":\"\"}").statusCode());
        assertEquals(400, alex.post("/api/v1/me/device-tokens", "not json").statusCode());
    }

    @Test
    void credentialsCanBeListedAndRevoked() throws Exception {
        var phone = new Browser().loginAs(ALEX);
        var laptop = new Browser().loginAs(ALEX);
        var list = JsonParser.parseString(phone.get("/api/v1/me/credentials").body()).getAsJsonArray();
        assertEquals(2, list.size());

        long laptopId = list.asList().stream().map(e -> e.getAsJsonObject())
                .filter(o -> !o.get("current").getAsBoolean()).findFirst().orElseThrow().get("id").getAsLong();
        assertEquals(204, phone.send("DELETE", "/api/v1/me/credentials/" + laptopId, null, Map.of()).statusCode());
        assertEquals(401, laptop.get("/api/v1/me").statusCode());
        assertEquals(200, phone.get("/api/v1/me").statusCode());

        assertEquals(204, phone.post("/api/v1/me/credentials/revoke-all", null).statusCode());
        assertEquals(401, phone.get("/api/v1/me").statusCode());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        var browser = new Browser().loginAs(STEVE);
        String token = browser.cookies.get("ov_session");
        assertEquals(204, browser.post("/api/v1/auth/logout", null).statusCode());
        browser.cookies.put("ov_session", token); // a stolen copy of the cookie is useless now
        assertEquals(401, browser.get("/api/v1/me").statusCode());
    }

    @Test
    void consoleLoginLinkSetsSessionAndRedirects() throws Exception {
        String token = runtime.auth().issueConsoleLogin(STEVE);
        var browser = new Browser();
        var response = browser.get("/api/v1/auth/login?token=" + token);
        assertEquals(302, response.statusCode());
        assertEquals("/", response.headers().firstValue("location").orElseThrow());
        assertEquals("Steve", json(browser.get("/api/v1/me")).get("name").getAsString());
        assertEquals(401, new Browser().get("/api/v1/auth/login?token=" + token).statusCode(), "single use");
    }

    @Test
    void crossOriginWritesAreRejected() throws Exception {
        var browser = new Browser().loginAs(ALEX);
        var forged = browser.send("POST", "/api/v1/me/credentials/revoke-all", null,
                Map.of("Origin", "https://evil.example"));
        assertEquals(403, forged.statusCode());
        assertEquals(200, browser.get("/api/v1/me").statusCode());
    }

    @Test
    void oversizedRequestsAreRejected() throws Exception {
        var browser = new Browser().loginAs(ALEX);
        var big = "{\"name\":\"" + "x".repeat(100_000) + "\"}";
        assertEquals(413, browser.post("/api/v1/me/device-tokens", big).statusCode());
    }

    @Test
    void linkCodeCreationIsRateLimited() throws Exception {
        var browser = new Browser();
        int status = 0;
        for (int i = 0; i < 12; i++) status = browser.post("/api/v1/auth/link-codes", null).statusCode();
        assertEquals(429, status);
    }

    @Test
    void historyIsFilteredByPermissionAndValidated() throws Exception {
        var alex = new Browser().loginAs(ALEX);
        String own = "/api/v1/history?resource=flux/network/1&metric=power&from=0&to=600000&points=60";
        // The storage writer runs asynchronously; wait until the samples are persisted.
        JsonObject history = null;
        for (int i = 0; i < 50; i++) {
            collect();
            history = json(alex.get(own));
            if (!history.getAsJsonArray("series").get(0).getAsJsonObject().getAsJsonArray("points").isEmpty()) break;
            Thread.sleep(100);
        }
        assertEquals("raw", history.get("resolution").getAsString());
        var point = history.getAsJsonArray("series").get(0).getAsJsonObject().getAsJsonArray("points").get(0)
                .getAsJsonObject();
        assertEquals(100, point.get("avg").getAsInt());
        assertNotNull(point.get("wall"));

        assertEquals(404, alex.get("/api/v1/history?resource=flux/network/2&metric=power").statusCode(),
                "the other player's network");
        assertEquals(400, alex.get("/api/v1/history?resource=nonsense&metric=power").statusCode());
        assertEquals(400, alex.get("/api/v1/history?resource=flux/network/1").statusCode(), "metric missing");
        assertEquals(400, alex.get("/api/v1/history?resource=flux/network/1&metric=power&points=5").statusCode());
        assertEquals(401, new Browser().get(own).statusCode());

        var sessions = json(alex.get("/api/v1/sessions"));
        assertEquals(1, sessions.getAsJsonArray("sessions").size());
    }

    @Test
    void streamPushesFilteredSnapshots() throws Exception {
        var alex = new Browser().loginAs(ALEX);
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/stream"))
                .header("Cookie", "ov_session=" + alex.cookies.get("ov_session")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/event-stream"));

        CompletableFuture<String> data = CompletableFuture.supplyAsync(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) return line.substring(6);
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        collect();

        String json = data.get(10, TimeUnit.SECONDS);
        assertNotNull(json);
        var snapshot = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("flux", snapshot.get("provider").getAsString());
        assertEquals(1, snapshot.getAsJsonArray("resources").size(), "only Alex's network");
    }
}
