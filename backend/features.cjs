"use strict";
const crypto = require("node:crypto");
const { rateLimit } = require("express-rate-limit");

const ACTIONS = new Set(["request_status", "request_screenshot", "request_photo", "request_audio", "request_location", "request_scan", "request_audit", "request_files", "request_backup"]);
const MAX_FILE = 4 * 1024 * 1024;

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
    if (!/^[a-f0-9-]{36}$/.test(b.file_id || "") || typeof b.name !== "string" || !b.name.length || b.name.length > 180 || /[\x00-\x1f/\\]/.test(b.name) || !["photo", "audio", "screenshot", "document", "audit", "backup"].includes(b.kind) || typeof b.mime !== "string" || !/^[\w.+-]+\/[\w.+-]+$/.test(b.mime) || typeof b.data !== "string" || b.data.length > Math.ceil(MAX_FILE / 3) * 4 || /[^A-Za-z0-9+/=]/.test(b.data) || b.data.length % 4 !== 0) return res.status(400).json({ error: "Invalid file (maximum 4 MiB)" });
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
    const { device_id, action } = req.body || {};
    if (!validDeviceId(device_id) || !ACTIONS.has(action)) return res.status(400).json({ error: "Invalid device or action" });
    const device = await db.queries.findDevice.get(device_id);
    if (!device?.enabled) return res.status(404).json({ error: "Device unavailable" });
    const pending = await query("SELECT COUNT(*) AS count FROM device_requests WHERE device_id = ? AND status IN ('pending','delivered') AND expires_at > ?", [device_id, new Date().toISOString()], "get");
    if (Number(pending.count) >= 20) return res.status(429).json({ error: "Too many outstanding requests" });
    const id = crypto.randomUUID(), now = new Date().toISOString(), expiry = new Date(Date.now() + 600_000).toISOString();
    await query("INSERT INTO device_requests(request_id,device_id,action,status,created_at,expires_at,updated_at) VALUES (?,?,?,'pending',?,?,?)", [id, device_id, action, now, expiry, now], "run");
    changed();
    res.status(201).json({ ok: true, request_id: id, status: "pending", detail: "Awaiting phone review; expires in 10 minutes." });
  });
  app.get("/api/requests", authenticateDashboard, async (req, res) => {
    const id = req.query.device_id || null;
    if (id && !validDeviceId(id)) return res.status(400).json({ error: "Invalid device" });
    const now = new Date().toISOString();
    await query("UPDATE device_requests SET status='expired', updated_at=? WHERE status IN ('pending','delivered') AND expires_at < ?", [now, now], "run");
    res.json({ requests: await query("SELECT * FROM device_requests WHERE (CAST(? AS TEXT) IS NULL OR device_id = ?) ORDER BY created_at DESC LIMIT 100", [id, id]) });
  });
  app.get("/api/device/requests", async (req, res) => {
    res.json({ requests: await query("SELECT request_id,action,expires_at,created_at FROM device_requests WHERE device_id=? AND status IN ('pending','delivered') AND expires_at > ? ORDER BY created_at LIMIT 20", [req.deviceId, new Date().toISOString()]) });
  });
  app.post("/api/device/requests/:id/result", async (req, res) => {
    const { status, detail = "" } = req.body || {};
    if (!["delivered", "completed", "declined", "failed"].includes(status) || typeof detail !== "string" || detail.length > 800) return res.status(400).json({ error: "Invalid result" });
    const old = await query("SELECT * FROM device_requests WHERE request_id = ? AND device_id = ?", [req.params.id, req.deviceId], "get");
    if (!old) return res.status(404).json({ error: "Request not found" });
    if (!["pending", "delivered"].includes(old.status)) return res.json({ ok: true, status: old.status });
    if (old.expires_at < new Date().toISOString()) return res.status(410).json({ error: "Request expired" });
    await query("UPDATE device_requests SET status=?,detail=?,updated_at=? WHERE request_id=? AND device_id=? AND status IN ('pending','delivered') AND expires_at > ?", [status, detail, new Date().toISOString(), req.params.id, req.deviceId, new Date().toISOString()], "run");
    changed();
    res.json({ ok: true });
  });
}
module.exports = { installFeatures, ACTIONS, MAX_FILE };
