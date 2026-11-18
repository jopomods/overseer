-- Resources a mod does not identify by itself, registered by a player or adopted by an admin
-- (ADR-0011: AE2 grids without a controller). The id is the registered block position.
CREATE TABLE registration (
    provider        TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    rid             TEXT    NOT NULL,
    owner           TEXT    NOT NULL,
    created_wall_ms INTEGER NOT NULL,
    PRIMARY KEY (provider, type, rid)
) WITHOUT ROWID;
