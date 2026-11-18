-- Players an owner shared a resource with (ADR-0011). Grants viewing only, and only for
-- providers that declare the SHARING capability. Keyed by the stable resource identity.
CREATE TABLE resource_share (
    provider        TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    rid             TEXT    NOT NULL,
    player          TEXT    NOT NULL,
    granted_by      TEXT,
    granted_wall_ms INTEGER NOT NULL,
    PRIMARY KEY (provider, type, rid, player)
) WITHOUT ROWID;
