-- A player asks the owner of a shareable resource for access (ADR-0011). Approving creates
-- a share and removes the row; denying keeps it with denied_wall_ms (cool-down).
CREATE TABLE access_request (
    provider          TEXT    NOT NULL,
    type              TEXT    NOT NULL,
    rid               TEXT    NOT NULL,
    player            TEXT    NOT NULL,
    requested_wall_ms INTEGER NOT NULL,
    denied_wall_ms    INTEGER,
    PRIMARY KEY (provider, type, rid, player)
) WITHOUT ROWID;

-- In-game chat messages for players, delivered when they are online (then deleted).
CREATE TABLE notification (
    id              INTEGER PRIMARY KEY,
    player          TEXT    NOT NULL,
    message         TEXT    NOT NULL,
    created_wall_ms INTEGER NOT NULL
);
CREATE INDEX notification_by_player ON notification (player);
