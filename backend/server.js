// server.js
// Install: npm install express socket.io jsonwebtoken better-sqlite3 express-rate-limit pg
// Required environment variables:
// JWT_SECRET: a randomly generated secret of at least 32 characters.
// DASHBOARD_USERNAME
// DASHBOARD_PASSWORD: at least 16 characters.
// DASHBOARD_ORIGIN: dashboard origin, e.g. https://dashboard.example.com
//   A comma-separated list is allowed when one backend serves several dashboard
//   addresses; each entry is compared as an exact origin, never as a prefix.

"use strict";

const express = require("express");
const http = require("node:http");
const https = require("node:https");
const fs = require("node:fs");
const crypto = require("node:crypto");
const jwt = require("jsonwebtoken");
const { createStore } = require("./store.cjs");
const { installFeatures } = require("./features.cjs");
const { installEnrollment } = require("./enrollment.cjs");
const { installationKeyHash } = require("./installation.cjs");
const { Server } = require("socket.io");
const { rateLimit } = require("express-rate-limit");

async function main() {
  const {
    JWT_SECRET,
    DASHBOARD_USERNAME,
    DASHBOARD_PASSWORD,
    DASHBOARD_ORIGIN,
    PORT = "3000",
  } = process.env;

  if (
    !JWT_SECRET ||
    Buffer.byteLength(JWT_SECRET) < 32 ||
    !DASHBOARD_USERNAME ||
    !DASHBOARD_PASSWORD ||
    DASHBOARD_PASSWORD.length < 16 ||
    !DASHBOARD_ORIGIN
  ) {
    throw new Error("Missing or invalid required environment variables.");
  }

  const allowedOrigins = new Set(
    DASHBOARD_ORIGIN.split(",")
      .map((value) => value.trim())
      .filter(Boolean)
      .map((value) => new URL(value).origin)
  );
  const JWT_ISSUER = "device-management";
  const JWT_AUDIENCE = "dashboard";

  const automaticEnrollmentHash = installationKeyHash();
  const db = await createStore();
  const { queries } = db;

  // SQLite stores UTC text and PostgreSQL a Date; both describe the same instant here.
  function stampMillis(value) {
    if (!value) return 0;
    const text = value instanceof Date ? value.toISOString() : String(value);
    const stamped = /Z|[+-]\d\d:?\d\d$/.test(text) ? text : `${text.replace(" ", "T")}Z`;
    const parsed = Date.parse(stamped);
    return Number.isNaN(parsed) ? 0 : parsed;
  }

  // A dashboard token is self-contained and cannot be recalled once handed out, so this one stored
  // instant is what ends a sign-in early: anything issued before it stops working, and it survives
  // a restart because it lives in the database. Tokens carry whole-second issue times, so the
  // boundary is a second wide: a sign-in made in the same second as the revoke still counts as
  // current, which is what lets the owner sign straight back in.
  let sessionsValidFrom = 0;
  const wholeSecond = (value) => Math.floor(value / 1000) * 1000;
  async function storedWatermark() {
    return stampMillis((await queries.sessionsValidFrom.get())?.sessions_valid_from);
  }
  async function loadSessionWatermark() {
    sessionsValidFrom = wholeSecond(await storedWatermark());
  }
  await loadSessionWatermark();

  function hash(value) {
    return crypto.createHash("sha256").update(value).digest();
  }

  function equalSecret(received, expected) {
    return (
      typeof received === "string" &&
      crypto.timingSafeEqual(hash(received), hash(expected))
    );
  }

  function validDeviceId(value) {
    return (
      typeof value === "string" &&
      /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value)
    );
  }

  function verifyDashboardToken(token) {
    if (typeof token !== "string" || token.length > 4096) {
      throw new Error("Invalid token.");
    }

    const claims = jwt.verify(token, JWT_SECRET, {
      algorithms: ["HS256"],
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
    });

    if (
      claims.role !== "dashboard" ||
      claims.sub !== DASHBOARD_USERNAME ||
      !Number.isInteger(claims.exp)
    ) {
      throw new Error("Invalid dashboard claims.");
    }

    // Anything issued before the stored instant was ended by “Sign out everywhere”.
    if (!Number.isInteger(claims.iat) || claims.iat * 1000 < sessionsValidFrom) {
      throw new Error("This sign-in has been ended.");
    }

    return claims;
  }

  function authenticateDashboard(req, res, next) {
    const match = /^Bearer ([^\s]+)$/i.exec(req.headers.authorization || "");

    try {
      req.auth = verifyDashboardToken(match?.[1]);
      next();
    } catch {
      res.status(401).json({ error: "Unauthorized" });
    }
  }

  const app = express();
  app.disable("x-powered-by");
  const proxyHops = Number(process.env.TRUST_PROXY_HOPS || "0");
  if (!Number.isInteger(proxyHops) || proxyHops < 0 || proxyHops > 10) {
    throw new Error("TRUST_PROXY_HOPS must be an integer from 0 to 10.");
  }
  // Local access trusts no proxy. Hosted deployments explicitly configure their hop count.
  app.set("trust proxy", proxyHops);

  app.use((req, res, next) => {
    const origin = req.headers.origin;

    if (origin && !allowedOrigins.has(origin)) {
      return res.status(403).json({ error: "Origin not allowed" });
    }

    if (origin) {
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Vary", "Origin");
      res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Device-Id");
      res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    }

    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");

    if (req.method === "OPTIONS") {
      return res.sendStatus(204);
    }

    next();
  });

  app.use("/api/device/files", rateLimit({ windowMs: 60_000, limit: 30, standardHeaders: "draft-7", legacyHeaders: false }));
  const smallJson = express.json({ limit: "64kb", strict: true });
  const fileJson = express.json({ limit: "6mb", strict: true });
  app.use((req, res, next) => (req.path === "/api/device/files" ? fileJson : smallJson)(req, res, next));

  // Phones compare this against their own build. Named apart from the telemetry field
  // app_version, which reports what a single phone currently runs.
  const appRelease = process.env.APP_RELEASE_VERSION ? {
    latest_app_version: process.env.APP_RELEASE_VERSION,
    ...(process.env.APP_RELEASE_URL ? { latest_app_url: process.env.APP_RELEASE_URL } : {}),
  } : {};

  app.get("/health", (req, res) => {
    if (shuttingDown) return res.status(503).json({ ok: false });
    // Startup initialized the database. Avoid waking cloud compute on every health probe.
    res.json({ ok: true, api_version: 4, automatic_enrollment: Boolean(automaticEnrollmentHash), ...appRelease });
  });

  const { TLS_CERT_PATH, TLS_KEY_PATH } = process.env;
  if (Boolean(TLS_CERT_PATH) !== Boolean(TLS_KEY_PATH)) {
    throw new Error("Set both TLS_CERT_PATH and TLS_KEY_PATH, or neither.");
  }
  const server = TLS_CERT_PATH
    ? https.createServer({
        cert: fs.readFileSync(TLS_CERT_PATH),
        key: fs.readFileSync(TLS_KEY_PATH),
      }, app)
    : http.createServer(app);
  const io = new Server(server, {
    cors: {
      origin: [...allowedOrigins],
      methods: ["GET", "POST"],
    },
    allowRequest: (req, callback) => {
      callback(
        null,
        !req.headers.origin || allowedOrigins.has(req.headers.origin)
      );
    },
    maxHttpBufferSize: 64 * 1024,
  });

  app.post(
    "/api/login",
    rateLimit({
      windowMs: 15 * 60 * 1000,
      limit: 10,
      standardHeaders: "draft-7",
      legacyHeaders: false,
    }),
    (req, res) => {
      const usernameMatches = equalSecret(
        req.body?.username,
        DASHBOARD_USERNAME
      );
      const passwordMatches = equalSecret(
        req.body?.password,
        DASHBOARD_PASSWORD
      );

      if (!usernameMatches || !passwordMatches) {
        return res.status(401).json({ error: "Invalid credentials" });
      }

      const token = jwt.sign({ role: "dashboard" }, JWT_SECRET, {
        algorithm: "HS256",
        subject: DASHBOARD_USERNAME,
        issuer: JWT_ISSUER,
        audience: JWT_AUDIENCE,
        expiresIn: "1h",
      });

      res.json({ token, token_type: "Bearer", expires_in: 3600 });
    }
  );

  /** Ends every dashboard sign-in now, not at its scheduled expiry, and keeps working after a
   *  restart because the instant lives in the database. Phones are untouched: their credentials
   *  are separate, and each device still has its own disable control. */
  async function revokeDashboardSessions() {
    await queries.revokeSessions.run();
    // Never trust another machine's clock backwards: this moment is at least now.
    sessionsValidFrom = wholeSecond(Math.max(await storedWatermark(), Date.now()));
    for (const socket of await io.in("dashboards").fetchSockets()) socket.disconnect(true);
  }

  app.post(
    "/api/sessions/revoke",
    authenticateDashboard,
    rateLimit({ windowMs: 60_000, limit: 6, standardHeaders: "draft-7", legacyHeaders: false }),
    async (req, res, next) => {
      try {
        await revokeDashboardSessions();
        res.json({ ok: true, ended: "every dashboard sign-in on this server" });
      } catch (error) { next(error); }
    }
  );

  // Phones deliver over HTTP and refresh last_seen on every poll, so recent contact — not a live
  // socket — is all "online" can honestly mean now.
  function recentlySeen(value) {
    const seen = stampMillis(value);
    return seen > 0 && Date.now() - seen < 45_000;
  }

  app.get("/api/devices", authenticateDashboard, async (req, res) => {
    const devices = (await queries.listDevices.all()).map((device) => ({
      ...device,
      latest_payload: device.latest_payload ? JSON.parse(device.latest_payload) : null,
      latest_health: device.latest_health ? JSON.parse(device.latest_health) : null,
      enabled: Boolean(device.enabled),
      online: recentlySeen(device.last_seen),
    }));

    res.json({ devices });
  });

  app.get("/api/events", authenticateDashboard, async (req, res) => {
    const target = req.query.device_id ?? null;
    const value = req.query.limit ?? "100";
    const type = req.query.type ?? null;
    const after = req.query.after ?? null;
    if ((target !== null && !validDeviceId(target)) ||
        typeof value !== "string" || !/^\d+$/.test(value) ||
        Number(value) < 1 || Number(value) > 200 ||
        (type !== null && !/^[A-Za-z0-9_-]{1,40}$/.test(type)) ||
        (after !== null && !/^\d{1,19}$/.test(after))) {
      return res.status(400).json({ error: "Invalid device_id, limit (1-200), type or after" });
    }
    // `after` is the lowest event id already shown, so a phone can keep appending while the
    // owner pages backwards through history without repeating or skipping a row.
    const events = (await queries.listEvents.all(
      target, target, type, type, after ? Number(after) : null, after ? Number(after) : null, Number(value)
    )).map(event => ({ ...event, payload: JSON.parse(event.payload) }));
    res.json({ events });
  });

  app.post("/api/devices", authenticateDashboard, async (req, res, next) => {
    const { device_id, name } = req.body || {};

    if (
      !validDeviceId(device_id) ||
      typeof name !== "string" ||
      !name.trim() ||
      name.length > 200
    ) {
      return res.status(400).json({ error: "Invalid device_id or name" });
    }

    const deviceToken = crypto.randomBytes(32).toString("base64url");

    try {
      await queries.createDevice.run(
        device_id,
        name.trim(),
        hash(deviceToken).toString("hex")
      );

      // Returned once; only the token hash is stored.
      res.status(201).json({ device_id, device_token: deviceToken });
    } catch (error) {
      if (["SQLITE_CONSTRAINT_PRIMARYKEY", "23505"].includes(error.code)) {
        return res.status(409).json({ error: "Device already exists" });
      }

      next(error);
    }
  });

  // Revoking access is rare, so cap it: a leaked dashboard token must not be able to
  // rotate or disable every phone in a loop.
  const manageLimiter = rateLimit({ windowMs: 60_000, limit: 15, standardHeaders: "draft-7", legacyHeaders: false });

  app.post("/api/devices/:id/token", authenticateDashboard, manageLimiter, async (req, res, next) => {
    const id = req.params.id;
    if (!validDeviceId(id)) return res.status(400).json({ error: "Invalid device_id" });
    const device = await queries.findDevice.get(id);
    if (!device) return res.status(404).json({ error: "Device not found" });
    const deviceToken = crypto.randomBytes(32).toString("base64url");
    try {
      // Rotate and revoke together: the old token stops working immediately.
      await queries.rotateDeviceToken.run(hash(deviceToken).toString("hex"), id);
      // Keep a pending enrollment in sync so approving it cannot restore the old credential.
      await db.features.query("UPDATE enrollment_requests SET token_hash=? WHERE device_id=? AND status='pending'",
        [hash(deviceToken).toString("hex"), id], "run");
      // The next HTTP call with the old token is rejected, so nothing else needs dropping.
      res.json({ device_id: id, device_token: deviceToken, replaces: true });
    } catch (error) { next(error); }
  });

  app.post("/api/devices/:id/enabled", authenticateDashboard, manageLimiter, async (req, res, next) => {
    const id = req.params.id;
    if (!validDeviceId(id) || typeof req.body?.enabled !== "boolean") {
      return res.status(400).json({ error: "Invalid device_id or enabled flag" });
    }
    const device = await queries.findDevice.get(id);
    if (!device) return res.status(404).json({ error: "Device not found" });
    try {
      await queries.setDeviceEnabled.run(req.body.enabled ? 1 : 0, id);
      res.json({ device_id: id, enabled: req.body.enabled });
    } catch (error) { next(error); }
  });

  // A household with several phones needs readable names; the device id stays immutable.
  app.post("/api/devices/:id/name", authenticateDashboard, manageLimiter, async (req, res, next) => {
    const id = req.params.id;
    const name = typeof req.body?.name === "string" ? req.body.name.trim() : "";
    if (!validDeviceId(id) || !name || name.length > 200) {
      return res.status(400).json({ error: "Invalid device_id or name" });
    }
    const device = await queries.findDevice.get(id);
    if (!device) return res.status(404).json({ error: "Device not found" });
    try {
      await queries.setDeviceName.run(name, id);
      res.json({ device_id: id, name });
    } catch (error) { next(error); }
  });

  const retentionDays = Number(process.env.RETENTION_DAYS || "30");
  if (!Number.isInteger(retentionDays) || retentionDays < 1 || retentionDays > 3650) {
    throw new Error("RETENTION_DAYS must be an integer from 1 to 3650.");
  }

  async function pruneNow() {
    const cutoff = new Date(Date.now() - retentionDays * 86_400_000).toISOString();
    return db.features.pruneRetention(cutoff);
  }

  const retentionTimer = setInterval(() => {
    pruneNow().then(
      removed => console.log(`Retention sweep removed ${JSON.stringify(removed)}.`),
      () => console.error("Retention sweep failed; data is unchanged.")
    );
  }, 6 * 60 * 60 * 1000);
  retentionTimer.unref();
  pruneNow().catch(() => console.error("Startup retention sweep failed; data is unchanged."));

  installFeatures({ app, db, authenticateDashboard, validDeviceId, io, hash });
  installEnrollment({ app, db, authenticateDashboard, validDeviceId, io, installationKeyHash: automaticEnrollmentHash });

  // Dashboard handshake: { auth: { role: "dashboard", token: "<JWT>" } }
  // Phones never open a socket; they deliver and poll over authenticated HTTP.
  io.use(async (socket, next) => {
    const auth = socket.handshake.auth || {};

    try {
      if (auth.role !== "dashboard") throw new Error("Invalid role.");
      socket.data.role = "dashboard";
      socket.data.claims = verifyDashboardToken(auth.token);
      next();
    } catch {
      next(new Error("Unauthorized"));
    }
  });

  io.on("connection", (socket) => {
    socket.join("dashboards");

    // A JWT lives an hour; a socket must not outlive the session it was opened with.
    const expiryTimer = setTimeout(() => {
      socket.disconnect(true);
    }, Math.max(0, socket.data.claims.exp * 1000 - Date.now()));

    expiryTimer.unref();
    socket.on("disconnect", () => clearTimeout(expiryTimer));
  });

  app.use((error, req, res, next) => {
    if (error.type === "entity.parse.failed") {
      return res.status(400).json({ error: "Invalid JSON" });
    }

    if (error.type === "entity.too.large") {
      return res.status(413).json({ error: "Payload too large" });
    }

    console.error("Request failed.");
    res.status(500).json({ error: "Internal server error" });
  });

  server.listen(Number(PORT), process.env.HOST || "0.0.0.0", () => {
    console.log(`Device management backend listening on port ${PORT}`);
  });

  let shuttingDown = false;

  function shutdown() {
    if (shuttingDown) return;
    shuttingDown = true;

    const timeout = setTimeout(() => process.exit(1), 10000);
    timeout.unref();

    io.close(async () => {
      try {
        await db.close();
        clearTimeout(timeout);
        process.exit(0);
      } catch { process.exit(1); }
    });
  }

  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
  // Windows cannot deliver those signals to a child process, so a test harness asks over IPC.
  process.on("message", message => { if (message === "shutdown") shutdown(); });

}
main().catch((error) => {
  console.error("Backend startup failed. Check required environment variables and database connectivity.",
    error.code && /^[A-Z0-9_]{1,40}$/.test(error.code) ? error.code : error.name);
  process.exit(1);
});
