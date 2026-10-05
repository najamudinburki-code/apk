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
      assert.deepEqual(health.body, { ok: true, api_version: 3 });
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
    const phoneHeaders = { "X-Device-Id": "phone-01" };
    const phone = (route, body, headers = phoneHeaders) => request(route, enrollment.device_token, body, headers);
    let fileId, requestId;
    await t.test("feature HTTP routes enforce dashboard and enrolled-device roles", async () => {
      assert.equal((await request("/api/files")).status, 401);
      assert.equal((await request("/api/requests", enrollment.device_token)).status, 401);
      assert.equal((await request("/api/device/requests", token, undefined, phoneHeaders)).status, 401);
      assert.equal((await phone("/api/device/requests", undefined, { "X-Device-Id": "spoofed" })).status, 401);
      assert.deepEqual((await phone("/api/device/requests")).body.requests, []);
    });
    await t.test("feature reports are durable and concurrent retries insert only once", async () => {
      const id = crypto.randomUUID();
      const body = { event_id: id, payload: { type: "location", latitude: 24.86, longitude: 67.01, accuracy: 10 } };
      const replies = await Promise.all([phone("/api/device/events", body), phone("/api/device/events", body)]);
      assert.ok(replies.every(r => r.status === 200));
      assert.equal(replies.filter(r => r.body.duplicate).length, 1);
      const history = (await request("/api/events?device_id=phone-01", token)).body.events;
      assert.equal(history.filter(e => e.payload.type === "location").length, 1);
      const state = await request("/api/state?device_id=phone-01", token);
      assert.equal(state.status, 200);
      assert.equal(state.body.events.find(e => e.payload.type === "location").payload.latitude, 24.86);
      assert.equal((await request("/api/state?device_id=phone-01")).status, 401);
      assert.equal((await phone("/api/device/events", { event_id: "x", payload: { text: "x".repeat(49 * 1024) } })).status, 400);
    });
    await t.test("media files upload, download and retry without duplicate rows", async () => {
      fileId = crypto.randomUUID();
      const bytes = Buffer.from("test document bytes");
      const body = { file_id: fileId, name: "demo.txt", mime: "text/plain", kind: "document", data: bytes.toString("base64") };
      assert.equal((await phone("/api/device/files", body)).status, 201);
      assert.equal((await phone("/api/device/files", body)).status, 200);
      const files = (await request("/api/files?device_id=phone-01", token)).body.files;
      assert.equal(files.length, 1); assert.equal(files[0].size, bytes.length);
      assert.ok(!("bytes" in files[0]));
      const response = await fetch(url + "/api/files/" + fileId, { headers: { Authorization: `Bearer ${token}` } });
      assert.match(response.headers.get("content-disposition"), /attachment/);
      assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
      for (const patch of [{ name: "../escape.txt" }, { data: "not base64!" }, { mime: "text/html\r\nX: bad" }]) assert.equal((await phone("/api/device/files", { ...body, file_id: crypto.randomUUID(), ...patch })).status, 400);
      const other = (await request("/api/devices", token, { device_id: "phone-02", name: "Other phone" })).body;
      assert.equal((await request("/api/device/files", other.device_token, body, { "X-Device-Id": "phone-02" })).status, 409);
      assert.equal((await request("/api/files?device_id=phone-02", token)).body.files.length, 0);
      // Large canonical base64 must not overflow the regexp engine or JSON body limit.
      const large = { ...body, file_id: crypto.randomUUID(), name: "large.bin", data: Buffer.alloc(4 * 1024 * 1024, 3).toString("base64") };
      assert.equal((await phone("/api/device/files", large)).status, 201);
      const del = await fetch(url + "/api/files/" + large.file_id, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      assert.equal(del.status, 200);
    });
    await t.test("requests await phone approval, isolate devices and retain terminal results", async () => {
      const issued = await request("/api/requests", token, { device_id: "phone-01", action: "request_photo" });
      assert.equal(issued.status, 201); requestId = issued.body.request_id;
      assert.equal(issued.body.status, "pending");
      const pending = (await phone("/api/device/requests")).body.requests;
      assert.equal(pending.length, 1); assert.equal(pending[0].request_id, requestId);
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "unknown" })).status, 400);
      const other = (await request("/api/devices", token, { device_id: "phone-03", name: "Third phone" })).body;
      assert.equal((await request(`/api/device/requests/${requestId}/result`, other.device_token, { status: "completed" }, { "X-Device-Id": "phone-03" })).status, 404);
      assert.equal((await phone(`/api/device/requests/${requestId}/result`, { status: "completed", detail: "Photo queued on phone" })).status, 200);
      assert.equal((await phone(`/api/device/requests/${requestId}/result`, { status: "delivered" })).body.status, "completed");
      assert.equal((await phone("/api/device/requests")).body.requests.length, 0);
      assert.equal((await request("/api/requests?device_id=phone-01", token)).body.requests[0].status, "completed");
    });
    const automaticId = `phone-${crypto.randomUUID()}`;
    const automaticToken = crypto.randomBytes(32).toString("base64url");
    const automaticBody = { device_id: automaticId, device_token: automaticToken, name: "Automatically connected phone" };
    const declinedId = `phone-${crypto.randomUUID()}`;
    const declinedToken = crypto.randomBytes(32).toString("base64url");
    await t.test("automatic registration is pending, idempotent and cannot upload before approval", async () => {
      assert.equal((await request("/api/enrollment/register", "", automaticBody)).status, 202);
      assert.equal((await request("/api/enrollment/register", "", automaticBody)).body.state, "pending");
      assert.equal((await request("/api/enrollment/status", automaticToken, undefined, { "X-Device-Id": automaticId })).body.state, "pending");
      assert.equal((await request("/api/device/events", automaticToken, { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": automaticId })).status, 401);
      assert.equal((await request("/api/enrollments")).status, 401);
      assert.equal((await request(`/api/enrollments/${automaticId}/approve`, "", {})).status, 401);
      const pending = (await request("/api/enrollments", token)).body.requests.find(row => row.device_id === automaticId);
      assert.ok(pending && !("token_hash" in pending) && !("device_token" in pending));
      assert.equal((await request("/api/enrollment/register", "", { ...automaticBody, device_token: crypto.randomBytes(32).toString("base64url") })).status, 401);
      assert.equal((await request("/api/enrollment/status", crypto.randomBytes(32).toString("base64url"), undefined, { "X-Device-Id": automaticId })).status, 401);
    });
    await t.test("dashboard approval activates only the matching phone and survives duplicate approval", async () => {
      const approvals = await Promise.all([request(`/api/enrollments/${automaticId}/approve`, token, {}), request(`/api/enrollments/${automaticId}/approve`, token, {})]);
      assert.ok(approvals.every(result => result.status === 200));
      assert.equal((await request("/api/enrollment/status", automaticToken, undefined, { "X-Device-Id": automaticId })).body.state, "approved");
      assert.equal((await request("/api/devices", token)).body.devices.filter(row => row.device_id === automaticId).length, 1);
      const result = await request("/api/device/events", automaticToken, { event_id: crypto.randomUUID(), payload: { type: "system_health", battery_percent: 81 } }, { "X-Device-Id": automaticId });
      assert.equal(result.status, 200);
      assert.equal((await request("/api/enrollment/register", "", automaticBody)).body.state, "approved");
    });
    await t.test("declined enrollment stays declined across phone retries", async () => {
      const body = { device_id: declinedId, device_token: declinedToken, name: "Declined phone" };
      assert.equal((await request("/api/enrollment/register", "", body)).status, 202);
      assert.equal((await request(`/api/enrollments/${declinedId}/reject`, token, {})).body.state, "rejected");
      assert.equal((await request("/api/enrollment/register", "", body)).body.state, "rejected");
      assert.equal((await request(`/api/enrollments/${declinedId}/approve`, token, {})).status, 409);
    });
    async function updateEnrollment(sqliteSql, postgresSql, values) {
      if (pgTestUrl) {
        const pool = new Pool({ connectionString: pgTestUrl, ssl: false });
        try { await pool.query(postgresSql, values); } finally { await pool.end(); }
      } else {
        const connection = new Database(database);
        try { connection.prepare(sqliteSql).run(...values); } finally { connection.close(); }
      }
    }
    await t.test("expired requests need renewal before dashboard approval", async () => {
      const id = `phone-${crypto.randomUUID()}`;
      const secret = crypto.randomBytes(32).toString("base64url");
      const body = { device_id: id, device_token: secret, name: "Renewal phone" };
      const client = { "X-Forwarded-For": "198.51.100.90" };
      assert.equal((await request("/api/enrollment/register", "", body, client)).status, 202);
      await updateEnrollment("UPDATE enrollment_requests SET expires_at=? WHERE device_id=?", "UPDATE enrollment_requests SET expires_at=$1 WHERE device_id=$2", [new Date(Date.now() - 1000).toISOString(), id]);
      assert.equal((await request(`/api/enrollments/${id}/approve`, token, {})).status, 410);
      assert.equal((await request("/api/enrollment/status", secret, undefined, { "X-Device-Id": id })).body.state, "expired");
      assert.equal((await request("/api/enrollment/register", "", body, client)).body.state, "pending");
      assert.equal((await request(`/api/enrollments/${id}/approve`, token, {})).status, 200);
    });
    await t.test("automatic connection cannot reactivate a disabled phone", async () => {
      await updateEnrollment("UPDATE devices SET enabled=0 WHERE device_id=?", "UPDATE devices SET enabled=0 WHERE device_id=$1", [automaticId]);
      assert.equal((await request("/api/enrollment/status", automaticToken, undefined, { "X-Device-Id": automaticId })).body.state, "disabled");
      assert.equal((await request("/api/enrollment/register", "", automaticBody, { "X-Forwarded-For": "198.51.100.91" })).body.state, "disabled");
      assert.equal((await request("/api/device/events", automaticToken, { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": automaticId })).status, 401);
    });
    await stop(); await start();
    await t.test("cloud/local feature outputs and request results survive restart", async () => {
      assert.equal((await request("/api/files", token)).body.files[0].file_id, fileId);
      assert.equal((await request("/api/requests", token)).body.requests[0].status, "completed");
      const deleted = await fetch(url + "/api/files/" + fileId, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      assert.equal(deleted.status, 200);
      assert.equal((await request("/api/files", token)).body.files.length, 0);
      assert.equal((await request("/api/enrollment/status", automaticToken, undefined, { "X-Device-Id": automaticId })).body.state, "disabled");
      assert.equal((await request("/api/enrollment/status", declinedToken, undefined, { "X-Device-Id": declinedId })).body.state, "rejected");
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
