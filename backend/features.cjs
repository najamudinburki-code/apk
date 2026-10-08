"use strict";
const crypto = require("node:crypto");
const { rateLimit } = require("express-rate-limit");

const ACTIONS = new Set(["request_status", "request_screenshot", "request_photo", "request_audio", "request_location", "request_scan", "request_geofence", "request_settings", "request_live_view", "request_live_view_stop"]);
// Tool names a dashboard rule may keep on. They are the request action without its prefix, and
// "settings" is deliberately absent so a phone can never be locked out of new rules. A live view is
// governable because it repeats; stopping one never is, so a rule can never strand a stream.
const TOOLS = ["audio", "geofence", "live_view", "location", "photo", "screenshot", "scan"];
// "running" tells the dashboard the phone is working, and "reviewed" is an honest terminal state for
// a report the owner looked at but chose not to share — it must never be recorded as a delivery.
const RESULT_STATES = new Set(["delivered", "running", "completed", "reviewed", "declined", "failed"]);
const TERMINAL_STATES = new Set(["completed", "reviewed", "declined", "failed", "expired"]);
const OUTSTANDING_STATES = "'pending','delivered','running'";
const MAX_FILE = 4 * 1024 * 1024;

// A dashboard may ask a phone to watch one boundary. The phone still asks its owner first, so the
// server only needs to refuse values that could never be a geofence.
function geofenceArgs(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const name = typeof value.name === "string" ? value.name.trim() : "";
  const radius = value.radius_meters;
  if (!name || name.length > 80) return null;
  if (typeof value.latitude !== "number" || !Number.isFinite(value.latitude) || Math.abs(value.latitude) > 90) return null;
  if (typeof value.longitude !== "number" || !Number.isFinite(value.longitude) || Math.abs(value.longitude) > 180) return null;
  if (typeof radius !== "number" || !Number.isFinite(radius) || radius < 100 || radius > 10000) return null;
  return { name, latitude: value.latitude, longitude: value.longitude, radius_meters: radius };
}

