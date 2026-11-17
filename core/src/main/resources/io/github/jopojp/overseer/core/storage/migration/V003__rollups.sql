-- Rollups (ADR-0005): time-weighted aggregates of the step function described by the
-- deadband-thinned raw samples. bucket = start of the bucket in server uptime (ms).
-- wall_ms = calendar time at the bucket start (from the uptime session).

CREATE TABLE sample_minute (
    series_id INTEGER NOT NULL REFERENCES series (id),
    bucket    INTEGER NOT NULL,
    wall_ms   INTEGER NOT NULL,
    session   INTEGER NOT NULL,
    min       REAL    NOT NULL,
    max       REAL    NOT NULL,
    avg       REAL    NOT NULL,
    last      REAL    NOT NULL,
    PRIMARY KEY (series_id, bucket)
) WITHOUT ROWID;

CREATE TABLE sample_hour (
    series_id INTEGER NOT NULL REFERENCES series (id),
    bucket    INTEGER NOT NULL,
    wall_ms   INTEGER NOT NULL,
    session   INTEGER NOT NULL,
    min       REAL    NOT NULL,
    max       REAL    NOT NULL,
    avg       REAL    NOT NULL,
    last      REAL    NOT NULL,
    PRIMARY KEY (series_id, bucket)
) WITHOUT ROWID;

-- Retention deletes by time across all series.
CREATE INDEX sample_by_time ON sample (uptime_ms);
CREATE INDEX sample_minute_by_time ON sample_minute (bucket);
CREATE INDEX sample_hour_by_time ON sample_hour (bucket);
