import { useCallback, useEffect, useState } from "react";
import { createDashboardSocket, SERVER_URL } from "./socket";

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

export default function App() {
  // Credentials and the one-time enrollment token stay in memory for this tab.
  const [token, setToken] = useState("");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [connected, setConnected] = useState(false);
  const [devices, setDevices] = useState([]);
  const [events, setEvents] = useState([]);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [deviceId, setDeviceId] = useState("phone-01");
  const [deviceName, setDeviceName] = useState("");
  const [enrollment, setEnrollment] = useState(null);

  const logout = useCallback(() => {
    setToken(""); setPassword(""); setDevices([]); setEvents([]);
    setEnrollment(null); setConnected(false);
  }, []);

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
    socket.on("device:status", status => {
      setDevices(previous => previous.map(device => device.device_id === status.device_id
        ? { ...device, online: status.online } : device));
    });
    socket.on("data:received", event => {
      const receivedAt = new Date().toISOString();
      setEvents(previous => mergeEvents(previous, [{ ...event, event_type: "data:receive", created_at: receivedAt }]));
      setDevices(previous => previous.map(device => device.device_id === event.device_id
        ? { ...device, latest_payload: event.payload, last_seen: receivedAt,
            ...(event.payload?.type === "system_health" ? { latest_health: event.payload } : {}) } : device));
    });
    load(); socket.connect();
    return () => {
      active = false; controller.abort();
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

  return (
    <main className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-900 px-6 py-5">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4">
          <div><p className="text-xs tracking-widest text-indigo-300">SYSTEM HEALTH</p><h1 className="text-2xl font-semibold">Your enrolled devices</h1></div>
          <div className="flex items-center gap-3"><span className={`text-sm ${connected ? "text-emerald-300" : "text-amber-300"}`}>{connected ? "Live updates connected" : "Reconnecting…"}</span><button className="secondary" onClick={() => setRefresh(v => v + 1)}>Refresh</button><button className="secondary" onClick={logout}>Sign out</button></div>
        </div>
      </header>
      <div className="mx-auto max-w-6xl space-y-8 p-6">
        {error && <p role="alert" className="rounded-lg bg-rose-950 p-4 text-rose-200">{error}</p>}
        <section className="rounded-xl border border-slate-800 bg-slate-900 p-6">
          <h2 className="text-lg font-semibold">1. Enroll a phone</h2>
          <p className="mt-1 text-sm text-slate-400">Choose a unique ID, then enter the issued token in the Android app’s “Configure enrolled device” screen.</p>
          <form onSubmit={enroll} className="mt-4 flex flex-wrap items-end gap-4">
            <label className="min-w-48 flex-1 text-sm">Device ID<input className="field" value={deviceId} onChange={e => setDeviceId(e.target.value)} pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,127}" maxLength={128} required /></label>
            <label className="min-w-48 flex-1 text-sm">Display name<input className="field" value={deviceName} onChange={e => setDeviceName(e.target.value)} maxLength={200} placeholder="My test phone" required /></label>
            <button className="primary" disabled={busy}>{busy ? "Enrolling…" : "Enroll device"}</button>
          </form>
          {enrollment && <div className="mt-5 rounded-lg border border-indigo-800 bg-indigo-950/50 p-4">
            <p className="font-medium">Device created: {enrollment.device_id}</p>
            <p className="mt-2 text-sm text-indigo-200">This token is displayed once. Copy it to your phone before closing this page.</p>
            <label className="mt-3 block text-sm">Device token<input className="field font-mono" readOnly value={enrollment.device_token} onFocus={e => e.target.select()} /></label>
            <p className="mt-3 text-sm text-slate-300">For local testing, the phone’s server URL is <code>http://YOUR-COMPUTER-LAN-IP:3000</code>. Both devices must be on the same Wi-Fi.</p>
          </div>}
        </section>
        <section>
          <h2 className="text-lg font-semibold">2. Start monitoring in the Android app</h2>
          <p className="mt-1 text-sm text-slate-400">The app sends one health sample immediately, then every five minutes. “Idle” between uploads is normal. Check the last sample time.</p>
          <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {devices.map(device => {
              const health = device.latest_health ||
                (device.latest_payload?.type === "system_health" ? device.latest_payload : null);
              return <article key={device.device_id} className="rounded-xl border border-slate-800 bg-slate-900 p-5">
                <div className="flex items-start justify-between gap-2"><div><h3 className="font-semibold">{device.name}</h3><p className="text-xs text-slate-400">{device.device_id}</p></div><span className={`text-xs ${device.online ? "text-emerald-300" : "text-slate-400"}`}>{device.online ? "Uploading" : "Idle"}</span></div>
                <dl className="mt-5 grid grid-cols-2 gap-4 text-sm">
                  <div><dt className="text-slate-400">Battery</dt><dd className="mt-1 text-xl">{Number.isFinite(health?.battery_percent) ? `${health.battery_percent}%` : "—"}</dd></div>
                  <div><dt className="text-slate-400">Uptime</dt><dd className="mt-1 text-xl">{uptime(health?.uptime_ms)}</dd></div>
                  <div className="col-span-2"><dt className="text-slate-400">Phone</dt><dd>{health ? `${health.manufacturer} ${health.model}` : "Waiting for first health sample"}</dd></div>
                  {health && <div className="col-span-2"><dt className="text-slate-400">Version</dt><dd>Android {health.android_version} (SDK {health.android_sdk}) · App {health.app_version}</dd></div>}
                  <div className="col-span-2"><dt className="text-slate-400">Last health sample</dt><dd>{time(health?.timestamp)}</dd></div>
                </dl>
              </article>;
            })}
          </div>
          {!devices.length && <p className="mt-4 rounded-lg border border-dashed border-slate-700 p-8 text-center text-slate-400">Enroll your first phone above.</p>}
        </section>
        <section className="overflow-hidden rounded-xl border border-slate-800 bg-slate-900">
          <h2 className="p-5 text-lg font-semibold">Recent received events</h2>
          <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead className="bg-slate-800 text-slate-300"><tr><th className="p-4">Received</th><th className="p-4">Device</th><th className="p-4">Type</th><th className="p-4">Battery</th><th className="p-4">Details</th></tr></thead><tbody>{events.map(event => <tr key={event.event_id} className="border-t border-slate-800"><td className="p-4">{time(event.created_at)}</td><td className="p-4">{event.device_id}</td><td className="p-4">{event.payload?.type || event.event_type}</td><td className="p-4">{Number.isFinite(event.payload?.battery_percent) ? `${event.payload.battery_percent}%` : "—"}</td><td className="p-4"><details><summary className="cursor-pointer">View payload</summary><pre className="mt-2 max-h-72 max-w-md overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(event.payload, null, 2)}</pre></details></td></tr>)}</tbody></table></div>
          {!events.length && <p className="p-5 text-sm text-slate-400">No received data yet. Configure the phone, enable notifications, and tap Start.</p>}
        </section>
      </div>
    </main>
  );
}
