"use strict";
const crypto = require("node:crypto");
const { rateLimit } = require("express-rate-limit");

// Phones generate their own random credentials. Only dashboard approval activates them.
function installEnrollment({ app, db, authenticateDashboard, validDeviceId, io }) {
  const { query } = db.features;
  const limiter = limit => rateLimit({ windowMs: 60_000, limit, standardHeaders: "draft-7", legacyHeaders: false });
  const hash = token => crypto.createHash("sha256").update(token).digest("hex");
  const matches = (token, row) => row && crypto.timingSafeEqual(Buffer.from(hash(token), "hex"), Buffer.from(row.token_hash, "hex"));
  function validCredentials(id, token) {
    return validDeviceId(id) && typeof token === "string" && /^[A-Za-z0-9_-]{43}$/.test(token);
  }
  async function state(id, token) {
    const device = await db.queries.findDevice.get(id);
    if (device) return matches(token, device) ? (device.enabled ? "approved" : "disabled") : "unauthorized";
    const pending = await query("SELECT * FROM enrollment_requests WHERE device_id=?", [id], "get");
    if (!pending) return "missing";
    if (!matches(token, pending)) return "unauthorized";
    if (pending.status === "rejected") return "rejected";
    return pending.expires_at <= new Date().toISOString() ? "expired" : "pending";
  }

  app.post("/api/enrollment/register", limiter(6), async (req, res) => {
    const { device_id: id, device_token: token, name } = req.body || {};
    if (!validCredentials(id, token) || typeof name !== "string" || !name.trim() || name.length > 200 || /[\x00-\x1f\x7f]/.test(name)) {
      return res.status(400).json({ error: "Invalid enrollment" });
    }
    let current = await state(id, token);
    if (current === "unauthorized") return res.status(401).json({ error: "Unauthorized" });
    if (["approved", "disabled", "rejected"].includes(current)) return res.json({ state: current });
    const now = new Date().toISOString();
    const expires = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    if (current === "expired") {
      await query("UPDATE enrollment_requests SET created_at=?,expires_at=?,status='pending' WHERE device_id=? AND token_hash=? AND status='pending'", [now, expires, id, hash(token)], "run");
    } else if (current === "missing") {
      // One atomic statement never overwrites another phone's identity or credential.
      await query(`INSERT INTO enrollment_requests(device_id,name,token_hash,created_at,expires_at)
        SELECT ?,?,?,?,? WHERE (SELECT COUNT(*) FROM enrollment_requests WHERE status='pending' AND expires_at>?) < 100
        ON CONFLICT(device_id) DO NOTHING`, [id, name.trim(), hash(token), now, expires, now], "run");
    }
    current = await state(id, token);
    if (current === "unauthorized") return res.status(401).json({ error: "Unauthorized" });
    if (current === "missing") return res.status(429).json({ error: "Enrollment queue is full" });
    io.to("dashboards").emit("enrollment:changed");
    res.status(current === "approved" ? 200 : 202).json({ state: current });
  });

  app.get("/api/enrollment/status", limiter(30), async (req, res) => {
    const id = req.headers["x-device-id"];
    const token = /^Bearer ([^\s]+)$/i.exec(req.headers.authorization || "")?.[1];
    if (!validCredentials(id, token)) return res.status(401).json({ error: "Unauthorized" });
    const current = await state(id, token);
    if (current === "unauthorized") return res.status(401).json({ error: "Unauthorized" });
    res.json({ state: current });
  });

  app.get("/api/enrollments", authenticateDashboard, async (req, res) => {
    const requests = await query("SELECT device_id,name,status,created_at,expires_at FROM enrollment_requests WHERE status='pending' AND expires_at>? ORDER BY created_at DESC LIMIT 100", [new Date().toISOString()]);
    res.json({ requests });
  });
  app.post("/api/enrollments/:id/approve", authenticateDashboard, async (req, res) => {
    const id = req.params.id;
    if (!validDeviceId(id)) return res.status(400).json({ error: "Invalid device" });
    const pending = await query("SELECT * FROM enrollment_requests WHERE device_id=?", [id], "get");
    if (!pending) return res.status(404).json({ error: "Enrollment not found" });
    if (pending.status === "rejected") return res.status(409).json({ error: "Enrollment was rejected" });
    if (pending.expires_at <= new Date().toISOString()) return res.status(410).json({ error: "Open the phone app to renew this request" });
    // Only this authenticated route creates an active device from the pending record.
    await query(`INSERT INTO devices(device_id,name,token_hash)
      SELECT device_id,name,token_hash FROM enrollment_requests WHERE device_id=? AND status='pending' AND expires_at>?
      ON CONFLICT(device_id) DO NOTHING`, [id, new Date().toISOString()], "run");
    const device = await db.queries.findDevice.get(id);
    if (!device || device.token_hash !== pending.token_hash || !device.enabled) return res.status(409).json({ error: "Device identity conflict" });
    await query("UPDATE enrollment_requests SET status='approved' WHERE device_id=?", [id], "run");
    io.to("dashboards").emit("enrollment:changed");
    res.json({ state: "approved", device_id: id });
  });
  app.post("/api/enrollments/:id/reject", authenticateDashboard, async (req, res) => {
    if (!validDeviceId(req.params.id)) return res.status(400).json({ error: "Invalid device" });
    const changed = await query("UPDATE enrollment_requests SET status='rejected' WHERE device_id=? AND status='pending'", [req.params.id], "run");
    if (!changed) return res.status(409).json({ error: "Enrollment is no longer pending" });
    io.to("dashboards").emit("enrollment:changed");
    res.json({ state: "rejected" });
  });
}
module.exports = { installEnrollment };
