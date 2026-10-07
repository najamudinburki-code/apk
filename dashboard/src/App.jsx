import { useCallback, useEffect, useMemo, useState } from "react";
import { createDashboardSocket, SERVER_URL } from "./socket";
import FeaturePanels from "./components/FeaturePanels";
import EnrollmentPanel from "./components/EnrollmentPanel";
import ActivityLog from "./components/ActivityLog";

// The server hands out an hour and stops accepting it after that, so the dashboard reads the
// expiry out of the token instead of letting a page go dead mid-task.
function tokenExpiry(value) {
  try {
    const body = value.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const claims = JSON.parse(atob(body.padEnd(Math.ceil(body.length / 4) * 4, "=")));
    return typeof claims.exp === "number" ? claims.exp * 1000 : 0;
  } catch {
    return 0;
  }
}

async function api(path, token, options = {}) {
  const response = await fetch(`${SERVER_URL}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.error || "Request failed");
    error.status = response.status;
    throw error;
  }
  return data;
}

function mergeEvents(previous, incoming) {
  return [...new Map([...previous, ...incoming].map(event => [event.event_id, event])).values()]
    .sort((a, b) => b.event_id - a.event_id).slice(0, 100);
}

function time(value) {
  if (!value) return "No data yet";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : date.toLocaleString();
}

function uptime(ms) {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  const minutes = Math.floor(ms / 60000);
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

function ago(value) {
  const diff = Date.now() - new Date(value).getTime();
  if (!value || !Number.isFinite(diff)) return "unknown";
  const seconds = Math.max(0, Math.round(diff / 1000));
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

// A monitoring phone checks in about every ten seconds, so a stale timestamp is the real fault signal.
const stale = (value, ms) => !value || Date.now() - new Date(value).getTime() > ms;

function lastContact(value) {
  if (!value) return { text: "No check-in yet", tone: "text-amber-300" };
  const age = ago(value);
  return stale(value, 90_000)
    ? { text: `Silent for ${age}`, tone: "text-rose-300" }
    : { text: `Checked in ${age} ago`, tone: "text-emerald-300" };
}

function DeviceCard({ device, api, token, selected, onSelect, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [secret, setSecret] = useState("");
  const [error, setError] = useState("");
  const health = device.latest_health ||
    (device.latest_payload?.type === "system_health" ? device.latest_payload : null);
  const contact = lastContact(device.last_seen);
  const battery = Number.isFinite(health?.battery_percent) ? health.battery_percent : null;
  const silent = device.enabled && !device.online && stale(device.last_seen, 90_000);
  // The token is shown once, so keep it on screen only long enough to copy into the app.
  useEffect(() => {
    if (!secret) return undefined;
    const clear = setTimeout(() => setSecret(""), 60_000);
    return () => clearTimeout(clear);
  }, [secret]);

  async function act(path, body, warning) {
    if (warning && !confirm(warning)) return;
    setBusy(true); setError("");
    try {
      const result = await api(`/api/devices/${encodeURIComponent(device.device_id)}${path}`, token, { method: "POST", body: JSON.stringify(body) });
      if (result.device_token) setSecret(result.device_token);
      onChanged();
    } catch (err) {
      setError(err.status === 401 ? "Session expired. Sign in again." : err.message);
    } finally { setBusy(false); }
  }

  async function rename() {
    const entered = window.prompt(`Display name for ${device.device_id}:`, device.name);
    if (entered === null) return;
    const name = entered.trim();
    if (!name) { setError("Enter a name, or cancel to keep the current one."); return; }
    await act("/name", { name });
  }

  return <article className={`rounded-xl border bg-slate-900 p-5 ${selected ? "border-indigo-400 ring-1 ring-indigo-400" : "border-slate-800"}`}>
    <div className="flex items-start justify-between gap-2">
      <button type="button" className="text-left" onClick={onSelect}
        title="Use this phone in the device tools below">
        <h3 className="font-semibold">{device.name}</h3>
        <p className="text-xs text-slate-400">{device.device_id}</p>
      </button>
      <div className="flex flex-col items-end gap-1">
        <span className={`text-xs ${!device.enabled ? "text-rose-300" : device.online ? "text-emerald-300" : contact.tone}`}>{!device.enabled ? "Disabled" : device.online ? "Uploading now" : contact.text}</span>
        {selected && <span className="rounded bg-indigo-900/70 px-2 py-0.5 text-xs text-indigo-200">Selected for tools</span>}
        {silent && <span className="rounded bg-amber-900/60 px-2 py-0.5 text-xs text-amber-200">Not checking in</span>}
        {battery !== null && battery <= 20 && <span className="rounded bg-rose-900/60 px-2 py-0.5 text-xs text-rose-200">Battery {battery}%</span>}
      </div>
    </div>
    <dl className="mt-5 grid grid-cols-2 gap-4 text-sm">
      <div><dt className="text-slate-400">Battery</dt><dd className="mt-1 text-xl">{battery === null ? "—" : `${battery}%`}</dd></div>
      <div><dt className="text-slate-400">Uptime</dt><dd className="mt-1 text-xl">{uptime(health?.uptime_ms)}</dd></div>
      <div className="col-span-2"><dt className="text-slate-400">Phone</dt><dd>{health ? `${health.manufacturer} ${health.model}` : "Waiting for first health sample"}</dd></div>
      {health && <div className="col-span-2"><dt className="text-slate-400">Version</dt><dd>Android {health.android_version} (SDK {health.android_sdk}) · App {health.app_version}</dd></div>}
      <div className="col-span-2"><dt className="text-slate-400">Last health sample</dt><dd>{time(health?.timestamp)}</dd></div>
      <div className="col-span-2"><dt className="text-slate-400">Last server contact</dt><dd>{time(device.last_seen)}</dd></div>
    </dl>
    {secret && <p className="mt-3 rounded-lg border border-indigo-800 bg-indigo-950/50 p-3 text-sm text-indigo-200">New token, shown once: <code className="break-all font-mono">{secret}</code> Enter it in the app’s Advanced connection settings; the old token no longer works.</p>}
    {error && <p role="alert" className="mt-3 text-sm text-rose-300">{error}</p>}
    <div className="mt-4 flex flex-wrap gap-2">
      <button className="secondary" disabled={busy} onClick={rename}>Rename</button>
      <button className="secondary" disabled={busy} onClick={() => act("/enabled", { enabled: !device.enabled },
        device.enabled ? null : `Re-enable ${device.name}? It can upload data again as soon as it connects.`)}>
        {device.enabled ? "Disable phone" : "Re-enable phone"}
      </button>
      <button className="secondary text-amber-200" disabled={busy} onClick={() => act("/token", {}, `Rotate the token for ${device.device_id}? Its current token stops working at once and you must type the new one on the phone.`)}>Rotate token</button>
    </div>
  </article>;
}

/** Turns "this phone is quiet" into the next taps on the phone, without changing any consent. */
function Troubleshoot({ devices, api, token, onUse }) {
  const [busy, setBusy] = useState("");
  const [note, setNote] = useState("");
  const problems = devices.map(device => {
    const health = device.latest_health || (device.latest_payload?.type === "system_health" ? device.latest_payload : null);
    if (!device.enabled) return { device, title: "Turned off from this dashboard",
      steps: ["Use “Re-enable phone” on its card below. It resumes on the next check-in and keeps every permission it already had."] };
    if (!device.last_seen) return { device, title: "Has never connected",
      steps: [`Check the app's server address is ${SERVER_URL}.`, "Install the current APK, open it once and allow notifications.", "Use Guided setup, grant the listed permissions, then tap Start System Health Monitor."] };
    if (device.online && !health) return { device, title: "Connected, but no health sample yet",
      steps: ["On the phone, tap Start System Health Monitor.", "Wait about ten seconds, then Refresh here."] };
    if (stale(device.last_seen, 90_000)) return { device, title: "Stopped checking in",
      steps: ["Unlock the phone and open System Health.", "Tap Start System Health Monitor. Android stops monitoring after a restart, a force-stop or its daily time limit.", "Confirm the phone still has mobile data or Wi-Fi.", "If the app reports a notification problem, allow notifications for it in Android settings."] };
    return null;
  }).filter(Boolean);

  async function test(device) {
    setBusy(device.device_id); setNote("");
    try {
      const result = await api("/api/requests", token, { method: "POST", body: JSON.stringify({ device_id: device.device_id, action: "request_status" }) });
      setNote(`${device.name}: ${result.detail} Watch its Requests panel for “running”, then “completed”.`);
    } catch (err) { setNote(`${device.name}: ${err.message}`); }
    finally { setBusy(""); }
  }

  if (!problems.length) return null;
  return <section className="rounded-xl border border-amber-900 bg-slate-900 p-6">
    <h2 className="text-lg font-semibold text-amber-200">Needs attention</h2>
    <p className="mt-1 text-sm text-slate-400">{problems.length} of {devices.length} enrolled phones are not reporting. A test request only asks for the phone's own status; it never turns a permission on.</p>
    <div className="mt-4 space-y-4">{problems.map(({ device, title, steps }) => <article key={device.device_id} className="rounded-lg border border-slate-700 p-4">
      <p className="font-medium">{device.name} <span className="text-xs text-slate-400">{device.device_id}</span> · <span className="text-amber-200">{title}</span></p>
      <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm text-slate-300">{steps.map(step => <li key={step}>{step}</li>)}</ol>
      <div className="mt-3 flex flex-wrap gap-2">
        <button className="secondary text-xs" onClick={() => onUse(device.device_id)}>Open its tools</button>
        {device.last_seen && <button className="secondary text-xs" disabled={busy === device.device_id} onClick={() => test(device)}>{busy === device.device_id ? "Queuing…" : "Send a test status request"}</button>}
      </div>
    </article>)}</div>
    {note && <p role="status" className="mt-3 text-sm text-indigo-200">{note}</p>}
  </section>;
}

