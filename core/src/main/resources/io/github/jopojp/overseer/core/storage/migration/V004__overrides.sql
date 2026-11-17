-- Overseer-side settings of resources (e.g. the zone of a Flux device), set in the UI.
-- Keyed by the stable resource identity (ADR-0005), so they survive renames.
CREATE TABLE resource_override (
    provider        TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    rid             TEXT    NOT NULL,
    key             TEXT    NOT NULL,
    value           TEXT    NOT NULL,
    updated_by      TEXT,
    updated_wall_ms INTEGER NOT NULL,
    PRIMARY KEY (provider, type, rid, key)
) WITHOUT ROWID;
