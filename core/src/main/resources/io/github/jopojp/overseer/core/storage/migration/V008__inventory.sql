-- Inventories (ADR-0011): AE2 contents, scanned every 10 s, stored as changes only.

-- Things that can be in an inventory. key: <kind>:<registry id>[#<nbt hash>]. name is for display.
CREATE TABLE inventory_key (
    id   INTEGER PRIMARY KEY,
    key  TEXT    NOT NULL UNIQUE,
    kind TEXT    NOT NULL,
    name TEXT    NOT NULL
);

-- Current amount per resource and key; a key that is gone has no row.
CREATE TABLE inventory_amount (
    resource_id INTEGER NOT NULL REFERENCES resource (id),
    key_id      INTEGER NOT NULL REFERENCES inventory_key (id),
    amount      INTEGER NOT NULL,
    uptime_ms   INTEGER NOT NULL,
    PRIMARY KEY (resource_id, key_id)
) WITHOUT ROWID;

-- Net change per minute (bucket = start of the minute on the uptime axis) and the amount at
-- its end. At most one row per key and minute, however often it changes.
CREATE TABLE inventory_change (
    resource_id INTEGER NOT NULL REFERENCES resource (id),
    bucket      INTEGER NOT NULL,
    key_id      INTEGER NOT NULL REFERENCES inventory_key (id),
    delta       INTEGER NOT NULL,
    amount      INTEGER NOT NULL,
    PRIMARY KEY (resource_id, bucket, key_id)
) WITHOUT ROWID;
