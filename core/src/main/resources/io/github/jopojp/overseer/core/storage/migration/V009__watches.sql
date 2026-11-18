-- Watched items per resource (ADR-0011): read every second, optionally with a target amount
-- (minimum stock) for the deficit view. item is a stack key, e.g. item:minecraft:iron_ingot.
CREATE TABLE watch (
    provider        TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    rid             TEXT    NOT NULL,
    item            TEXT    NOT NULL,
    name            TEXT    NOT NULL,
    kind            TEXT    NOT NULL,
    target          INTEGER,
    created_by      TEXT,
    created_wall_ms INTEGER NOT NULL,
    PRIMARY KEY (provider, type, rid, item)
) WITHOUT ROWID;
