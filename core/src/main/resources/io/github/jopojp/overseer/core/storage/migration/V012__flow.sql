-- Flows (ADR-0020): what each device moved into or out of a storage network, per minute.

-- A device of a network: a bus, a player, a crafting CPU, ... device is its id within the network.
CREATE TABLE flow_device (
    id          INTEGER PRIMARY KEY,
    resource_id INTEGER NOT NULL REFERENCES resource (id),
    device      TEXT    NOT NULL,
    kind        TEXT    NOT NULL,
    name        TEXT    NOT NULL,
    UNIQUE (resource_id, device)
);

-- Amounts per device, key and minute (bucket = start of the minute on the uptime axis).
CREATE TABLE flow_minute (
    device_id INTEGER NOT NULL REFERENCES flow_device (id),
    bucket    INTEGER NOT NULL,
    key_id    INTEGER NOT NULL REFERENCES inventory_key (id),
    inserted  INTEGER NOT NULL,
    extracted INTEGER NOT NULL,
    PRIMARY KEY (device_id, bucket, key_id)
) WITHOUT ROWID;

CREATE INDEX flow_minute_bucket ON flow_minute (bucket);