export default function App() {
  // Credentials and the one-time enrollment token stay in memory for this tab.
  const [token, setToken] = useState("");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState([]);
  const [events, setEvents] = useState([]);
  // Total live events since sign-in, so the activity log can offer the newest page without
  // re-fetching every few seconds while the owner is reading an older one.
  const [liveCount, setLiveCount] = useState(0);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  // Separate counter for feature data (files, requests) so FeaturePanels can
  // reload immediately when the backend emits features:changed without also
  // re-fetching the full device roster and event history.
  const [featuresRefresh, setFeaturesRefresh] = useState(0);
  const [deviceId, setDeviceId] = useState("phone-01");
  const [deviceName, setDeviceName] = useState("");
  const [enrollment, setEnrollment] = useState(null);
  const [activeDevice, setActiveDevice] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");

  const logout = useCallback(() => {
    setToken(""); setPassword(""); setDevices([]); setEvents([]); setLiveCount(0);
    setEnrollment(null); setConnected(false); setActiveDevice("");
  }, []);

  const expiresAt = useMemo(() => (token ? tokenExpiry(token) : 0), [token]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!token) return undefined;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(timer);
  }, [token]);
  useEffect(() => {
    if (token && expiresAt && now >= expiresAt) {
      logout();
      setError("Your sign-in finished after an hour. Sign in again to keep watching the phones.");
    }
  }, [token, expiresAt, now, logout]);
  const minutesLeft = expiresAt ? Math.max(0, Math.round((expiresAt - now) / 60_000)) : null;

  async function login(event) {
    event.preventDefault(); setBusy(true); setError("");
    try {
      const result = await api("/api/login", "", {
        method: "POST", body: JSON.stringify({ username, password }),
      });
      setToken(result.token); setPassword("");
    } catch (err) {
      setError(err instanceof TypeError ? "Cannot reach the server. Check that the backend is running." : err.message);
    } finally { setBusy(false); }
  }

  useEffect(() => {
    if (!token) return;
    let active = true;
    const controller = new AbortController();
    const socket = createDashboardSocket(token);
    async function load() {
      try {
        const [roster, history] = await Promise.all([
          api("/api/devices", token, { signal: controller.signal }),
          api("/api/events?limit=100", token, { signal: controller.signal }),
        ]);
        if (!active) return;
        setDevices(roster.devices);
        setEvents(previous => mergeEvents(previous, history.events));
        setError("");
      } catch (err) {
        if (!active || err.name === "AbortError") return;
        if (err.status === 401) logout();
        setError(err instanceof TypeError ? "Cannot reach the server." : err.message);
      }
    }
    socket.on("connect", () => { setConnected(true); load(); });
    socket.on("disconnect", reason => {
      setConnected(false);
      if (reason === "io server disconnect") {
        logout(); setError("Session ended. Please sign in again.");
      }
    });
    socket.on("connect_error", err => {
      setConnected(false);
      if (err.message === "Unauthorized") {
        logout(); setError("Session expired. Please sign in again.");
      } else { setError("Live connection unavailable. Check the server and refresh."); }
    });
    socket.on("enrollment:changed", load);
    socket.on("data:received", event => {
      const receivedAt = new Date().toISOString();
      setLiveCount(value => value + 1);
      setEvents(previous => mergeEvents(previous, [{ ...event, event_type: "data:receive", created_at: receivedAt }]));
      setDevices(previous => previous.map(device => device.device_id === event.device_id
        ? { ...device, latest_payload: event.payload, last_seen: receivedAt,
            ...(event.payload?.type === "system_health" ? { latest_health: event.payload } : {}) } : device));
    });
    // The backend emits this when a file is uploaded or a request status changes.
    // Incrementing featuresRefresh tells FeaturePanels to reload immediately.
    socket.on("features:changed", () => setFeaturesRefresh(v => v + 1));
    load(); socket.connect();
    const timer = setInterval(load, 20_000);
    return () => {
      active = false; controller.abort(); clearInterval(timer);
      socket.removeAllListeners(); socket.disconnect();
    };
  }, [token, refresh, logout]);

  async function enroll(event) {
    event.preventDefault(); setBusy(true); setError(""); setEnrollment(null);
    try {
      const result = await api("/api/devices", token, {
        method: "POST", body: JSON.stringify({ device_id: deviceId.trim(), name: deviceName.trim() }),
      });
      setEnrollment(result); setRefresh(value => value + 1);
    } catch (err) {
      if (err.status === 401) logout();
      setError(err instanceof TypeError ? "Cannot reach the server." : err.message);
    } finally { setBusy(false); }
  }

  // A dashboard token cannot be recalled once issued, so this asks the server to stop accepting
  // every sign-in older than this moment — the control to reach for after a shared laptop.
  async function signOutEverywhere() {
    if (!window.confirm("End every dashboard sign-in on this server, including this one? Phones keep uploading; only the dashboard has to sign in again.")) return;
    setBusy(true);
    try {
      await api("/api/sessions/revoke", token, { method: "POST", body: "{}" });
      logout();
    } catch (err) {
      setError(err instanceof TypeError ? "Cannot reach the server." : err.message);
      logout();
    } finally { setBusy(false); }
  }

  if (!token) return (
    <main className="grid min-h-screen place-items-center bg-slate-950 p-6 text-slate-100">
      <form onSubmit={login} className="w-full max-w-md space-y-5 rounded-2xl border border-slate-700 bg-slate-900 p-8">
        <div><p className="text-sm text-indigo-300">SYSTEM HEALTH</p><h1 className="mt-2 text-2xl font-semibold">Sign in to your dashboard</h1></div>
        <p className="text-sm text-slate-400">Use the username and password printed by <code>npm run setup</code> in the backend folder.</p>
        <label className="block text-sm">Username<input className="field" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required /></label>
        <label className="block text-sm">Password<input className="field" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
        {error && <p role="alert" className="text-sm text-rose-300">{error}</p>}
        <button className="primary w-full" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        <p className="break-all text-xs text-slate-500">Server: {SERVER_URL}</p>
      </form>
    </main>
  );

  const healthOf = device => device.latest_health ||
    (device.latest_payload?.type === "system_health" ? device.latest_payload : null);
  const needle = query.trim().toLowerCase();
  const visible = devices.filter(device => {
    const health = healthOf(device);
    if (needle && ![device.name, device.device_id, health?.manufacturer, health?.model,
      health?.app_version, health?.android_version].filter(Boolean)
      .some(value => String(value).toLowerCase().includes(needle))) return false;
    if (statusFilter === "online") return device.online;
    if (statusFilter === "silent") return device.enabled && !device.online && stale(device.last_seen, 90_000);
    if (statusFilter === "disabled") return !device.enabled;
    if (statusFilter === "low") { const level = health?.battery_percent; return Number.isFinite(level) && level <= 20; }
    return true;
  });

  return (
    <main className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-900 px-6 py-5">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4">
          <div><p className="text-xs tracking-widest text-indigo-300">SYSTEM HEALTH</p><h1 className="text-2xl font-semibold">Your enrolled devices</h1></div>
          <div className="flex items-center gap-3">{minutesLeft !== null && <span className={`text-sm ${minutesLeft <= 5 ? "text-amber-300" : "text-slate-400"}`}>{minutesLeft <= 5 ? `Sign-in ends in ${minutesLeft} min` : "Signed in for one hour"}</span>}<span className={`text-sm ${connected ? "text-emerald-300" : "text-amber-300"}`}>{connected ? "Live updates connected" : "Reconnecting…"}</span><button className="secondary" onClick={() => setRefresh(v => v + 1)}>Refresh</button><button className="secondary" disabled={busy} onClick={signOutEverywhere}>Sign out everywhere</button><button className="secondary" onClick={logout}>Sign out</button></div>
        </div>
      </header>
      <div className="mx-auto max-w-6xl space-y-8 p-6">
        {error && <p role="alert" className="rounded-lg bg-rose-950 p-4 text-rose-200">{error}</p>}
        <EnrollmentPanel api={api} token={token} devices={devices} onUnauthorized={logout} onChanged={() => setRefresh(value => value + 1)} />
        <section className="rounded-xl border border-slate-800 bg-slate-900 p-6">
          <h2 className="text-lg font-semibold">Advanced manual enrollment</h2>
          <p className="mt-1 text-sm text-slate-400">The current APK connects automatically. This optional form remains available for custom device IDs and manual connection through the app's Advanced connection settings.</p>
          <form onSubmit={enroll} className="mt-4 flex flex-wrap items-end gap-4">
            <label className="min-w-48 flex-1 text-sm">Device ID<input className="field" value={deviceId} onChange={e => setDeviceId(e.target.value)} pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,127}" maxLength={128} required /></label>
            <label className="min-w-48 flex-1 text-sm">Display name<input className="field" value={deviceName} onChange={e => setDeviceName(e.target.value)} maxLength={200} placeholder="My test phone" required /></label>
            <button className="primary" disabled={busy}>{busy ? "Enrolling…" : "Enroll device"}</button>
          </form>
          {enrollment && <div className="mt-5 rounded-lg border border-indigo-800 bg-indigo-950/50 p-4">
            <p className="font-medium">Device created: {enrollment.device_id}</p>
            <p className="mt-2 text-sm text-indigo-200">This token is displayed once. Copy it to your phone before closing this page.</p>
            <label className="mt-3 block text-sm">Device token<input className="field font-mono" readOnly value={enrollment.device_token} onFocus={e => e.target.select()} /></label>
            <p className="mt-3 text-sm text-slate-300">The phone’s server URL is <code>{SERVER_URL}</code>. Enter this address in Advanced connection settings for a manual connection.</p>
          </div>}
        </section>
        <section>
          <h2 className="text-lg font-semibold">Connected phones</h2>
          <p className="mt-1 text-sm text-slate-400">The current APK starts health monitoring after connection and Android notification permission. It sends one sample immediately, then every five minutes, while a remote request check runs about every ten seconds. “Silent for …” means monitoring is off or the phone has no network. Disable a phone here to cut it off; rotate its token to revoke the one stored on the device. Tap a name to use that phone in the device tools below.</p>
          <div className="mt-4 flex flex-wrap items-end gap-3">
            <label className="min-w-56 flex-1 text-sm">Search<input className="field" value={query} onChange={e => setQuery(e.target.value)} placeholder="Name, ID, model, Android or app version" /></label>
            <label className="text-sm">Status<select className="field" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
              <option value="all">All phones</option><option value="online">Uploading now</option><option value="silent">Silent</option><option value="disabled">Disabled</option><option value="low">Battery 20% or less</option>
            </select></label>
            <p className="text-sm text-slate-400">{visible.length} of {devices.length}</p>
          </div>
          <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {visible.map(device => <DeviceCard key={device.device_id} device={device} api={api} token={token}
              selected={device.device_id === (activeDevice || devices[0]?.device_id)}
              onSelect={() => setActiveDevice(device.device_id)}
              onChanged={() => setRefresh(value => value + 1)} />)}
          </div>
          {!devices.length && <p className="mt-4 rounded-lg border border-dashed border-slate-700 p-8 text-center text-slate-400">Open the current APK on your phone to connect automatically.</p>}
          {devices.length > 0 && !visible.length && <p className="mt-4 rounded-lg border border-dashed border-slate-700 p-8 text-center text-slate-400">No phone matches this search. Clear it or choose “All phones”.</p>}
        </section>
        <Troubleshoot devices={devices} api={api} token={token} onUse={device => setActiveDevice(device)} />
        <FeaturePanels token={token} devices={devices} events={events} api={api} featuresRefresh={featuresRefresh} activeDevice={activeDevice} />
        <ActivityLog api={api} token={token} devices={devices} liveCount={liveCount} onUnauthorized={logout} />
      </div>
    </main>
  );
}
