"use strict";
const fs = require("node:fs");
const path = require("node:path");
const Database = require("better-sqlite3");
const { Pool } = require("pg");
const { createFeatureStore } = require("./feature-store.cjs");

function createSqliteStore(databasePath) {
  fs.mkdirSync(path.dirname(path.resolve(databasePath)), { recursive: true });

  const db = new Database(databasePath);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  db.pragma("busy_timeout = 5000");
  db.exec(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));

  const queries = {
    findDevice: db.prepare(`
      SELECT device_id, name, token_hash, enabled, created_at, last_seen
      FROM devices WHERE device_id = ?
    `),
    listDevices: db.prepare(`
      SELECT device_id, name, enabled, created_at, last_seen,
        (SELECT payload FROM events e
         WHERE e.device_id = devices.device_id AND e.event_type = 'data:receive'
         ORDER BY e.id DESC LIMIT 1) AS latest_payload,
        (SELECT payload FROM events e
         WHERE e.device_id = devices.device_id AND e.event_type = 'data:receive'
           AND json_extract(e.payload, '$.type') = 'system_health'
         ORDER BY e.id DESC LIMIT 1) AS latest_health
      FROM devices ORDER BY created_at DESC
    `),
    listEvents: db.prepare(`
      SELECT id AS event_id, device_id, event_type, payload, created_at
      FROM events WHERE (? IS NULL OR device_id = ?)
      ORDER BY id DESC LIMIT ?
    `),
    createDevice: db.prepare(`
      INSERT INTO devices (device_id, name, token_hash)
      VALUES (?, ?, ?)
    `),
    touchDevice: db.prepare(`
      UPDATE devices
      SET last_seen = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE device_id = ?
    `),
    insertEvent: db.prepare(`
      INSERT INTO events (device_id, event_type, payload)
      VALUES (?, ?, ?)
    `),
  };

  const saveTelemetry = db.transaction((deviceId, payload) => {
    const result = queries.insertEvent.run(deviceId, "data:receive", payload);
    queries.touchDevice.run(deviceId);
    return Number(result.lastInsertRowid);
  });

  const features = createFeatureStore(db, false);
  return { queries, saveTelemetry, features, close: () => db.close(), kind: "sqlite" };
}

async function createPostgresStore(databaseUrl) {
  const address = new URL(databaseUrl);
  if (!["postgres:", "postgresql:"].includes(address.protocol)) {
    throw new Error("DATABASE_URL must be a PostgreSQL connection string.");
  }
  const disableTls = process.env.PG_SSL_MODE === "disable";
  if (disableTls && !["localhost", "127.0.0.1", "[::1]"].includes(address.hostname)) {
    throw new Error("Unencrypted PostgreSQL connections are allowed only on localhost.");
  }
  // Explicit TLS verification cannot be overridden by connection-string SSL options.
  for (const key of ["sslmode", "sslcert", "sslkey", "sslrootcert", "sslnegotiation"]) {
    address.searchParams.delete(key);
  }
  const db = new Pool({
    connectionString: address.toString(),
    ssl: disableTls ? false : { rejectUnauthorized: true },
    max: 3,
    connectionTimeoutMillis: 15000,
    idleTimeoutMillis: 10000,
    statement_timeout: 15000,
  });
  db.on("error", () => console.error("Idle database connection failed; a later request can reconnect."));
  try {
    await db.query(fs.readFileSync(path.join(__dirname, "schema.postgres.sql"), "utf8"));
  } catch (error) {
    await db.end();
    throw error;
  }
  function statement(sql) {
    return {
      get: async (...args) => (await db.query(sql, args)).rows[0],
      all: async (...args) => (await db.query(sql, args)).rows,
      run: async (...args) => {
        const result = await db.query(sql, args);
        return { changes: result.rowCount, lastInsertRowid: result.rows[0]?.id };
      },
    };
  }
  const queries = {
    findDevice: statement("SELECT device_id, name, token_hash, enabled, created_at, last_seen FROM devices WHERE device_id = $1"),
    listDevices: statement(`
      SELECT device_id, name, enabled, created_at, last_seen,
        (SELECT payload::text FROM events e WHERE e.device_id = devices.device_id
         AND e.event_type = 'data:receive' ORDER BY e.id DESC LIMIT 1) AS latest_payload,
        (SELECT payload::text FROM events e WHERE e.device_id = devices.device_id
         AND e.event_type = 'data:receive' AND e.payload->>'type' = 'system_health'
         ORDER BY e.id DESC LIMIT 1) AS latest_health
      FROM devices ORDER BY created_at DESC
    `),
    listEvents: statement(`
      SELECT id AS event_id, device_id, event_type, payload::text AS payload, created_at
      FROM events WHERE ($1::text IS NULL OR device_id = $2)
      ORDER BY id DESC LIMIT $3
    `),
    createDevice: statement("INSERT INTO devices (device_id, name, token_hash) VALUES ($1, $2, $3)"),
    touchDevice: statement("UPDATE devices SET last_seen = CURRENT_TIMESTAMP WHERE device_id = $1"),
    insertEvent: statement("INSERT INTO events (device_id, event_type, payload) VALUES ($1, $2, $3::jsonb) RETURNING id"),
  };
  async function saveTelemetry(deviceId, payload) {
    const client = await db.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        "INSERT INTO events (device_id, event_type, payload) VALUES ($1, 'data:receive', $2::jsonb) RETURNING id",
        [deviceId, payload]
      );
      await client.query("UPDATE devices SET last_seen = CURRENT_TIMESTAMP WHERE device_id = $1", [deviceId]);
      await client.query("COMMIT");
      return result.rows[0].id;
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch { /* Preserve the original failure. */ }
      throw error;
    } finally { client.release(); }
  }
  const features = createFeatureStore(db, true);
  await features.ready;
  return { queries, saveTelemetry, features, close: () => db.end(), kind: "postgres" };
}

async function createStore() {
  if (process.env.DATABASE_URL) return createPostgresStore(process.env.DATABASE_URL);
  if (process.env.RENDER) {
    throw new Error("Set DATABASE_URL on Render to avoid losing enrollment and telemetry on its temporary disk.");
  }
  return createSqliteStore(process.env.DATABASE_PATH || path.join(__dirname, "data", "devices.sqlite"));
}
module.exports = { createStore };