// A dashboard may ask this phone to do less, never more: a tool left out of tools_allowed stays off,
// the health cadence has a hard range, and an unrecognised name is refused instead of guessed.
function settingsArgs(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const rules = {};
  if (value.health_interval_minutes !== undefined) {
    const minutes = value.health_interval_minutes;
    if (typeof minutes !== "number" || !Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return null;
    rules.health_interval_minutes = minutes;
  }
  if (value.tools_allowed !== undefined) {
    const list = value.tools_allowed;
    if (!Array.isArray(list) || list.length > TOOLS.length || !list.every(name => typeof name === "string")) return null;
    if (list.some(name => !TOOLS.includes(name))) return null;
    rules.tools_allowed = [...new Set(list)].sort();
  }
  return Object.keys(rules).length ? rules : null;
}

function describeRules(rules) {
  const notes = [];
  if (rules.health_interval_minutes) notes.push(`health samples every ${rules.health_interval_minutes} min`);
  if (rules.tools_allowed) notes.push(rules.tools_allowed.length ? `the dashboard may run ${rules.tools_allowed.join(", ")}` : "no dashboard tool runs");
  return notes.join("; ");
}

function installFeatures({ app, db, authenticateDashboard, validDeviceId, io, hash }) {
  const { query } = db.features;
  async function authenticateDevice(req, res, next) {
    const id = req.headers["x-device-id"];
    const token = /^Bearer ([^\s]+)$/i.exec(req.headers.authorization || "")?.[1];
    if (!validDeviceId(id) || !token || token.length > 256) return res.status(401).json({ error: "Unauthorized" });
    try {
      const row = await db.queries.findDevice.get(id);
      if (!row?.enabled || !crypto.timingSafeEqual(hash(token), Buffer.from(row.token_hash, "hex"))) return res.status(401).json({ error: "Unauthorized" });
      req.deviceId = id;
      next();
    } catch (error) { next(error); }
  }
  const limited = rateLimit({ windowMs: 60_000, limit: 180, standardHeaders: "draft-7", legacyHeaders: false });
  const changed = () => io.to("dashboards").emit("features:changed");
  app.use("/api/device", limited, authenticateDevice);

  app.post("/api/device/events", async (req, res) => {
    const payload = req.body?.payload;
    const id = req.body?.event_id;
    if (typeof id !== "string" || !/^[A-Za-z0-9-]{1,80}$/.test(id) || !payload || typeof payload !== "object" || Array.isArray(payload) || Buffer.byteLength(JSON.stringify(payload)) > 48 * 1024) return res.status(400).json({ error: "Invalid event" });
    const eventId = await db.features.saveEvent(req.deviceId, id, JSON.stringify(payload));
    if (eventId === null) return res.json({ ok: true, duplicate: true });
    io.to("dashboards").emit("data:received", { event_id: eventId, device_id: req.deviceId, payload });
    res.json({ ok: true, event_id: eventId });
  });

  app.post("/api/device/files", async (req, res) => {
    const b = req.body || {};
    if (!/^[a-f0-9-]{36}$/.test(b.file_id || "") || typeof b.name !== "string" || !b.name.length || b.name.length > 180 || /[\x00-\x1f/\\]/.test(b.name) || !["photo", "audio", "screenshot", "document", "live_frame"].includes(b.kind) || typeof b.mime !== "string" || !/^[\w.+-]+\/[\w.+-]+$/.test(b.mime) || typeof b.data !== "string" || b.data.length > Math.ceil(MAX_FILE / 3) * 4 || /[^A-Za-z0-9+/=]/.test(b.data) || b.data.length % 4 !== 0) return res.status(400).json({ error: "Invalid file (maximum 4 MiB)" });
    const bytes = Buffer.from(b.data, "base64");
    if (bytes.toString("base64") !== b.data) return res.status(400).json({ error: "Invalid base64" });
    if (!bytes.length || bytes.length > MAX_FILE) return res.status(413).json({ error: "File too large or empty" });
    const result = await db.features.saveFile(req.deviceId, b, bytes);
    if (result === "conflict") return res.status(409).json({ error: "File id conflict" });
    if (result === "quota") return res.status(409).json({ error: "Device file quota reached. Delete old shared files in the dashboard." });
    if (result === "duplicate") return res.json({ ok: true, file_id: b.file_id });
    await db.queries.touchDevice.run(req.deviceId);
    changed();
    res.status(201).json({ ok: true, file_id: b.file_id });
  });

  app.get("/api/files", authenticateDashboard, async (req, res) => {
    const id = req.query.device_id || null;
    if (id && !validDeviceId(id)) return res.status(400).json({ error: "Invalid device" });
    res.json({ files: await query("SELECT file_id,device_id,name,mime,kind,size,created_at FROM shared_files WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 500", [id, id]) });
  });
  app.get("/api/state", authenticateDashboard, async (req, res) => {
    if (!validDeviceId(req.query.device_id)) return res.status(400).json({ error: "Invalid device" });
    res.json({ events: await db.features.latestEvents(req.query.device_id) });
  });
  app.get("/api/files/:id", authenticateDashboard, async (req, res) => {
    const file = await query("SELECT * FROM shared_files WHERE file_id = ?", [req.params.id], "get");
    if (!file) return res.status(404).json({ error: "File not found" });
    // Always download arbitrary documents: HTML/SVG must never execute on the API origin.
    res.setHeader("Content-Type", file.mime);
    res.setHeader("Content-Disposition", `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`);
    res.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
    res.send(Buffer.from(file.bytes));
  });
  app.delete("/api/files/:id", authenticateDashboard, async (req, res) => {
    await query("DELETE FROM shared_files WHERE file_id = ?", [req.params.id], "run");
    changed();
    res.json({ ok: true });
  });

  app.post("/api/requests", authenticateDashboard, async (req, res) => {
    const { device_id, action, args } = req.body || {};
    if (!validDeviceId(device_id) || !ACTIONS.has(action)) return res.status(400).json({ error: "Invalid device or action" });
    // Only the two value-carrying tools are accepted here, so no other action can smuggle
    // parameters to the phone.
    let encoded = "";
    if (action === "request_geofence") {
      const fence = geofenceArgs(args);
      if (!fence) return res.status(400).json({ error: "Geofence needs a name, latitude, longitude and radius between 100 and 10000 meters" });
      encoded = JSON.stringify(fence);
    } else if (action === "request_settings") {
      const rules = settingsArgs(args);
      if (!rules) return res.status(400).json({ error: `Rules need health_interval_minutes (1-1440) or tools_allowed drawn from ${TOOLS.join(", ")}, and at least one of them` });
      encoded = JSON.stringify(rules);
    } else if (args !== undefined && args !== null) {
      return res.status(400).json({ error: `${action} does not take arguments` });
    }
    const device = await db.queries.findDevice.get(device_id);
    if (!device?.enabled) return res.status(404).json({ error: "Device unavailable" });
    const pending = await query(`SELECT COUNT(*) AS count FROM device_requests WHERE device_id = ? AND status IN (${OUTSTANDING_STATES}) AND expires_at > ?`, [device_id, new Date().toISOString()], "get");
    if (Number(pending.count) >= 20) return res.status(429).json({ error: "Too many outstanding requests" });
    const id = crypto.randomUUID(), now = new Date().toISOString(), expiry = new Date(Date.now() + 600_000).toISOString();
    await query("INSERT INTO device_requests(request_id,device_id,action,status,args,created_at,expires_at,updated_at) VALUES (?,?,?,'pending',?,?,?,?)", [id, device_id, action, encoded, now, expiry, now], "run");
    changed();
    const queued = encoded ? JSON.parse(encoded) : {};
    const outcome = action === "request_geofence"
      ? `Queued "${queued.name}" for the phone; its owner must approve the boundary. Expires in 10 minutes.`
      : action === "request_settings"
        ? `Queued rules for the phone: ${describeRules(queued)}. Rules only narrow what this phone may do, and Android permissions still belong to its owner. Expires in 10 minutes.`
        : action === "request_live_view"
          ? "Queued; the phone streams only if its owner allowed live view in Device tools. A session ends by itself after 120 seconds or 6 MiB of frames, whichever comes first, and Android keeps its own camera indicator lit throughout. Expires in 10 minutes."
          : action === "request_live_view_stop"
            ? "Queued; the phone stops any live view on its next check, about 10 seconds while monitoring is on. Expires in 10 minutes."
            : "Queued for the phone; expires in 10 minutes.";
    res.status(201).json({ ok: true, request_id: id, status: "pending", detail: outcome });
  });
  app.get("/api/requests", authenticateDashboard, async (req, res) => {
    const id = req.query.device_id || null;
    if (id && !validDeviceId(id)) return res.status(400).json({ error: "Invalid device" });
    const now = new Date().toISOString();
    await query(`UPDATE device_requests SET status='expired', updated_at=? WHERE status IN (${OUTSTANDING_STATES}) AND expires_at < ?`, [now, now], "run");
    res.json({ requests: await query("SELECT * FROM device_requests WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 100", [id, id]) });
  });
  // Offline review and spreadsheets: one bounded, authenticated dump per table. Captured file bytes
  // and token hashes are never part of an export.
  const exportLimiter = rateLimit({ windowMs: 60_000, limit: 12, standardHeaders: "draft-7", legacyHeaders: false });
  const payloadType = db.kind === "postgres" ? "payload->>'type'" : "json_extract(payload,'$.type')";
  const EXPORTS = {
    events: {
      columns: ["event_id", "device_id", "event_type", "payload_type", "payload", "created_at"],
      sql: `SELECT id AS event_id, device_id, event_type, ${payloadType} AS payload_type, payload, created_at
        FROM events WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY id DESC LIMIT 1000`,
    },
    requests: {
      columns: ["request_id", "device_id", "action", "status", "detail", "result_ref", "args", "created_at", "expires_at", "updated_at"],
      sql: `SELECT request_id, device_id, action, status, detail, result_ref, args, created_at, expires_at, updated_at
        FROM device_requests WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 1000`,
    },
    files: {
      columns: ["file_id", "device_id", "name", "mime", "kind", "size", "created_at"],
      sql: `SELECT file_id, device_id, name, mime, kind, size, created_at FROM shared_files
        WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 1000`,
    },
    devices: {
      columns: ["device_id", "name", "enabled", "created_at", "last_seen"],
      sql: `SELECT device_id, name, enabled, created_at, last_seen FROM devices
        WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 500`,
    },
  };
  function csvCell(value) {
    if (value === null || value === undefined) return "";
    const raw = value instanceof Date ? value.toISOString() : typeof value === "object" ? JSON.stringify(value) : String(value);
    // A cell that opens with =, +, - or @ becomes a formula in a spreadsheet, but a plain negative
    // coordinate must stay a number, so only non-numeric values are guarded.
    const formula = /^[-+=@]/.test(raw) && !/^-?\d+(\.\d+)?$/.test(raw);
    return `"${(formula ? `'${raw}` : raw).replace(/"/g, '""').replace(/[\r\n]+/g, " ")}"`;
  }
  app.get("/api/export/:kind", authenticateDashboard, exportLimiter, async (req, res, next) => {
    const spec = EXPORTS[req.params.kind];
    if (!spec) return res.status(400).json({ error: "Export must be events, requests, files or devices" });
    const id = req.query.device_id || null;
    if (id && !validDeviceId(id)) return res.status(400).json({ error: "Invalid device" });
    try {
      const rows = await query(spec.sql, [id, id]);
      const name = `system-health-${req.params.kind}${id ? `-${id.slice(0, 60)}` : ""}-${new Date().toISOString().slice(0, 10)}`;
      res.setHeader("Cache-Control", "no-store");
      if (req.query.format === "json") {
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.setHeader("Content-Disposition", `attachment; filename="${name}.json"`);
        return res.json({ kind: req.params.kind, device_id: id, exported_at: new Date().toISOString(), count: rows.length, rows });
      }
      const head = spec.columns.map(column => `"${column}"`).join(",");
      res.setHeader("Content-Type", "text/csv; charset=utf-8");
      res.setHeader("Content-Disposition", `attachment; filename="${name}.csv"`);
      res.send(`${head}\r\n${rows.map(row => spec.columns.map(column => csvCell(row[column])).join(",")).join("\r\n")}\r\n`);
    } catch (error) { next(error); }
  });

  app.get("/api/device/requests", async (req, res) => {
    const now = new Date().toISOString();
    const requests = await query("SELECT request_id,action,args,expires_at,created_at FROM device_requests WHERE device_id=? AND status IN ('pending','delivered') AND expires_at > ? ORDER BY created_at LIMIT 20", [req.deviceId, now]);
    // A poll proves the phone is awake and reachable, so it refreshes presence and hands the
    // returned requests over to it. Nothing beyond the 20-request outstanding cap can be skipped.
    await db.queries.touchDevice.run(req.deviceId);
    if (requests.length) {
      await query("UPDATE device_requests SET status='delivered', updated_at=? WHERE device_id=? AND status='pending' AND expires_at > ?", [now, req.deviceId, now], "run");
      changed();
    }
    res.json({ requests: requests.map(row => ({ ...row, args: row.args ? JSON.parse(row.args) : {} })) });
  });
  app.post("/api/device/requests/:id/result", async (req, res) => {
    const { status, detail = "", result_ref = "" } = req.body || {};
    if (!RESULT_STATES.has(status) || typeof detail !== "string" || detail.length > 800) return res.status(400).json({ error: "Invalid result" });
    if (typeof result_ref !== "string" || (result_ref && !/^(file|event):[A-Za-z0-9-]{1,64}$/.test(result_ref))) return res.status(400).json({ error: "Invalid result reference" });
    const old = await query("SELECT * FROM device_requests WHERE request_id = ? AND device_id = ?", [req.params.id, req.deviceId], "get");
    if (!old) return res.status(404).json({ error: "Request not found" });
    // A finished request keeps its first answer, so a retried upload cannot rewrite history.
    if (TERMINAL_STATES.has(old.status)) return res.json({ ok: true, status: old.status });
    if (!["pending", "delivered", "running"].includes(old.status)) return res.json({ ok: true, status: old.status });
    if (old.expires_at < new Date().toISOString()) return res.status(410).json({ error: "Request expired" });
    await query(`UPDATE device_requests SET status=?,detail=?,result_ref=?,updated_at=? WHERE request_id=? AND device_id=? AND status IN (${OUTSTANDING_STATES}) AND expires_at > ?`, [status, detail, result_ref, new Date().toISOString(), req.params.id, req.deviceId, new Date().toISOString()], "run");
    changed();
    res.json({ ok: true });
  });
}
module.exports = { installFeatures, ACTIONS, MAX_FILE };
