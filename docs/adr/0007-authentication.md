# 0007. Authentication: in-game link codes, sessions, device tokens

- Status: Accepted
- Date: 2026-11-17

## Context

Players need to log in from a phone without passwords and without Microsoft OAuth.
The dashboard may be reachable from the internet. An admin must never be locked out.

## Decision

### Login

1. The login page requests a link code (e.g. `7KQ-4M2`) and shows
   "type `/ov link 7KQ-4M2` in game".
2. The player runs the command in game; the browser session becomes that Minecraft
   account. Codes are single-use, expire after 5 minutes, and code creation and
   confirmation are rate limited.
3. Recovery from the server console (always super-admin): `ov login <player>` prints a
   one-time login URL; `ov admin add|remove <player>`, `ov sessions revoke <player>`.

### Sessions

- Opaque random token in an **HttpOnly, Secure (when behind HTTPS), SameSite=Strict**
  cookie. Works for REST and for `EventSource` (which cannot send custom headers).
- Sliding expiry: every use extends it; idle timeout configurable (default 30 days).
- Users see and revoke their sessions; "log out everywhere".

### Device tokens

- Created in the UI, named ("tablet on the wall"), read-only, no expiry, revocable.
- Sent as `Authorization: Bearer <token>`; also serve as API keys (Home Assistant,
  Grafana, scripts). A kiosk can exchange a device token for a cookie once via URL.
- Carry the permissions of their creator.

### Baseline hardening (M1a, not later)

- Bind to `127.0.0.1` by default; exposure to the network is an explicit config change.
- Secrets (session tokens, device tokens, link codes) stored only as SHA-256 hashes.
- Same-origin only: no CORS headers; `Origin` checked on state-changing requests.
- Request size limits; static file serving resolves only inside the embedded bundle.
- `X-Forwarded-For` / `X-Forwarded-Proto` trusted only from configured proxy addresses.
- TLS is terminated by a reverse proxy (guide in M1a). Built-in Let's Encrypt is backlog.

## Consequences

- No passwords to store or reset.
- Logging in requires being on the server (or console access), which is also the proof
  of account ownership.
- Write actions (backlog) will additionally need CSRF tokens.

## Alternatives considered

- **Microsoft/Xbox OAuth**: heavy, needs an Azure app registration.
- **Passwords**: storage, reset flows, weak passwords.
- **Link printed in game, opened on the phone**: hard to transfer from PC to phone;
  kept only as the console recovery path.
