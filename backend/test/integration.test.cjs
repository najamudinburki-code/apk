"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { mkdtempSync, rmSync } = require("node:fs");
const { tmpdir } = require("node:os");
const path = require("node:path");
const net = require("node:net");
const crypto = require("node:crypto");
const { io } = require("socket.io-client");
const Database = require("better-sqlite3");
const { Pool } = require("pg");
const pgTestUrl = process.env.TEST_DATABASE_URL;

test("enrollment, authenticated telemetry, live dashboard, and persistence", { timeout: 30000 }, async t => {
  const folder = mkdtempSync(path.join(tmpdir(), "system-health-test-"));
  const reservation = net.createServer();
  await new Promise(resolve => reservation.listen(0, "127.0.0.1", resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const url = `http://127.0.0.1:${port}`;
  const password = crypto.randomBytes(24).toString("base64url");
  const database = path.join(folder, "devices.sqlite");
  const env = { ...process.env, DATABASE_URL: pgTestUrl || "", PG_SSL_MODE: pgTestUrl ? "disable" : "", RENDER: "", PORT: String(port), DATABASE_PATH: database,
    JWT_SECRET: crypto.randomBytes(48).toString("hex"), DASHBOARD_USERNAME: "test-admin",
    DASHBOARD_PASSWORD: password, DASHBOARD_ORIGIN: "http://localhost:5173", TLS_CERT_PATH: "", TLS_KEY_PATH: "", TRUST_PROXY_HOPS: "1" };
  let child;
  const sockets = [];

  async function start() {
    child = spawn(process.execPath, ["server.js"], { cwd: path.join(__dirname, ".."), env, stdio: ["ignore", "pipe", "pipe"] });
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Backend startup timed out")), 10000);
      child.stdout.on("data", chunk => { if (chunk.toString().includes("listening on port")) { clearTimeout(timeout); resolve(); } });
      child.once("exit", code => { clearTimeout(timeout); reject(new Error(`Backend exited: ${code}`)); });
      child.once("error", error => { clearTimeout(timeout); reject(error); });
    });
  }
  async function stop() {
    for (const socket of sockets.splice(0)) socket.disconnect();
    if (!child || child.exitCode !== null) return;
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { child.kill("SIGKILL"); reject(new Error("Shutdown timed out")); }, 10000);
      child.once("exit", code => { clearTimeout(timeout); code === 0 ? resolve() : reject(new Error(`Shutdown failed: ${code}`)); });
      child.kill("SIGTERM");
    });
  }
  async function request(route, token, body, extraHeaders = {}) {
    const response = await fetch(url + route, {
      method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extraHeaders },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    return { status: response.status, body: await response.json() };
  }
  async function connect(auth) {
    const socket = io(url, { auth, autoConnect: false, reconnection: false, transports: ["websocket"] });
    sockets.push(socket);
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Socket timed out")), 5000);
      socket.once("connect", () => { clearTimeout(timeout); resolve(); });
      socket.once("connect_error", error => { clearTimeout(timeout); socket.disconnect(); reject(error); });
      socket.connect();
    });
    return socket;
  }
  function nextEvent(socket, name) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Missing event: ${name}`)), 5000);
      socket.once(name, value => { clearTimeout(timeout); resolve(value); });
    });
  }
  try {
    await start();
    let token, enrollment, dashboard, device, eventId, lastCaptureId;
    await t.test("public health check returns readiness without exposing credentials", async () => {
      const health = await request("/health");
      assert.equal(health.status, 200);
      assert.deepEqual(health.body, { ok: true });
    });
    await t.test("REST authentication rejects missing and incorrect credentials", async () => {
      assert.equal((await request("/api/devices")).status, 401);
      assert.equal((await request("/api/login", "", { username: "test-admin", password: "incorrect" })).status, 401);
      const login = await request("/api/login", "", { username: "test-admin", password });
      assert.equal(login.status, 200); token = login.body.token;
      assert.equal((await request("/api/devices", token, undefined, { Origin: "http://untrusted.example" })).status, 403);
    });
    await t.test("device enrollment issues a token once and rejects duplicates", async () => {
      const result = await request("/api/devices", token, { device_id: "phone-01", name: "Test phone" });
      assert.equal(result.status, 201); enrollment = result.body;
      assert.equal(enrollment.device_token.length, 43);
      assert.equal((await request("/api/devices", token, { device_id: "phone-01", name: "Duplicate" })).status, 409);
      const roster = await request("/api/devices", token);
      assert.equal(roster.body.devices[0].latest_payload, null);
      assert.ok(!("token_hash" in roster.body.devices[0]));
    });
    await t.test("socket authentication checks device identity and role", async () => {
      await assert.rejects(connect({ role: "device", device_id: "phone-01", token: "wrong" }), /Unauthorized/);
      dashboard = await connect({ role: "dashboard", token });
      device = await connect({ role: "device", device_id: "phone-01", token: enrollment.device_token });
      const result = await dashboard.timeout(5000).emitWithAck("data:receive", { type: "system_health" });
      assert.equal(result.ok, false); assert.match(result.error, /Forbidden/);
      assert.equal((await device.timeout(5000).emitWithAck("command:send", { device_id: "phone-01", command: {} })).ok, false);
    });
    await t.test("telemetry is saved before acknowledgement and reaches the dashboard", async () => {
      const live = nextEvent(dashboard, "data:received");
      const payload = { type: "system_health", timestamp: new Date().toISOString(), battery_percent: 73, uptime_ms: 900000, device_id: "spoofed-device" };
      const ack = await device.timeout(5000).emitWithAck("data:receive", payload);
      assert.equal(ack.ok, true); eventId = ack.event_id;
      const event = await live;
      assert.equal(event.device_id, "phone-01"); assert.equal(event.event_id, eventId); assert.equal(event.payload.battery_percent, 73);
      assert.equal((await request("/api/events?device_id=phone-01&limit=1", token)).body.events[0].event_id, eventId);
      const roster = await request("/api/devices", token);
      assert.equal(roster.body.devices[0].latest_payload.battery_percent, 73); assert.equal(roster.body.devices[0].online, true);
    });
    await t.test("invalid payloads and invalid event limits are rejected", async () => {
      for (const value of [null, [], { text: "x".repeat(49 * 1024) }]) assert.equal((await device.timeout(5000).emitWithAck("data:receive", value)).ok, false);
      assert.equal((await request("/api/events?limit=201", token)).status, 400);
      assert.equal((await request("/api/events?device_id=bad%20id", token)).status, 400);
    });
    await t.test("screen and notification payloads preserve the latest health sample", async () => {
      const payloads = [
        { type: "screen_text", package: "com.example.demo", text: "Demo text" },
        { type: "notification", package: "com.example.demo", title: "Demo", text: "Demo notification" },
        { type: "screen_fields", package: "com.example.demo", fields: [
          { type: "input", isPassword: true, text: "[REDACTED]", redacted: true }
        ] }
      ];
      for (const payload of payloads) {
        const live = nextEvent(dashboard, "data:received");
        const ack = await device.timeout(5000).emitWithAck("data:receive", payload);
        assert.equal(ack.ok, true); lastCaptureId = ack.event_id;
        const event = await live;
        assert.equal(event.device_id, "phone-01"); assert.deepEqual(event.payload, payload);
      }
      const roster = (await request("/api/devices", token)).body.devices[0];
      assert.equal(roster.latest_payload.type, "screen_fields");
      assert.equal(roster.latest_health.battery_percent, 73);
      const history = (await request("/api/events?device_id=phone-01&limit=3", token)).body.events;
      assert.deepEqual(history.map(event => event.payload.type), ["screen_fields", "notification", "screen_text"]);
    });
    await t.test("command dispatch remains authenticated and persists before dispatch", async () => {
      const receipt = nextEvent(device, "command:receive");
      const result = await dashboard.timeout(5000).emitWithAck("command:send", {
        device_id: "phone-01", command: { action: "prototype-test" }
      });
      assert.equal(result.ok, true);
      assert.equal(result.status, "dispatched");
      assert.equal((await receipt).command_id, result.command_id);
      const stored = (await request("/api/events?limit=1", token)).body.events[0];
      assert.equal(stored.event_type, "command:send");
      assert.equal(stored.payload.command_id, result.command_id);
    });
    await t.test("device disconnect updates dashboard status", async () => {
      const status = nextEvent(dashboard, "device:status"); device.disconnect();
      assert.deepEqual(await status, { device_id: "phone-01", online: false });
    });
    await t.test("configured reverse proxy keeps login rate limits separate by client", async () => {
      for (let attempt = 0; attempt < 10; attempt++) {
        const result = await request("/api/login", "", { username: "test-admin", password: "incorrect" },
          { "X-Forwarded-For": "192.0.2.10" });
        assert.equal(result.status, 401);
      }
      const blocked = await fetch(url + "/api/login", {
        method: "POST", headers: { "Content-Type": "application/json", "X-Forwarded-For": "192.0.2.10" },
        body: JSON.stringify({ username: "test-admin", password })
      });
      assert.equal(blocked.status, 429);
      const otherClient = await request("/api/login", "", { username: "test-admin", password },
        { "X-Forwarded-For": "192.0.2.11" });
      assert.equal(otherClient.status, 200);
    });
    await stop();
    await t.test("enrollment stores only a token hash", async () => {
      if (pgTestUrl) {
        const pg = new Pool({ connectionString: pgTestUrl, ssl: false });
        try {
          const result = await pg.query("SELECT token_hash FROM devices WHERE device_id = $1", ["phone-01"]);
          assert.equal(result.rows[0].token_hash, crypto.createHash("sha256").update(enrollment.device_token).digest("hex"));
        } finally { await pg.end(); }
        return;
      }
      const db = new Database(database, { readonly: true });
      try {
        const stored = db.prepare("SELECT token_hash FROM devices WHERE device_id = ?").get("phone-01");
        assert.equal(stored.token_hash, crypto.createHash("sha256").update(enrollment.device_token).digest("hex"));
      } finally { db.close(); }
    });
    await start();
    await t.test("saved events and enrollment survive a server restart", async () => {
      const history = await request("/api/events", token);
      assert.equal(history.body.events.length, 5);
      assert.equal(history.body.events[1].event_id, lastCaptureId);
      assert.ok(history.body.events.some(event => event.event_id === eventId));
      assert.equal((await request("/api/devices", token)).body.devices[0].latest_health.battery_percent, 73);
      await connect({ role: "device", device_id: "phone-01", token: enrollment.device_token });
    });
  } finally { await stop(); rmSync(folder, { recursive: true, force: true }); }
});

test("cloud database TLS cannot be disabled for remote hosts", async () => {
  const { createStore } = require("../store.cjs");
  const before = { url: process.env.DATABASE_URL, tls: process.env.PG_SSL_MODE };
  process.env.DATABASE_URL = "postgresql://test:test@database.example.com/test";
  process.env.PG_SSL_MODE = "disable";
  try { await assert.rejects(createStore(), /only on localhost/); }
  finally {
    if (before.url === undefined) delete process.env.DATABASE_URL; else process.env.DATABASE_URL = before.url;
    if (before.tls === undefined) delete process.env.PG_SSL_MODE; else process.env.PG_SSL_MODE = before.tls;
  }
});
