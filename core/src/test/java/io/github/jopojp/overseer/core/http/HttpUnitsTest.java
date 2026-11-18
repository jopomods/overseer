package io.github.jopojp.overseer.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Router, client address, origin check, cookies and static files. */
class HttpUnitsTest {
    private static Request request(String method, String path) {
        return new Request(method, path, Map.of(), Map.of(), Map.of(), new byte[0], "127.0.0.1", false, Map.of());
    }

    // --- Router -----------------------------------------------------------------------

    @Test
    void routerBindsPathParameters() throws Exception {
        var router = new Router().get("/api/v1/items/{id}", r -> Response.json(r.pathParam("id")));
        var match = router.match(request("GET", "/api/v1/items/42")).orElseThrow();
        assertEquals("42", match.request().pathParam("id"));
    }

    @Test
    void routerReturns405ForWrongMethodAndEmptyForUnknownPath() {
        var router = new Router().get("/api/v1/me", r -> Response.noContent());
        var error = assertThrows(HttpError.class, () -> router.match(request("POST", "/api/v1/me")));
        assertEquals(405, error.status());
        assertTrue(router.match(request("GET", "/api/v1/nope")).isEmpty());
    }

    @Test
    void routerPrefersFirstMatchingRoute() throws Exception {
        var router = new Router()
                .post("/api/v1/me/credentials/revoke-all", r -> Response.json("all"))
                .delete("/api/v1/me/credentials/{id}", r -> Response.json("one"));
        assertTrue(router.match(request("POST", "/api/v1/me/credentials/revoke-all")).isPresent());
        assertTrue(router.match(request("DELETE", "/api/v1/me/credentials/7")).isPresent());
    }

    // --- Client address and HTTPS detection ---------------------------------------------

    @Test
    void forwardedForIsIgnoredFromUntrustedPeers() {
        assertEquals("203.0.113.9", ClientAddress.resolve("203.0.113.9", "1.2.3.4", Set.of("127.0.0.1")));
    }

    @Test
    void forwardedForFromTrustedProxyUsesRightmostUntrustedHop() {
        // Client spoofs "6.6.6.6"; the proxy appends the real address.
        assertEquals("198.51.100.7",
                ClientAddress.resolve("127.0.0.1", "6.6.6.6, 198.51.100.7", Set.of("127.0.0.1")));
    }

    @Test
    void httpsOnlyBelievedFromTrustedProxy() {
        assertTrue(ClientAddress.isSecure("127.0.0.1", "https", Set.of("127.0.0.1"), false));
        assertFalse(ClientAddress.isSecure("203.0.113.9", "https", Set.of("127.0.0.1"), false));
        assertTrue(ClientAddress.isSecure("203.0.113.9", null, Set.of(), true));
    }

    // --- Origin check -------------------------------------------------------------------

    @Test
    void originMustMatchHost() {
        assertTrue(OriginCheck.sameOrigin("http://127.0.0.1:8765", "127.0.0.1:8765", false));
        assertTrue(OriginCheck.sameOrigin("https://dash.example.de", "dash.example.de", true));
        assertFalse(OriginCheck.sameOrigin("https://evil.example", "dash.example.de", true));
        assertFalse(OriginCheck.sameOrigin("http://127.0.0.1:9999", "127.0.0.1:8765", false));
        assertFalse(OriginCheck.sameOrigin(null, "127.0.0.1:8765", false));
        assertFalse(OriginCheck.sameOrigin("null", "127.0.0.1:8765", false));
    }

    @Test
    void onlyCookieAuthenticatedWritesAreChecked() {
        assertTrue(OriginCheck.needsCheck("POST", false));
        assertFalse(OriginCheck.needsCheck("POST", true));
        assertFalse(OriginCheck.needsCheck("GET", false));
    }

    // --- Cookies ------------------------------------------------------------------------

    @Test
    void cookiesAreParsedAndSetWithSafeAttributes() {
        assertEquals(Map.of("a", "1", "ov_session", "xyz"), Cookies.parse("a=1; ov_session=xyz"));
        String set = Cookies.set("ov_session", "t", "/", java.time.Duration.ofDays(1), true);
        assertTrue(set.contains("HttpOnly") && set.contains("SameSite=Strict") && set.contains("Secure"));
        assertFalse(Cookies.set("x", "t", "/", java.time.Duration.ofDays(1), false).contains("Secure"));
    }

    // --- Static files -------------------------------------------------------------------

    private final StaticFiles files = new StaticFiles("test-web", getClass().getClassLoader());

    @Test
    void servesIndexAndHashedAssetsWithCaching() {
        var index = files.serve("/").orElseThrow();
        assertEquals("no-cache", index.headers().get("Cache-Control"));
        assertTrue(index.headers().containsKey("Content-Security-Policy"));

        var asset = files.serve("/assets/app-abc123.js").orElseThrow();
        assertTrue(asset.headers().get("Content-Type").startsWith("text/javascript"));
        assertTrue(asset.headers().get("Cache-Control").contains("immutable"));
    }

    @Test
    void unknownRoutesFallBackToIndexButMissingFilesDoNot() {
        assertTrue(files.serve("/energy/networks/3").isPresent(), "client-side route");
        var grid = files.serve("/ae2/minecraft:overworld@122,73,-16").orElseThrow();
        assertTrue(new String(grid.body()).contains("Overseer test"), "routes with ids like dimension@x,y,z reload");
        assertTrue(files.serve("/assets/missing.js").isEmpty());
    }

    @Test
    void rejectsTraversalAndOddCharacters() {
        for (String path : List.of("/../secret.txt", "/assets/../../x", "/%2e%2e/x", "/a\\b", "//etc/passwd",
                "/io/github/jopojp/overseer/core/Overseer.class")) {
            var served = files.serve(path);
            assertTrue(served.isEmpty() || new String(served.get().body()).contains("Overseer test"),
                    "must not escape the bundle: " + path);
        }
    }
}
