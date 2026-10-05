-- schema.sql

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY NOT NULL
        CHECK (length(device_id) BETWEEN 1 AND 128),
    name TEXT NOT NULL
        CHECK (length(name) BETWEEN 1 AND 200),
    token_hash TEXT NOT NULL UNIQUE
        CHECK (length(token_hash) = 64),
    enabled INTEGER NOT NULL DEFAULT 1
        CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL
        DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen TEXT
);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    event_type TEXT NOT NULL
        CHECK (event_type IN ('data:receive', 'command:send')),
    payload TEXT NOT NULL
        CHECK (json_valid(payload)),
    created_at TEXT NOT NULL
        DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (device_id)
        REFERENCES devices(device_id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_events_device_created
    ON events(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_events_created
    ON events(created_at DESC);
