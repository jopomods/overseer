-- Item icons (ADR-0011): PNGs rendered by a client with Overseer installed (/ov icons export) and
-- sent to the server. key is a stack key; variants with NBT fall back to their base key.
CREATE TABLE icon (
    key             TEXT    PRIMARY KEY,
    png             BLOB    NOT NULL,
    uploaded_by     TEXT,
    updated_wall_ms INTEGER NOT NULL
) WITHOUT ROWID;
