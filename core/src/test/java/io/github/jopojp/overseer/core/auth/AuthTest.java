package io.github.jopojp.overseer.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jopojp.overseer.core.storage.Database;
import io.github.jopojp.overseer.core.testing.FakeTime;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {
    private static final PlayerRef ALEX = new PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"), "Alex");
    private static final PlayerRef STEVE = new PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000057e7"), "Steve");

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime();
    private Database db;
    private AuthStore store;
    private AuthService auth;
    private final Set<UUID> operators = new java.util.HashSet<>();

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.open(dir.resolve("overseer.db"));
        store = new AuthStore(db.openControlWriter(), Duration.ofDays(30));
        auth = new AuthService(store, operators::contains, time.wallClock());
    }

    @AfterEach
    void tearDown() throws SQLException {
        store.close();
        db.close();
    }

    private String login(PlayerRef player) throws SQLException {
        var created = auth.startLink();
        assertEquals(LinkCodes.ConfirmResult.CONFIRMED, auth.confirmLink(created.code(), player));
        return auth.pollLink(created.pollSecret(), "JUnit").session().orElseThrow().token();
    }

    // --- link codes ---------------------------------------------------------------------

    @Test
    void linkCodeLoginIssuesSessionExactlyOnce() throws SQLException {
        var created = auth.startLink();
        assertEquals(LinkCodes.State.PENDING, auth.pollLink(created.pollSecret(), null).state());

        auth.confirmLink(created.code().toLowerCase().replace("-", " "), ALEX); // forgiving input
        var first = auth.pollLink(created.pollSecret(), null);
        assertEquals(LinkCodes.State.CONFIRMED, first.state());
        assertTrue(first.session().isPresent());

        assertEquals(LinkCodes.State.EXPIRED, auth.pollLink(created.pollSecret(), null).state(), "single use");
    }

    @Test
    void knowingTheCodeIsNotEnoughToCollectTheLogin() throws SQLException {
        var created = auth.startLink();
        auth.confirmLink(created.code(), ALEX);
        assertEquals(LinkCodes.State.EXPIRED, auth.pollLink("someone-elses-secret", null).state());
    }

    @Test
    void linkCodesExpireAfterFiveMinutes() throws SQLException {
        var created = auth.startLink();
        time.advance(5, TimeUnit.MINUTES);
        assertEquals(LinkCodes.ConfirmResult.UNKNOWN_OR_EXPIRED, auth.confirmLink(created.code(), ALEX));
    }

    @Test
    void codesAvoidAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            String code = Secrets.newLinkCode();
            assertTrue(code.matches("[A-Z2-9]{3}-[A-Z2-9]{3}"), code);
            assertFalse(code.matches(".*[01OIL].*"), code);
        }
    }

    // --- sessions -----------------------------------------------------------------------

    @Test
    void tokensAreStoredOnlyAsHashes() throws SQLException {
        String token = login(ALEX);
        try (var reader = db.openReader(); var st = reader.createStatement();
                var rs = st.executeQuery("SELECT token_hash FROM credential")) {
            rs.next();
            assertEquals(Secrets.sha256(token), rs.getString(1));
        }
    }

    @Test
    void sessionsSlideAndExpireWhenIdle() throws SQLException {
        String token = login(ALEX);
        time.advance(29, TimeUnit.DAYS);
        assertTrue(auth.authenticate(null, token).isPresent(), "use refreshes the session");
        time.advance(29, TimeUnit.DAYS);
        assertTrue(auth.authenticate(null, token).isPresent());
        time.advance(31, TimeUnit.DAYS);
        assertTrue(auth.authenticate(null, token).isEmpty(), "idle for 31 days");
    }

    @Test
    void deviceTokensNeverExpireAndOnlyWorkAsBearer() throws SQLException {
        String session = login(ALEX);
        var principal = auth.authenticate(null, session).orElseThrow();
        String device = auth.createDeviceToken(principal, "Wall tablet").token();

        time.advance(400, TimeUnit.DAYS);
        var viaBearer = auth.authenticate(device, null).orElseThrow();
        assertTrue(viaBearer.deviceToken());
        assertTrue(auth.authenticate(session, null).isEmpty(), "a session token is not a bearer token");
    }

    @Test
    void revokeAffectsOnlyOwnCredentials() throws SQLException {
        String alexToken = login(ALEX);
        String steveToken = login(STEVE);
        var alex = auth.authenticate(null, alexToken).orElseThrow();
        var steve = auth.authenticate(null, steveToken).orElseThrow();

        assertFalse(auth.revoke(alex, steve.credentialId()), "cannot revoke someone else's session");
        assertTrue(auth.revoke(steve, steve.credentialId()));
        assertTrue(auth.authenticate(null, steveToken).isEmpty());
        assertTrue(auth.authenticate(null, alexToken).isPresent());
    }

    @Test
    void logoutEverywhereKeepsDeviceTokens() throws SQLException {
        String a = login(ALEX);
        String b = login(ALEX);
        var principal = auth.authenticate(null, a).orElseThrow();
        String device = auth.createDeviceToken(principal, "Kiosk").token();

        assertEquals(2, auth.revokeAllSessions(ALEX.uuid()));
        assertTrue(auth.authenticate(null, a).isEmpty());
        assertTrue(auth.authenticate(null, b).isEmpty());
        assertTrue(auth.authenticate(device, null).isPresent());
    }

    @Test
    void consoleLoginTokenIsSingleUse() throws SQLException {
        String token = auth.issueConsoleLogin(ALEX);
        String cookie = auth.exchangeLoginToken(token, "browser").orElseThrow();
        assertEquals("Alex", auth.authenticate(null, cookie).orElseThrow().player().name());
        assertTrue(auth.exchangeLoginToken(token, "browser").isEmpty());
    }

    // --- roles --------------------------------------------------------------------------

    @Test
    void rolesComeFromOpListAndAdminGrants() throws SQLException {
        String token = login(ALEX);
        assertEquals(Role.PLAYER, auth.authenticate(null, token).orElseThrow().role());

        auth.grantAdmin(ALEX, "console");
        assertEquals(Role.ADMIN, auth.authenticate(null, token).orElseThrow().role());

        operators.add(ALEX.uuid());
        assertEquals(Role.SUPER_ADMIN, auth.authenticate(null, token).orElseThrow().role());

        operators.clear();
        auth.revokeAdmin(ALEX.uuid());
        var principal = auth.authenticate(null, token).orElseThrow();
        assertEquals(Role.PLAYER, auth.revalidate(principal).orElseThrow().role());
    }

    // --- rate limiter -------------------------------------------------------------------

    @Test
    void rateLimiterRefillsOverTime() {
        var limiter = new RateLimiter(2, time);
        assertTrue(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("other-ip"));
        time.advance(30, TimeUnit.SECONDS);
        assertTrue(limiter.tryAcquire("ip"));
    }
}
