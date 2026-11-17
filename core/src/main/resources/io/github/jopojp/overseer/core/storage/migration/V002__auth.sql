-- Authentication and global roles (ADR-0007, ADR-0008).
-- Secrets are never stored in clear text: token_hash is the SHA-256 hex digest.

-- Players who logged in at least once (name as of last login).
CREATE TABLE player (
    uuid              TEXT PRIMARY KEY,
    name              TEXT    NOT NULL,
    last_seen_wall_ms INTEGER NOT NULL
) WITHOUT ROWID;

-- Players granted dashboard admin by an operator. Operators themselves are not listed:
-- they are super admins by virtue of the server's op list.
CREATE TABLE dashboard_admin (
    uuid            TEXT PRIMARY KEY,
    granted_by      TEXT,
    granted_wall_ms INTEGER NOT NULL
) WITHOUT ROWID;

-- Browser sessions (kind = session) and device tokens (kind = device_token).
CREATE TABLE credential (
    id                INTEGER PRIMARY KEY,
    kind              TEXT    NOT NULL,
    token_hash        TEXT    NOT NULL UNIQUE,
    player_uuid       TEXT    NOT NULL,
    name              TEXT,
    created_wall_ms   INTEGER NOT NULL,
    last_used_wall_ms INTEGER NOT NULL,
    revoked_wall_ms   INTEGER
);
CREATE INDEX credential_by_player ON credential (player_uuid);
