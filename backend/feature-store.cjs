"use strict";

// Both cloud and local storage use the same authenticated feature workflows.
function createFeatureStore(db, postgres) {
  const schema = `
    CREATE TABLE IF NOT EXISTS shared_files (
      file_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
      name TEXT NOT NULL, mime TEXT NOT NULL, kind TEXT NOT NULL,
      bytes ${postgres ? "BYTEA" : "BLOB"} NOT NULL, size INTEGER NOT NULL,
      created_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS shared_files_device ON shared_files(device_id, created_at);
    CREATE TABLE IF NOT EXISTS device_requests (
      request_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
      action TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL, expires_at TEXT NOT NULL, updated_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS device_requests_device ON device_requests(device_id, created_at);
    CREATE TABLE IF NOT EXISTS feature_receipts (
      receipt_id TEXT NOT NULL, device_id TEXT NOT NULL REFERENCES devices(device_id),
      PRIMARY KEY(receipt_id, device_id)
    );
    CREATE TABLE IF NOT EXISTS enrollment_requests (
      device_id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL, expires_at TEXT NOT NULL
    );`;
  const ready = postgres ? db.query(schema) : Promise.resolve(db.exec(schema));
  async function query(sql, values = [], mode = "all") {
    await ready;
    if (postgres) {
      let index = 0;
      const result = await db.query(sql.replace(/\?/g, () => `$${++index}`), values);
      return mode === "get" ? result.rows[0] : mode === "run" ? result.rowCount : result.rows;
    }
    const statement = db.prepare(sql);
    if (mode === "get") return statement.get(...values);
    if (mode === "run") return statement.run(...values).changes;
    return statement.all(...values);
  }
  async function saveEvent(device, id, payload) {
    await ready;
    if (!postgres) return db.transaction(() => {
      const inserted = db.prepare("INSERT INTO feature_receipts(receipt_id,device_id) VALUES (?,?) ON CONFLICT DO NOTHING").run(id, device);
      if (!inserted.changes) return null;
      const event = db.prepare("INSERT INTO events(device_id,event_type,payload) VALUES (?,'data:receive',?)").run(device, payload);
      db.prepare("UPDATE devices SET last_seen=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE device_id=?").run(device);
      return Number(event.lastInsertRowid);
    })();
    const client = await db.connect();
    try {
      await client.query("BEGIN");
      const receipt = await client.query("INSERT INTO feature_receipts(receipt_id,device_id) VALUES ($1,$2) ON CONFLICT DO NOTHING RETURNING receipt_id", [id, device]);
      if (!receipt.rowCount) { await client.query("COMMIT"); return null; }
      const event = await client.query("INSERT INTO events(device_id,event_type,payload) VALUES ($1,'data:receive',$2::jsonb) RETURNING id", [device, payload]);
      await client.query("UPDATE devices SET last_seen=CURRENT_TIMESTAMP WHERE device_id=$1", [device]);
      await client.query("COMMIT");
      return event.rows[0].id;
    } catch (e) { await client.query("ROLLBACK"); throw e; }
    finally { client.release(); }
  }
  async function saveFile(device, file, bytes) {
    await ready;
    const values = [file.file_id, device, file.name, file.mime, file.kind, bytes, bytes.length, new Date().toISOString()];
    function check(old, usage) {
      if (old) return old.device_id === device ? "duplicate" : "conflict";
      if (Number(usage.size) + bytes.length > 100 * 1024 * 1024 || Number(usage.count) >= 500) return "quota";
      return "insert";
    }
    if (!postgres) return db.transaction(() => {
      const old = db.prepare("SELECT device_id FROM shared_files WHERE file_id=?").get(file.file_id);
      const usage = db.prepare("SELECT COALESCE(SUM(size),0) AS size,COUNT(*) AS count FROM shared_files WHERE device_id=?").get(device);
      const result = check(old, usage);
      if (result === "insert") db.prepare("INSERT INTO shared_files(file_id,device_id,name,mime,kind,bytes,size,created_at) VALUES (?,?,?,?,?,?,?,?)").run(...values);
      return result;
    })();
    const client = await db.connect();
    try {
      await client.query("BEGIN");
      // Serialize a phone's quota checks across clients and server instances.
      await client.query("SELECT device_id FROM devices WHERE device_id=$1 FOR UPDATE", [device]);
      const old = (await client.query("SELECT device_id FROM shared_files WHERE file_id=$1", [file.file_id])).rows[0];
      const usage = (await client.query("SELECT COALESCE(SUM(size),0) AS size,COUNT(*) AS count FROM shared_files WHERE device_id=$1", [device])).rows[0];
      const result = check(old, usage);
      if (result === "insert") {
        const inserted = await client.query("INSERT INTO shared_files(file_id,device_id,name,mime,kind,bytes,size,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT DO NOTHING", values);
        if (!inserted.rowCount) {
          const concurrent = (await client.query("SELECT device_id FROM shared_files WHERE file_id=$1", [file.file_id])).rows[0];
          await client.query("COMMIT");
          return concurrent.device_id === device ? "duplicate" : "conflict";
        }
      }
      await client.query("COMMIT"); return result;
    } catch (e) { await client.query("ROLLBACK"); throw e; }
    finally { client.release(); }
  }
  async function latestEvents(device) {
    const type = postgres ? "payload->>'type'" : "json_extract(payload,'$.type')";
    const rows = await query(`SELECT event_id,device_id,payload,created_at FROM (
      SELECT id AS event_id,device_id,payload,created_at,
        ROW_NUMBER() OVER (PARTITION BY ${type} ORDER BY id DESC) AS position
      FROM events WHERE device_id=? AND event_type='data:receive'
    ) AS latest WHERE position=1 ORDER BY event_id DESC LIMIT 30`, [device]);
    return rows.map(row => ({ ...row, payload: typeof row.payload === "string" ? JSON.parse(row.payload) : row.payload }));
  }
  async function autoEnroll(id, name, tokenHash) {
    await ready;
    const existingState = row => row.token_hash !== tokenHash ? "unauthorized" : row.enabled ? "approved" : "disabled";
    if (!postgres) return db.transaction(() => {
      const device = db.prepare("SELECT token_hash,enabled FROM devices WHERE device_id=?").get(id);
      if (device) return existingState(device);
      const pending = db.prepare("SELECT name,token_hash,status FROM enrollment_requests WHERE device_id=?").get(id);
      if (pending?.token_hash && pending.token_hash !== tokenHash) return "unauthorized";
      if (pending?.status === "rejected") return "rejected";
      db.prepare("INSERT INTO devices(device_id,name,token_hash) VALUES (?,?,?)").run(id, pending?.name || name, tokenHash);
      db.prepare("UPDATE enrollment_requests SET status='approved' WHERE device_id=?").run(id);
      return "approved";
    })();
    const client = await db.connect();
    try {
      await client.query("BEGIN");
      const device = (await client.query("SELECT token_hash,enabled FROM devices WHERE device_id=$1 FOR UPDATE", [id])).rows[0];
      if (device) { await client.query("COMMIT"); return existingState(device); }
      const pending = (await client.query("SELECT name,token_hash,status FROM enrollment_requests WHERE device_id=$1 FOR UPDATE", [id])).rows[0];
      if (pending && (pending.token_hash !== tokenHash || pending.status === "rejected")) {
        await client.query("COMMIT"); return pending.token_hash !== tokenHash ? "unauthorized" : "rejected";
      }
      await client.query("INSERT INTO devices(device_id,name,token_hash) VALUES ($1,$2,$3) ON CONFLICT(device_id) DO NOTHING", [id, pending?.name || name, tokenHash]);
      const current = (await client.query("SELECT token_hash,enabled FROM devices WHERE device_id=$1", [id])).rows[0];
      const state = existingState(current);
      if (state === "approved") await client.query("UPDATE enrollment_requests SET status='approved' WHERE device_id=$1", [id]);
      await client.query("COMMIT"); return state;
    } catch (error) {
      try { await client.query("ROLLBACK"); } catch { /* Preserve the original error. */ }
      throw error;
    } finally { client.release(); }
  }
  return { ready, query, saveEvent, saveFile, latestEvents, autoEnroll };
}
module.exports = { createFeatureStore };
