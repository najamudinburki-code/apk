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
  const installationKey = crypto.randomBytes(32).toString("base64url");
  const database = path.join(folder, "devices.sqlite");
  const env = { ...process.env, DATABASE_URL: pgTestUrl || "", PG_SSL_MODE: pgTestUrl ? "disable" : "", RENDER: "", PORT: String(port), DATABASE_PATH: database,
    JWT_SECRET: crypto.randomBytes(48).toString("hex"), DASHBOARD_USERNAME: "test-admin",
    AUTO_ENROLLMENT_KEY_HASH: crypto.createHash("sha256").update(installationKey).digest("hex"),
    DASHBOARD_PASSWORD: password, DASHBOARD_ORIGIN: "http://localhost:5173,http://127.0.0.1:5173", TLS_CERT_PATH: "", TLS_KEY_PATH: "", TRUST_PROXY_HOPS: "1" };
  let child;
  const sockets = [];

  async function start() {
    child = spawn(process.execPath, ["server.js"], { cwd: path.join(__dirname, ".."), env, stdio: ["ignore", "pipe", "pipe", "ipc"] });
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
      // Windows cannot deliver SIGTERM to a child process, so it requests the same shutdown over IPC.
      if (process.platform === "win32") {
        if (!child.connected) { clearTimeout(timeout); return resolve(); }
        child.send("shutdown", error => { if (error) { clearTimeout(timeout); reject(error); } });
      } else child.kill("SIGTERM");
    });
  }
  async function request(route, token, body, extraHeaders = {}) {
    const response = await fetch(url + route, {
      method: body === undefined ? "GET" : "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extraHeaders },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    // A non-JSON body otherwise surfaces as an unrelated JSON.parse error with no route.
    try {
      return { status: response.status, body: JSON.parse(text) };
    } catch {
      throw new Error(`${response.status} for ${route}: ${text.slice(0, 200)}`);
    }
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
    let token, enrollment, dashboard, eventId, lastCaptureId;
    await t.test("public health check returns readiness without exposing credentials", async () => {
      const health = await request("/health");
      assert.equal(health.status, 200);
      assert.deepEqual(health.body, { ok: true, api_version: 4, automatic_enrollment: true });
    });
    await t.test("REST authentication rejects missing and incorrect credentials", async () => {
      assert.equal((await request("/api/devices")).status, 401);
      assert.equal((await request("/api/login", "", { username: "test-admin", password: "incorrect" })).status, 401);
      const login = await request("/api/login", "", { username: "test-admin", password });
      assert.equal(login.status, 200); token = login.body.token;
      assert.equal((await request("/api/devices", token, undefined, { Origin: "http://untrusted.example" })).status, 403);
      // A browser may reach the same dashboard as localhost or 127.0.0.1; both listed origins pass.
      for (const origin of ["http://localhost:5173", "http://127.0.0.1:5173"]) {
        assert.equal((await request("/api/devices", token, undefined, { Origin: origin })).status, 200, origin);
      }
      assert.equal((await request("/api/devices", token, undefined, { Origin: "http://localhost:5173.evil.example" })).status, 403);
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
    const phoneHeaders = { "X-Device-Id": "phone-01" };
    const phone = (route, body, headers = phoneHeaders) => request(route, enrollment.device_token, body, headers);
    await t.test("only the enrolled phone can report, and no phone holds a socket", async () => {
      const body = { event_id: crypto.randomUUID(), payload: { type: "system_health" } };
      assert.equal((await request("/api/device/events", enrollment.device_token, body, { "X-Device-Id": "phone-idle" })).status, 401);
      assert.equal((await request("/api/device/events", "wrong-token", body, phoneHeaders)).status, 401);
      assert.equal((await request("/api/device/events", token, body, phoneHeaders)).status, 401);
      // Delivery moved to authenticated HTTP, so even a valid phone token cannot open a socket.
      await assert.rejects(connect({ role: "device", device_id: "phone-01", token: enrollment.device_token }), /Unauthorized/);
      dashboard = await connect({ role: "dashboard", token });
    });
    await t.test("telemetry is saved before the phone is told it arrived and reaches the dashboard", async () => {
      const live = nextEvent(dashboard, "data:received");
      const payload = { type: "system_health", timestamp: new Date().toISOString(), battery_percent: 73, uptime_ms: 900000 };
      const sent = await phone("/api/device/events", { event_id: crypto.randomUUID(), payload });
      assert.equal(sent.status, 200); assert.equal(sent.body.ok, true); eventId = sent.body.event_id;
      const event = await live;
      assert.equal(event.device_id, "phone-01"); assert.equal(event.event_id, eventId); assert.equal(event.payload.battery_percent, 73);
      assert.equal((await request("/api/events?device_id=phone-01&limit=1", token)).body.events[0].event_id, eventId);
      const roster = await request("/api/devices", token);
      assert.equal(roster.body.devices[0].latest_payload.battery_percent, 73);
      assert.equal(roster.body.devices[0].online, true);
    });
    await t.test("invalid payloads and invalid event limits are rejected", async () => {
      for (const value of [undefined, null, [], { text: "x".repeat(49 * 1024) }]) {
        assert.equal((await phone("/api/device/events", { event_id: crypto.randomUUID(), payload: value })).status, 400);
      }
      assert.equal((await phone("/api/device/events", { event_id: "", payload: { type: "system_health" } })).status, 400);
      assert.equal((await request("/api/events?limit=201", token)).status, 400);
      assert.equal((await request("/api/events?device_id=bad%20id", token)).status, 400);
      assert.equal((await request("/api/events?type=bad%20type", token)).status, 400);
      assert.equal((await request("/api/events?after=abc", token)).status, 400);
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
        const sent = await phone("/api/device/events", { event_id: crypto.randomUUID(), payload });
        assert.equal(sent.status, 200); lastCaptureId = sent.body.event_id;
        const event = await live;
        assert.equal(event.device_id, "phone-01"); assert.deepEqual(event.payload, payload);
      }
      const roster = (await request("/api/devices", token)).body.devices[0];
      assert.equal(roster.latest_payload.type, "screen_fields");
      assert.equal(roster.latest_health.battery_percent, 73);
      const history = (await request("/api/events?device_id=phone-01&limit=3", token)).body.events;
      assert.deepEqual(history.map(event => event.payload.type), ["screen_fields", "notification", "screen_text"]);
    });
    await t.test("history filters by type and pages backwards without gaps or repeats", async () => {
      const captures = (await request("/api/events?device_id=phone-01&type=screen_text", token)).body.events;
      assert.ok(captures.length >= 1);
      assert.ok(captures.every(event => event.payload.type === "screen_text"));
      const newest = (await request("/api/events?device_id=phone-01&limit=2", token)).body.events;
      assert.equal(newest.length, 2);
      const older = (await request(`/api/events?device_id=phone-01&limit=2&after=${newest[1].event_id}`, token)).body.events;
      assert.equal(older.length, 2);
      // A page boundary must neither repeat a row nor drop one that sits between the two pages.
      assert.ok(older.every(event => newest.every(seen => seen.event_id !== event.event_id)));
      assert.equal(Number(older[0].event_id), Number(newest[1].event_id) - 1);
    });
    await t.test("a phone counts as uploading only while it keeps checking in", async () => {
      assert.equal((await request("/api/devices", token, { device_id: "phone-idle", name: "Never connected" })).status, 201);
      const roster = (await request("/api/devices", token)).body.devices;
      assert.equal(roster.find(device => device.device_id === "phone-01").online, true);
      assert.equal(roster.find(device => device.device_id === "phone-idle").online, false);
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
      assert.equal(history.body.events.length, 4);
      assert.equal(history.body.events[0].event_id, lastCaptureId);
      assert.ok(history.body.events.some(event => event.event_id === eventId));
      assert.equal((await request("/api/devices", token)).body.devices
        .find(device => device.device_id === "phone-01").latest_health.battery_percent, 73);
    });
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
    await t.test("requests move from pending to delivered to terminal without phone approval", async () => {
      const issued = await request("/api/requests", token, { device_id: "phone-01", action: "request_photo" });
      assert.equal(issued.status, 201); requestId = issued.body.request_id;
      assert.equal(issued.body.status, "pending");
      const pending = (await phone("/api/device/requests")).body.requests;
      assert.equal(pending.length, 1); assert.equal(pending[0].request_id, requestId);
      // Fetching the queue is proof the phone is awake: it refreshes presence and marks delivery.
      const delivered = (await request("/api/requests?device_id=phone-01", token)).body.requests[0];
      assert.equal(delivered.status, "delivered");
      assert.ok(Date.parse(delivered.updated_at) >= Date.parse(delivered.created_at));
      // The roster is newest-first, so look the phone up by id instead of assuming a position.
      const roster = (await request("/api/devices", token)).body.devices;
      assert.notEqual(roster.find(row => row.device_id === "phone-01").last_seen, null);
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "unknown" })).status, 400);
      const other = (await request("/api/devices", token, { device_id: "phone-03", name: "Third phone" })).body;
      assert.equal((await request(`/api/device/requests/${requestId}/result`, other.device_token, { status: "completed" }, { "X-Device-Id": "phone-03" })).status, 404);
      assert.equal((await phone(`/api/device/requests/${requestId}/result`, { status: "completed", detail: "Photo queued on phone" })).status, 200);
      assert.equal((await phone(`/api/device/requests/${requestId}/result`, { status: "delivered" })).body.status, "completed");
      assert.equal((await phone("/api/device/requests")).body.requests.length, 0);
      assert.equal((await request("/api/requests?device_id=phone-01", token)).body.requests[0].status, "completed");
    });
    await t.test("completed is only provable when the phone names the output it delivered", async () => {
      const issued = await request("/api/requests", token, { device_id: "phone-01", action: "request_scan" });
      const id = issued.body.request_id;
      assert.equal((await phone(`/api/device/requests/${id}/result`, { status: "running", detail: "Scan started on phone" })).status, 200);
      const running = (await request("/api/requests?device_id=phone-01", token)).body.requests.find(row => row.request_id === id);
      assert.equal(running.status, "running");
      // A running request is not handed out again, so the phone is never asked to do it twice.
      assert.deepEqual((await phone("/api/device/requests")).body.requests.map(row => row.request_id), []);
      const queued = await phone(`/api/device/requests/${id}/result`, { status: "completed", detail: "Scan uploaded", result_ref: `file:${crypto.randomUUID()}` });
      assert.equal(queued.status, 200);
      const done = (await request("/api/requests?device_id=phone-01", token)).body.requests.find(row => row.request_id === id);
      assert.equal(done.status, "completed");
      assert.match(done.result_ref, /^file:/);
      // A retried upload must never rewrite a finished request.
      assert.equal((await phone(`/api/device/requests/${id}/result`, { status: "reviewed", detail: "Late retry" })).body.status, "completed");
      assert.equal((await request("/api/requests?device_id=phone-01", token)).body.requests.find(row => row.request_id === id).result_ref, done.result_ref);
      // A report the owner only read is its own state, not a delivery.
      const reviewed = await request("/api/requests", token, { device_id: "phone-01", action: "request_status" });
      assert.equal((await phone(`/api/device/requests/${reviewed.body.request_id}/result`, { status: "reviewed", detail: "Report reviewed locally; nothing shared" })).status, 200);
      assert.equal((await request("/api/requests?device_id=phone-01", token)).body.requests.find(row => row.request_id === reviewed.body.request_id).status, "reviewed");
      assert.equal((await phone(`/api/device/requests/${reviewed.body.request_id}/result`, { status: "bogus" })).status, 400);
      assert.equal((await phone(`/api/device/requests/${reviewed.body.request_id}/result`, { status: "completed", result_ref: "file:../other" })).status, 400);
      assert.equal((await phone(`/api/device/requests/${reviewed.body.request_id}/result`, { status: "completed", result_ref: "record:12" })).status, 400);
    });
    await t.test("a boundary request carries validated values for the phone owner to approve", async () => {
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_geofence" })).status, 400);
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_geofence",
        args: { name: "Home", latitude: 91, longitude: 0, radius_meters: 200 } })).status, 400);
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_geofence",
        args: { name: "Home", latitude: 0, longitude: 0, radius_meters: 9 } })).status, 400);
      // Only the boundary tool takes values, so no other action can smuggle parameters to the phone.
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_photo", args: { name: "Home" } })).status, 400);
      const issued = await request("/api/requests", token, { device_id: "phone-01", action: "request_geofence",
        args: { name: " Home ", latitude: -33.86, longitude: 151.21, radius_meters: 250, note: "ignored" } });
      assert.equal(issued.status, 201);
      assert.match(issued.body.detail, /"Home"/);
      const expected = { name: "Home", latitude: -33.86, longitude: 151.21, radius_meters: 250 };
      const queued = (await phone("/api/device/requests")).body.requests
        .find(row => row.request_id === issued.body.request_id);
      // The phone gets the values as an object, stripped of anything the server did not validate.
      assert.deepEqual(queued.args, expected);
      const stored = (await request("/api/requests?device_id=phone-01", token)).body.requests
        .find(row => row.request_id === issued.body.request_id);
      assert.deepEqual(JSON.parse(stored.args), expected);
      assert.equal((await phone(`/api/device/requests/${issued.body.request_id}/result`, { status: "declined", detail: "Declined on the phone" })).status, 200);
    });
    await t.test("a rules request only narrows what the phone may do", async () => {
      // Nothing, a name this phone does not know, a cadence out of range and a list that is not a
      // list are all refused before the phone ever sees them.
      const refused = [{}, null, { tools_allowed: ["photo", "shell"] }, { tools_allowed: "photo" },
        { health_interval_minutes: 0 }, { health_interval_minutes: 1441 }, { health_interval_minutes: 2.5 }];
      for (const args of refused) {
        assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_settings", args })).status, 400);
      }
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_settings" })).status, 400);
      const issued = await request("/api/requests", token, { device_id: "phone-01", action: "request_settings",
        args: { health_interval_minutes: 2, tools_allowed: ["audio", "photo", "photo"], note: "ignored" } });
      assert.equal(issued.status, 201);
      assert.match(issued.body.detail, /health samples every 2 min/);
      assert.match(issued.body.detail, /may run audio, photo/);
      const queued = (await phone("/api/device/requests")).body.requests
        .find(row => row.request_id === issued.body.request_id);
      // The phone receives exactly the validated rules: a duplicate name is folded away, an
      // unrecognised key never travels, and the list is what the phone must obey.
      assert.deepEqual(queued.args, { health_interval_minutes: 2, tools_allowed: ["audio", "photo"] });
      assert.equal((await phone(`/api/device/requests/${issued.body.request_id}/result`,
        { status: "completed", detail: "Rules applied on the phone: health samples every 2 min" })).status, 200);
      // Turning everything off is a valid rule, and so is touching only the cadence.
      const none = await request("/api/requests", token, { device_id: "phone-01", action: "request_settings", args: { tools_allowed: [] } });
      assert.equal(none.status, 201);
      assert.match(none.body.detail, /no dashboard tool runs/);
      const slow = await request("/api/requests", token, { device_id: "phone-01", action: "request_settings", args: { health_interval_minutes: 60 } });
      assert.equal(slow.status, 201);
      assert.doesNotMatch(slow.body.detail, /may run/);
      for (const id of [none.body.request_id, slow.body.request_id]) {
        assert.equal((await phone(`/api/device/requests/${id}/result`, { status: "completed", detail: "Rules applied on the phone" })).status, 200);
      }
      assert.equal((await phone("/api/device/requests")).body.requests.length, 0);
    });
    await t.test("a live view is a short budgeted stream of ordinary uploaded frames", async () => {
      // No arguments: the phone decides the frame rate, size and limits, so nothing can be smuggled in.
      assert.equal((await request("/api/requests", token, { device_id: "phone-01", action: "request_live_view",
        args: { seconds: 600 } })).status, 400);
      const started = await request("/api/requests", token, { device_id: "phone-01", action: "request_live_view" });
      assert.equal(started.status, 201);
      assert.match(started.body.detail, /its owner allowed live view/);
      assert.match(started.body.detail, /120 seconds/);
      // A rule may keep the repeating tool off, exactly like a one-off capture.
      const narrowed = await request("/api/requests", token, { device_id: "phone-01", action: "request_settings",
        args: { tools_allowed: ["live_view"] } });
      assert.equal(narrowed.status, 201);
      assert.match(narrowed.body.detail, /may run live_view/);
      assert.equal((await phone(`/api/device/requests/${narrowed.body.request_id}/result`,
        { status: "completed", detail: "Rules applied on the phone" })).status, 200);
      const frame = { file_id: crypto.randomUUID(), name: "live-2026-10-08T12-00-00-0001.jpg",
        mime: "image/jpeg", kind: "live_frame", data: Buffer.from([0xff, 0xd8, 0xff, 0xd9]).toString("base64") };
      assert.equal((await phone("/api/device/files", frame)).status, 201);
      // The first frame is the proof the start request asked for, so it answers the request it names.
      assert.equal((await phone(`/api/device/requests/${started.body.request_id}/result`,
        { status: "running", detail: "Streaming the front camera…" })).status, 200);
      assert.equal((await phone(`/api/device/requests/${started.body.request_id}/result`,
        { status: "completed", detail: "Live view frame uploaded", result_ref: `file:${frame.file_id}` })).status, 200);
      const stopped = await request("/api/requests", token, { device_id: "phone-01", action: "request_live_view_stop" });
      assert.equal(stopped.status, 201);
      assert.match(stopped.body.detail, /stops any live view/);
      assert.equal((await phone(`/api/device/requests/${stopped.body.request_id}/result`,
        { status: "completed", detail: "12 s · 24 frame(s) uploaded · 613 KiB queued" })).status, 200);
      for (const id of [started.body.request_id, stopped.body.request_id]) {
        const row = (await request("/api/requests?device_id=phone-01", token)).body.requests
          .find(entry => entry.request_id === id);
        assert.equal(row.status, "completed");
      }
      assert.equal((await phone("/api/device/files", { ...frame, file_id: crypto.randomUUID(), kind: "video" })).status, 400);
      // A frame is an ordinary stored upload, so the dashboard may drop it like any other file. The
      // retention checks further down count the uploads they planted, so this one must not linger.
      const dropped = await fetch(url + "/api/files/" + frame.file_id, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      assert.equal(dropped.status, 200);
    });
    await t.test("history exports as CSV or JSON without leaking file bytes or hashes", async () => {
      assert.equal((await fetch(url + "/api/export/events")).status, 401);
      assert.equal((await fetch(url + "/api/export/events", { headers: { Authorization: `Bearer ${enrollment.device_token}` } })).status, 401);
      assert.equal((await fetch(url + "/api/export/nope", { headers: { Authorization: `Bearer ${token}` } })).status, 400);
      assert.equal((await fetch(url + "/api/export/events?device_id=%2F..", { headers: { Authorization: `Bearer ${token}` } })).status, 400);
      const csv = await fetch(url + "/api/export/events?device_id=phone-01", { headers: { Authorization: `Bearer ${token}` } });
      assert.equal(csv.status, 200);
      assert.match(csv.headers.get("content-type"), /text\/csv/);
      assert.match(csv.headers.get("content-disposition"), /attachment; filename="system-health-events-phone-01-/);
      const text = await csv.text();
      assert.ok(text.startsWith('"event_id","device_id","event_type","payload_type","payload","created_at"'));
      assert.ok(text.includes("system_health"));
      // A stored name that starts like a spreadsheet formula is escaped when it lands in a cell.
      const probe = await phone("/api/device/files", { file_id: crypto.randomUUID(), name: "=1+1.txt",
        mime: "text/plain", kind: "document", data: Buffer.from("secret bytes").toString("base64") });
      assert.equal(probe.status, 201);
      const filesCsv = await (await fetch(url + "/api/export/files?format=csv", { headers: { Authorization: `Bearer ${token}` } })).text();
      assert.ok(filesCsv.includes('"\'=1+1.txt"'), filesCsv);
      assert.ok(!filesCsv.includes("secret bytes"));
      const devices = await (await fetch(url + "/api/export/devices?format=json", { headers: { Authorization: `Bearer ${token}` } })).json();
      assert.equal(devices.kind, "devices");
      assert.ok(devices.rows.some(row => row.device_id === "phone-01"));
      assert.ok(!Object.keys(devices.rows[0]).includes("token_hash"));
      const requests = await (await fetch(url + "/api/export/requests?format=json", { headers: { Authorization: `Bearer ${token}` } })).json();
      assert.ok(requests.rows.some(row => row.action === "request_geofence"));
      assert.equal((await fetch(url + "/api/files/" + probe.body.file_id, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } })).status, 200);
    });
    await t.test("rotating a token destroys the credential held on the phone", async () => {
      const created = (await request("/api/devices", token, { device_id: "phone-rotate", name: "Rotation phone" })).body;
      const upload = secret => request("/api/device/events", secret,
        { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": "phone-rotate" });
      assert.equal((await upload(created.device_token)).status, 200);
      const rotated = await request("/api/devices/phone-rotate/token", token, {});
      assert.equal(rotated.status, 200);
      assert.equal(rotated.body.device_token.length, 43);
      assert.notEqual(rotated.body.device_token, created.device_token);
      assert.ok(!("device_token" in (await request("/api/devices", token)).body.devices.find(d => d.device_id === "phone-rotate")));
      assert.equal((await upload(created.device_token)).status, 401);
      assert.equal((await upload(rotated.body.device_token)).status, 200);
      assert.equal((await request("/api/devices/phone-missing/token", token, {})).status, 404);
      // Passing a body keeps this a POST, which is the route's method.
      assert.equal((await request("/api/devices/phone-rotate/token", "", {})).status, 401);
      // A new credential must not quietly re-enable a phone the dashboard disabled.
      assert.equal((await request("/api/devices/phone-rotate/enabled", token, { enabled: false })).body.enabled, false);
      const second = await request("/api/devices/phone-rotate/token", token, {});
      assert.equal(second.status, 200);
      const afterRotation = (await request("/api/devices", token)).body.devices.find(row => row.device_id === "phone-rotate");
      assert.equal(afterRotation.enabled, false);
      assert.equal((await request("/api/enrollment/status", second.body.device_token, undefined, { "X-Device-Id": "phone-rotate" })).body.state, "disabled");
    });
    await t.test("a phone is renamed without touching its credential or consent", async () => {
      assert.equal((await request("/api/devices/phone-rotate/name", "", { name: "Stealer" })).status, 401);
      const renamed = await request("/api/devices/phone-rotate/name", token, { name: "  Kitchen tablet  " });
      assert.equal(renamed.status, 200);
      assert.equal(renamed.body.name, "Kitchen tablet");
      const row = (await request("/api/devices", token)).body.devices.find(device => device.device_id === "phone-rotate");
      assert.equal(row.name, "Kitchen tablet");
      assert.equal(row.enabled, false);
      assert.equal((await request("/api/devices/phone-rotate/name", token, { name: "   " })).status, 400);
      assert.equal((await request("/api/devices/phone-rotate/name", token, { name: "x".repeat(201) })).status, 400);
      assert.equal((await request("/api/devices/phone-rotate/name", token, {})).status, 400);
      assert.equal((await request("/api/devices/phone-missing/name", token, { name: "Ghost" })).status, 404);
    });
    await t.test("disabling a phone stops uploads and requests until it is re-enabled", async () => {
      const created = (await request("/api/devices", token, { device_id: "phone-switch", name: "Switch phone" })).body;
      const upload = () => request("/api/device/events", created.device_token,
        { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": "phone-switch" });
      const client = { "X-Device-Id": "phone-switch" };
      assert.equal((await request("/api/devices/phone-switch/enabled", token, { enabled: "false" })).status, 400);
      assert.equal((await request("/api/devices/phone-switch/enabled", token, { enabled: false })).body.enabled, false);
      assert.equal((await upload()).status, 401);
      assert.equal((await request("/api/requests", token, { device_id: "phone-switch", action: "request_status" })).status, 404);
      assert.equal((await request("/api/enrollment/status", created.device_token, undefined, client)).body.state, "disabled");
      assert.equal((await request("/api/devices/phone-switch/enabled", token, { enabled: true })).body.enabled, true);
      assert.equal((await upload()).status, 200);
      const issued = await request("/api/requests", token, { device_id: "phone-switch", action: "request_status" });
      assert.equal(issued.status, 201);
      const seen = await request("/api/device/requests", created.device_token, undefined, client);
      assert.equal(seen.body.requests[0].request_id, issued.body.request_id);
      assert.equal((await request("/api/requests?device_id=phone-switch", token)).body.requests[0].status, "delivered");
      const finished = await request(`/api/device/requests/${issued.body.request_id}/result`, created.device_token, { status: "completed" }, client);
      assert.equal(finished.status, 200);
    });
    const automaticId = `phone-${crypto.randomUUID()}`;
    const joinedId = `phone-${crypto.randomUUID()}`;
    const joinedToken = crypto.randomBytes(32).toString("base64url");
    const joinedBody = { device_id: joinedId, device_token: joinedToken, name: "APK invitation phone", installation_key: installationKey };
    const joinClient = { "X-Forwarded-For": "198.51.100.101" };
    await t.test("matching APK joins immediately and concurrent retries create only one phone", async () => {
      const results = await Promise.all([request("/api/enrollment/register", "", joinedBody, joinClient), request("/api/enrollment/register", "", joinedBody, joinClient)]);
      assert.ok(results.every(result => result.status === 200 && result.body.state === "approved"));
      assert.equal((await request("/api/enrollment/status", joinedToken, undefined, { "X-Device-Id": joinedId })).body.state, "approved");
      assert.equal((await request("/api/devices", token)).body.devices.filter(row => row.device_id === joinedId).length, 1);
      assert.equal((await request("/api/device/events", joinedToken, { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": joinedId })).status, 200);
      assert.ok(!(await request("/api/enrollments", token)).body.requests.some(row => row.device_id === joinedId));
    });
    await t.test("wrong APK invitation cannot register a phone or read the dashboard", async () => {
      const id = `phone-${crypto.randomUUID()}`;
      const body = { ...joinedBody, device_id: id, installation_key: crypto.randomBytes(32).toString("base64url") };
      assert.equal((await request("/api/enrollment/register", "", body, joinClient)).status, 403);
      assert.equal((await request("/api/devices", installationKey)).status, 401);
      assert.ok(!(await request("/api/devices", token)).body.devices.some(row => row.device_id === id));
    });
    await t.test("upgrading a pending phone connects its existing identity without dashboard approval", async () => {
      const id = `phone-${crypto.randomUUID()}`;
      const secret = crypto.randomBytes(32).toString("base64url");
      const body = { device_id: id, device_token: secret, name: "Upgraded phone" };
      const client = { "X-Forwarded-For": "198.51.100.103" };
      assert.equal((await request("/api/enrollment/register", "", body, client)).body.state, "pending");
      const upgraded = await request("/api/enrollment/register", "", { ...body, installation_key: installationKey }, client);
      assert.equal(upgraded.status, 200);
      assert.equal(upgraded.body.state, "approved");
      assert.equal((await request("/api/enrollment/status", secret, undefined, { "X-Device-Id": id })).body.state, "approved");
      assert.equal((await request("/api/devices", token)).body.devices.filter(row => row.device_id === id).length, 1);
      assert.ok(!(await request("/api/enrollments", token)).body.requests.some(row => row.device_id === id));
    });
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
      assert.equal((await request("/api/enrollment/register", "", { ...body, installation_key: installationKey }, { "X-Forwarded-For": "198.51.100.102" })).body.state, "rejected");
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
      assert.equal((await request("/api/enrollment/register", "", { ...automaticBody, installation_key: installationKey }, { "X-Forwarded-For": "198.51.100.102" })).body.state, "disabled");
    });
    // Rows older than the default 30-day window must disappear with the next startup sweep.
    const staleReceipt = `retention-${crypto.randomUUID()}`;
    const staleRequest = crypto.randomUUID();
    const staleFile = crypto.randomUUID();
    const longAgo = new Date(Date.now() - 40 * 86_400_000).toISOString();
    await t.test("expired history is planted before the retention sweep runs", async () => {
      await updateEnrollment(
        `INSERT INTO events(device_id,event_type,payload,created_at) VALUES (?,'data:receive','{"type":"retention_probe"}',?)`,
        `INSERT INTO events(device_id,event_type,payload,created_at) VALUES ($1,'data:receive','{"type":"retention_probe"}'::jsonb,$2)`,
        [joinedId, longAgo]
      );
      await updateEnrollment(
        "INSERT INTO shared_files(file_id,device_id,name,mime,kind,bytes,size,created_at) VALUES (?,?,?,?,?,?,?,?)",
        "INSERT INTO shared_files(file_id,device_id,name,mime,kind,bytes,size,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)",
        [staleFile, joinedId, "stale.txt", "text/plain", "document", Buffer.from([1]), 1, longAgo]
      );
      await updateEnrollment(
        "INSERT INTO feature_receipts(receipt_id,device_id,created_at) VALUES (?,?,?)",
        "INSERT INTO feature_receipts(receipt_id,device_id,created_at) VALUES ($1,$2,$3)",
        [staleReceipt, joinedId, longAgo]
      );
      await updateEnrollment(
        "INSERT INTO device_requests(request_id,device_id,action,status,created_at,expires_at,updated_at) VALUES (?,?,'request_status','completed',?,?,?)",
        "INSERT INTO device_requests(request_id,device_id,action,status,created_at,expires_at,updated_at) VALUES ($1,$2,'request_status','completed',$3,$4,$5)",
        [staleRequest, joinedId, longAgo, longAgo, longAgo]
      );
      assert.ok((await request("/api/events?device_id=" + joinedId + "&limit=200", token)).body.events.some(e => e.payload.type === "retention_probe"));
    });
    await stop(); await start();
    await t.test("cloud/local feature outputs and request results survive restart", async () => {
      // The planted 40-day-old file is gone with the others; only the fresh upload survives.
      const files = (await request("/api/files", token)).body.files;
      assert.equal(files.length, 1); assert.equal(files[0].file_id, fileId);
      assert.equal((await request("/api/requests", token)).body.requests[0].status, "completed");
      const deleted = await fetch(url + "/api/files/" + fileId, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      assert.equal(deleted.status, 200);
      assert.equal((await request("/api/files", token)).body.files.length, 0);
      assert.equal((await request("/api/enrollment/status", automaticToken, undefined, { "X-Device-Id": automaticId })).body.state, "disabled");
      assert.equal((await request("/api/enrollment/status", declinedToken, undefined, { "X-Device-Id": declinedId })).body.state, "rejected");
      assert.equal((await request("/api/enrollment/status", joinedToken, undefined, { "X-Device-Id": joinedId })).body.state, "approved");
    });
    await t.test("the startup sweep removes expired history, receipts and finished requests", async () => {
      const events = (await request("/api/events?device_id=" + joinedId + "&limit=200", token)).body.events;
      assert.ok(!events.some(event => event.payload.type === "retention_probe"));
      assert.ok(events.some(event => event.payload.type === "system_health"));
      assert.ok(!(await request("/api/requests?device_id=" + joinedId, token)).body.requests
        .some(row => row.request_id === staleRequest));
      const client = { "X-Device-Id": joinedId };
      const replay = body => request("/api/device/events", joinedToken, body, client);
      // Its receipt is gone, so the swept event id is accepted as new again...
      const fresh = await replay({ event_id: staleReceipt, payload: { type: "system_health" } });
      assert.equal(fresh.status, 200); assert.notEqual(fresh.body.duplicate, true);
      // A receipt inside the retention window still deduplicates a retry.
      const again = await replay({ event_id: staleReceipt, payload: { type: "system_health" } });
      assert.equal(again.status, 200); assert.equal(again.body.duplicate, true);
      assert.equal((await request("/api/events?device_id=" + joinedId + "&limit=200", token)).body.events
        .filter(event => event.payload.type === "system_health").length, 2);
    });
    await stop();
    env.AUTO_ENROLLMENT_KEY_HASH = "disabled";
    await start();
    await t.test("disabling new automatic joins preserves existing phone connections", async () => {
      assert.equal((await request("/health")).body.automatic_enrollment, false);
      const fresh = { ...joinedBody, device_id: `phone-${crypto.randomUUID()}`, device_token: crypto.randomBytes(32).toString("base64url") };
      assert.equal((await request("/api/enrollment/register", "", fresh)).status, 503);
      assert.equal((await request("/api/enrollment/status", joinedToken, undefined, { "X-Device-Id": joinedId })).body.state, "approved");
      assert.equal((await request("/api/device/events", joinedToken, { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": joinedId })).status, 200);
    });
    await stop();
    env.APP_RELEASE_VERSION = "0.6.0";
    env.APP_RELEASE_URL = "https://example.invalid/system-health-0.6.0.apk";
    await start();
    await t.test("the server advertises which APK release phones should be on", async () => {
      const health = (await request("/health")).body;
      assert.equal(health.latest_app_version, "0.6.0");
      assert.equal(health.latest_app_url, "https://example.invalid/system-health-0.6.0.apk");
      // A phone reports its own version as app_version, so the advertised release must not reuse it.
      assert.equal(health.app_version, undefined);
    });
    await t.test("revoking dashboard sessions ends the sign-in everywhere and survives a restart", async () => {
      assert.equal((await request("/api/devices", token)).status, 200);
      const live = await connect({ role: "dashboard", token });
      const closed = new Promise(resolve => live.once("disconnect", resolve));
      assert.equal((await request("/api/sessions/revoke", token, {})).status, 200);
      await closed;
      assert.equal((await request("/api/devices", token)).status, 401);
      assert.equal((await request("/api/enrollments", token)).status, 401);
      // Phones keep their own credentials, so device ingest is unaffected by a dashboard sign-out.
      assert.equal((await request("/api/device/events", joinedToken, { event_id: crypto.randomUUID(), payload: { type: "system_health" } }, { "X-Device-Id": joinedId })).status, 200);
      const revived = (await request("/api/login", "", { username: "test-admin", password })).body.token;
      assert.equal((await request("/api/devices", revived)).status, 200);
      await stop();
      await start();
      assert.equal((await request("/api/devices", token)).status, 401);
      assert.equal((await request("/api/devices", revived)).status, 200);
      token = revived;
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
