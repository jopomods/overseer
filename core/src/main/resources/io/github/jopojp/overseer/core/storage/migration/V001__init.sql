-- Initial schema (ADR-0005, ADR-0006).
-- Every time-bearing row stores both clocks: wall_ms (UTC epoch millis) and
-- uptime_ms (accumulated server uptime) plus the uptime session number.

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;

-- One row per server run. Gaps between sessions are the offline periods
-- shown as markers on the server-uptime axis.
CREATE TABLE uptime_session (
    session           INTEGER PRIMARY KEY,
    started_wall_ms   INTEGER NOT NULL,
    started_uptime_ms INTEGER NOT NULL,
    last_wall_ms      INTEGER NOT NULL,
    last_uptime_ms    INTEGER NOT NULL
);

-- Stable identity of observed things. Never keyed by display names.
CREATE TABLE resource (
    id                   INTEGER PRIMARY KEY,
    provider             TEXT    NOT NULL,
    type                 TEXT    NOT NULL,
    rid                  TEXT    NOT NULL,
    first_seen_wall_ms   INTEGER NOT NULL,
    first_seen_uptime_ms INTEGER NOT NULL,
    UNIQUE (provider, type, rid)
);

-- Discrete changes: kind = added | removed | attribute.
-- For kind = attribute, value NULL means the attribute was removed.
CREATE TABLE event (
    id          INTEGER PRIMARY KEY,
    resource_id INTEGER NOT NULL REFERENCES resource (id),
    kind        TEXT    NOT NULL,
    attribute   TEXT,
    value       TEXT,
    wall_ms     INTEGER NOT NULL,
    uptime_ms   INTEGER NOT NULL,
    session     INTEGER NOT NULL
);
CREATE INDEX event_by_resource ON event (resource_id, uptime_ms);

-- One metric of one resource.
CREATE TABLE series (
    id          INTEGER PRIMARY KEY,
    resource_id INTEGER NOT NULL REFERENCES resource (id),
    metric      TEXT    NOT NULL,
    UNIQUE (resource_id, metric)
);

-- Raw samples, thinned by deadband + heartbeat before insert.
CREATE TABLE sample (
    series_id INTEGER NOT NULL REFERENCES series (id),
    uptime_ms INTEGER NOT NULL,
    wall_ms   INTEGER NOT NULL,
    session   INTEGER NOT NULL,
    value     REAL    NOT NULL,
    PRIMARY KEY (series_id, uptime_ms)
) WITHOUT ROWID;
