-- World actions (ADR-0016): every change made from the dashboard, and every refused attempt,
-- with who did it and the old and new values. Kept until an admin purges it.
CREATE TABLE action_log (
    id          INTEGER PRIMARY KEY,
    at_wall_ms  INTEGER NOT NULL,
    actor       TEXT    NOT NULL,
    actor_name  TEXT    NOT NULL,
    operator    INTEGER NOT NULL,
    action      TEXT    NOT NULL,
    provider    TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    rid         TEXT    NOT NULL,
    params      TEXT    NOT NULL,
    status      TEXT    NOT NULL,
    message     TEXT
);
CREATE INDEX action_log_target ON action_log (provider, type, rid, id);

-- The values one action changed; a batch action has one row per device and field.
CREATE TABLE action_change (
    log_id   INTEGER NOT NULL REFERENCES action_log (id) ON DELETE CASCADE,
    provider TEXT    NOT NULL,
    type     TEXT    NOT NULL,
    rid      TEXT    NOT NULL,
    field    TEXT    NOT NULL,
    before   TEXT,
    after    TEXT
);
CREATE INDEX action_change_log ON action_change (log_id);
CREATE INDEX action_change_resource ON action_change (provider, type, rid, log_id);
