package io.github.jopojp.overseer.core.auth;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication and global roles (ADR-0007, ADR-0008 layer 1). HTTP-agnostic:
 * the web layer extracts tokens from headers and cookies; the platform calls the
 * in-game and console entry points ({@code /ov link}, {@code ov login}, {@code ov admin}).
 *
 * <p>Thread-safe.
 */
public final class AuthService {
    /** Outcome of polling a link code; {@code session} is set once confirmed. */
    public record LinkPoll(LinkCodes.State state, Optional<AuthStore.Issued> session) {}

    private final AuthStore store;
    private final LinkCodes linkCodes = new LinkCodes();
    private final LoginTokens loginTokens = new LoginTokens();
    private final OperatorCheck operators;
    private final Clock clock;

    public AuthService(AuthStore store, OperatorCheck operators, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.operators = Objects.requireNonNull(operators);
        this.clock = Objects.requireNonNull(clock);
    }

    // --- request authentication ---------------------------------------------------------

    /**
     * Authenticates a request. A bearer token, if present, must be a valid device token;
     * there is no fallback to the cookie in that case.
     */
    public Optional<Principal> authenticate(String bearerToken, String sessionCookie) throws SQLException {
        if (bearerToken != null) {
            return store.resolve(bearerToken, clock.instant())
                    .filter(r -> r.kind() == AuthStore.Kind.DEVICE_TOKEN)
                    .map(this::principal);
        }
        return store.resolve(sessionCookie, clock.instant()).map(this::principal);
    }

    /** Re-validates a principal for long-lived connections: still valid, with its current role. */
    public Optional<Principal> revalidate(Principal principal) throws SQLException {
        if (!store.isValid(principal.credentialId(), clock.instant())) return Optional.empty();
        return Optional.of(new Principal(principal.player(), roleOf(principal.player().uuid()),
                principal.credentialId(), principal.deviceToken()));
    }

    public Role roleOf(UUID player) throws SQLException {
        if (operators.isOperator(player)) return Role.SUPER_ADMIN;
        if (store.isDashboardAdmin(player)) return Role.ADMIN;
        return Role.PLAYER;
    }

    private Principal principal(AuthStore.Resolved resolved) {
        try {
            return new Principal(resolved.player(), roleOf(resolved.player().uuid()), resolved.id(),
                    resolved.kind() == AuthStore.Kind.DEVICE_TOKEN);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- link-code login ----------------------------------------------------------------

    public LinkCodes.Created startLink() {
        return linkCodes.create(clock.instant());
    }

    /** {@code /ov link <code>} in game. Records the player. */
    public LinkCodes.ConfirmResult confirmLink(String code, PlayerRef player) throws SQLException {
        var result = linkCodes.confirm(code, player, clock.instant());
        if (result == LinkCodes.ConfirmResult.CONFIRMED) store.upsertPlayer(player, clock.instant());
        return result;
    }

    /** Browser polling with its link cookie; issues the session exactly once. */
    public LinkPoll pollLink(String pollSecret, String userAgent) throws SQLException {
        var status = linkCodes.poll(pollSecret, clock.instant());
        if (status.state() != LinkCodes.State.CONFIRMED) return new LinkPoll(status.state(), Optional.empty());
        PlayerRef player = status.player().orElseThrow();
        return new LinkPoll(status.state(), Optional.of(
                store.issue(AuthStore.Kind.SESSION, player.uuid(), truncate(userAgent), clock.instant())));
    }

    // --- console recovery and kiosk ----------------------------------------------------

    /** {@code ov login <player>} on the console: returns a one-time token for {@code /api/v1/auth/login}. */
    public String issueConsoleLogin(PlayerRef player) throws SQLException {
        store.upsertPlayer(player, clock.instant());
        return loginTokens.issue(player, clock.instant());
    }

    /**
     * Exchanges a one-time console login token for a new session, or accepts a device
     * token (kiosk setup) as cookie value. Returns the token to put into the session cookie.
     */
    public Optional<String> exchangeLoginToken(String token, String userAgent) throws SQLException {
        var player = loginTokens.redeem(token, clock.instant());
        if (player.isPresent()) {
            return Optional.of(store.issue(AuthStore.Kind.SESSION, player.get().uuid(), truncate(userAgent),
                    clock.instant()).token());
        }
        return store.resolve(token, clock.instant())
                .filter(r -> r.kind() == AuthStore.Kind.DEVICE_TOKEN)
                .map(r -> token);
    }

    // --- credential management ---------------------------------------------------------

    public AuthStore.Issued createDeviceToken(Principal owner, String name) throws SQLException {
        return store.issue(AuthStore.Kind.DEVICE_TOKEN, owner.player().uuid(), name, clock.instant());
    }

    public List<AuthStore.Info> credentials(Principal owner) throws SQLException {
        return store.list(owner.player().uuid(), clock.instant());
    }

    public boolean revoke(Principal owner, long credentialId) throws SQLException {
        return store.revoke(credentialId, owner.player().uuid(), clock.instant());
    }

    /** "Log out everywhere": all sessions of the player. Device tokens stay. */
    public int revokeAllSessions(UUID player) throws SQLException {
        return store.revokeAll(player, AuthStore.Kind.SESSION, clock.instant());
    }

    // --- admins (commands and console) -------------------------------------------------

    public boolean grantAdmin(PlayerRef player, String grantedBy) throws SQLException {
        store.upsertPlayer(player, clock.instant());
        return store.grantAdmin(player.uuid(), grantedBy, clock.instant());
    }

    public boolean revokeAdmin(UUID player) throws SQLException {
        return store.revokeAdmin(player);
    }

    public Optional<PlayerRef> findPlayerByName(String name) throws SQLException {
        return store.findPlayerByName(name);
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() <= 160 ? userAgent : userAgent.substring(0, 160);
    }
}
