-- Persistent cloud database. Applied automatically before the server starts.
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY CHECK (length(device_id) BETWEEN 1 AND 128),
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
  enabled SMALLINT NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(device_id) ON UPDATE CASCADE ON DELETE RESTRICT,
  event_type TEXT NOT NULL CHECK (event_type IN ('data:receive', 'command:send')),
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_events_device_created ON events(device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_device_id ON events(device_id, id DESC);

-- One row: the instant from which dashboard sign-ins count as current. Revoking sessions
-- moves this forward, so tokens issued earlier stop working even though they are well-formed.
CREATE TABLE IF NOT EXISTS dashboard_state (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  sessions_valid_from TIMESTAMPTZ NOT NULL
);
-- Deliberately the beginning of time: a fresh install must not end the sign-ins it inherits,
-- and only an explicit revoke moves this forward.
INSERT INTO dashboard_state (id, sessions_valid_from) VALUES (1, 'epoch') ON CONFLICT (id) DO NOTHING;
