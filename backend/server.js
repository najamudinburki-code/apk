// server.js
// Install: npm install express socket.io jsonwebtoken better-sqlite3 express-rate-limit pg
// Required environment variables:
// JWT_SECRET: a randomly generated secret of at least 32 characters.
// DASHBOARD_USERNAME
// DASHBOARD_PASSWORD: at least 16 characters.
// DASHBOARD_ORIGIN: dashboard origin, e.g. https://dashboard.example.com

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

  const allowedOrigin = new URL(DASHBOARD_ORIGIN).origin;
  const MAX_JSON_BYTES = 48 * 1024;
  const JWT_ISSUER = "device-management";
  const JWT_AUDIENCE = "dashboard";

  const db = await createStore();
  const { queries, saveTelemetry } = db;

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

  function serializeObject(value) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      throw new Error("Payload must be a JSON object.");
    }

    const serialized = JSON.stringify(value);

    if (Buffer.byteLength(serialized, "utf8") > MAX_JSON_BYTES) {
      throw new Error("Payload is too large.");
    }

    return serialized;
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

    if (origin && origin !== allowedOrigin) {
      return res.status(403).json({ error: "Origin not allowed" });
    }

    if (origin) {
      res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
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

  app.get("/health", (req, res) => {
    if (shuttingDown) return res.status(503).json({ ok: false });
    // Startup initialized the database. Avoid waking cloud compute on every health probe.
    res.json({ ok: true, api_version: 3 });
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
      origin: allowedOrigin,
      methods: ["GET", "POST"],
    },
    allowRequest: (req, callback) => {
      callback(
        null,
        !req.headers.origin || req.headers.origin === allowedOrigin
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

  app.get("/api/devices", authenticateDashboard, async (req, res) => {
    const devices = (await queries.listDevices.all()).map((device) => ({
      ...device,
      latest_payload: device.latest_payload ? JSON.parse(device.latest_payload) : null,
      latest_health: device.latest_health ? JSON.parse(device.latest_health) : null,
      enabled: Boolean(device.enabled),
      online: Boolean(
        io.sockets.adapter.rooms.get(`device:${device.device_id}`)?.size
      ),
    }));

    res.json({ devices });
  });

  app.get("/api/events", authenticateDashboard, async (req, res) => {
    const target = req.query.device_id ?? null;
    const value = req.query.limit ?? "100";
    if ((target !== null && !validDeviceId(target)) ||
        typeof value !== "string" || !/^\d+$/.test(value) ||
        Number(value) < 1 || Number(value) > 200) {
      return res.status(400).json({ error: "Invalid device_id or limit (1-200)" });
    }
    const events = (await queries.listEvents.all(target, target, Number(value))).map(event => ({
      ...event,
      payload: JSON.parse(event.payload),
    }));
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

  installFeatures({ app, db, authenticateDashboard, validDeviceId, io, hash });
  installEnrollment({ app, db, authenticateDashboard, validDeviceId, io });

  // Dashboard handshake: { auth: { role: "dashboard", token: "<JWT>" } }
  // Device handshake:
  // { auth: { role: "device", device_id: "...", token: "<device_token>" } }
  io.use(async (socket, next) => {
    const auth = socket.handshake.auth || {};

    try {
      if (auth.role === "dashboard") {
        socket.data.role = "dashboard";
        socket.data.claims = verifyDashboardToken(auth.token);
      } else if (auth.role === "device") {
        if (
          !validDeviceId(auth.device_id) ||
          typeof auth.token !== "string" ||
          auth.token.length > 256
        ) {
          throw new Error("Invalid device credentials.");
        }

        const device = await queries.findDevice.get(auth.device_id);

        if (
          !device ||
          !device.enabled ||
          !crypto.timingSafeEqual(
            hash(auth.token),
            Buffer.from(device.token_hash, "hex")
          )
        ) {
          throw new Error("Invalid device credentials.");
        }

        socket.data.role = "device";
        socket.data.deviceId = device.device_id;
      } else {
        throw new Error("Invalid role.");
      }

      next();
    } catch {
      next(new Error("Unauthorized"));
    }
  });

  io.on("connection", (socket) => {
    const isDashboard = socket.data.role === "dashboard";
    const deviceId = socket.data.deviceId;

    if (isDashboard) {
      socket.join("dashboards");

      const expiryTimer = setTimeout(() => {
        socket.disconnect(true);
      }, Math.max(0, socket.data.claims.exp * 1000 - Date.now()));

      expiryTimer.unref();
      socket.on("disconnect", () => clearTimeout(expiryTimer));
    } else {
      socket.join(`device:${deviceId}`);
      Promise.resolve(queries.touchDevice.run(deviceId)).catch(() => {
        console.error("Could not update device connection time.");
      });
      io.to("dashboards").emit("device:status", {
        device_id: deviceId,
        online: true,
      });
    }

    let windowStart = Date.now();
    let eventCount = 0;

    function respond(ack, result) {
      if (typeof ack === "function") {
        ack(result);
      } else if (!result.ok) {
        socket.emit("server:error", result);
      }
    }

    async function authorize(requiredRole) {
      if (socket.data.role !== requiredRole) {
        throw new Error("Forbidden.");
      }

      if (
        requiredRole === "dashboard" &&
        socket.data.claims.exp * 1000 <= Date.now()
      ) {
        socket.disconnect(true);
        throw new Error("Token expired.");
      }

      if (requiredRole === "device") {
        const device = await queries.findDevice.get(deviceId);

        if (!device?.enabled) {
          socket.disconnect(true);
          throw new Error("Device disabled.");
        }
      }

      const now = Date.now();

      if (now - windowStart >= 1000) {
        windowStart = now;
        eventCount = 0;
      }

      if (++eventCount > 30) {
        throw new Error("Rate limit exceeded.");
      }
    }

    socket.on("data:receive", async (payload, ack) => {
      try {
        await authorize("device");

        // Identity comes from authentication, never from the supplied payload.
        const serialized = serializeObject(payload);
        const eventId = await saveTelemetry(deviceId, serialized);

        io.to("dashboards").emit("data:received", {
          event_id: eventId,
          device_id: deviceId,
          payload: JSON.parse(serialized),
        });

        respond(ack, { ok: true, event_id: eventId });
      } catch (error) {
        if (error.code) {
          console.error("Telemetry persistence failed.");
          return respond(ack, { ok: false, error: "Persistence failed." });
        }

        respond(ack, { ok: false, error: error.message });
      }
    });

    socket.on("command:send", async (message, ack) => {
      try {
        await authorize("dashboard");

        const targetId = message?.device_id;

        if (!validDeviceId(targetId)) {
          throw new Error("Invalid device_id.");
        }

        const device = await queries.findDevice.get(targetId);

        if (!device?.enabled) {
          throw new Error("Device unavailable.");
        }

        const room = `device:${targetId}`;

        if (!io.sockets.adapter.rooms.get(room)?.size) {
          throw new Error("Device offline.");
        }

        const serialized = serializeObject(message.command);
        const commandId = crypto.randomUUID();
        const command = {
          command_id: commandId,
          device_id: targetId,
          issued_at: new Date().toISOString(),
          command: JSON.parse(serialized),
        };

        await queries.insertEvent.run(
          targetId,
          "command:send",
          JSON.stringify(command)
        );

        io.to(room).emit("command:receive", command);

        // Dispatch acknowledgement; execution requires a device response.
        respond(ack, {
          ok: true,
          command_id: commandId,
          status: "dispatched",
        });
      } catch (error) {
        if (error.code) {
          console.error("Command persistence failed.");
          return respond(ack, { ok: false, error: "Persistence failed." });
        }

        respond(ack, { ok: false, error: error.message });
      }
    });

    socket.on("disconnect", () => {
      if (!isDashboard) {
        io.to("dashboards").emit("device:status", {
          device_id: deviceId,
          online: Boolean(
            io.sockets.adapter.rooms.get(`device:${deviceId}`)?.size
          ),
        });
      }
    });
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

}
main().catch((error) => {
  console.error("Backend startup failed. Check required environment variables and database connectivity.",
    error.code && /^[A-Z0-9_]{1,40}$/.test(error.code) ? error.code : error.name);
  process.exit(1);
});
