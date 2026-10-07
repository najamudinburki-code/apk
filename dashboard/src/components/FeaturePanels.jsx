import { useEffect, useState } from "react";
import { MapContainer, TileLayer, CircleMarker, Popup } from "react-leaflet";
import "leaflet/dist/leaflet.css";
import { SERVER_URL } from "../socket";

const names = { request_status: "Phone status", request_screenshot: "Screenshot", request_photo: "Photo", request_audio: "15-second audio", request_location: "Start location", request_scan: "Nearby scan", request_geofence: "Add a geofence", request_settings: "Phone rules" };
// Two tools carry values, so they are sent from their own forms instead of a one-tap button.
const valued = ["request_geofence", "request_settings"];
const quickActions = Object.keys(names).filter(action => !valued.includes(action));
// These names are the request action without its prefix, and must match the backend's rule list.
const toolNames = { photo: "Photo", audio: "Microphone clip", screenshot: "Screenshot", location: "Location and boundaries", scan: "Nearby scan", geofence: "Add a boundary" };
const ruleTools = Object.keys(toolNames);
const fromReported = rules => ({
  interval: String(rules.health_interval_minutes ?? 5),
  tools: Array.isArray(rules.tools_allowed) ? rules.tools_allowed.filter(name => name in toolNames) : ruleTools,
});
function reportedRules(rules) {
  if (typeof rules?.explanation === "string") return rules.explanation;
  const allowed = Array.isArray(rules?.tools_allowed) ? rules.tools_allowed : null;
  const cadence = Number.isFinite(rules?.health_interval_minutes) ? `health samples every ${rules.health_interval_minutes} min` : "health cadence unknown";
  const tools = allowed === null ? "every tool this phone already allows"
    : allowed.length ? `the dashboard may run ${allowed.map(name => toolNames[name] || name).join(", ")}` : "no dashboard tool runs";
  return `${cadence} · ${tools}`;
}
const values = text => { try { return JSON.parse(text || "{}"); } catch { return {}; } };
const stamp = value => value ? new Date(value).toLocaleString() : "—";
const statusColor = status => ({ completed: "text-emerald-300", running: "text-sky-300 animate-pulse", reviewed: "text-teal-300", delivered: "text-indigo-300", failed: "text-rose-300", declined: "text-amber-300", expired: "text-slate-400" }[status] || "text-slate-200");
const panel = "space-y-4 rounded-xl border border-slate-800 bg-slate-900 p-5";

/** The phone stores which server record answers a request, so the dashboard can show the proof. */
function RequestOutput({ output, files, events, token, remove }) {
  const [kind, id] = String(output || "").split(":");
  if (!id) return null;
  if (kind === "file") {
    const file = files.find(f => f.file_id === id);
    return file
      ? <SharedFile file={file} token={token} remove={remove} />
      : <p className="text-xs text-slate-400">Its uploaded file is no longer on the server.</p>;
  }
  if (kind === "event") {
    const event = events.find(e => String(e.event_id) === id);
    return event
      ? <details className="rounded-lg border border-slate-700 p-3"><summary className="cursor-pointer text-sm">Uploaded report</summary><pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(event.payload, null, 2)}</pre></details>
      : <p className="text-xs text-slate-400">Its uploaded report is no longer in the last 100 events.</p>;
  }
  return null;
}

function readable(payload) {
  if (payload.type === "notification") return [payload.title, payload.text].filter(v => typeof v === "string" && v).join("\n");
  if (payload.type === "screen_fields") {
    const lines = [...(payload.event_text ? [payload.event_text] : []), ...(Array.isArray(payload.fields) ? payload.fields : []).filter(f => f && typeof f === "object").map(f => f.isPassword ? "[REDACTED]" : f.text || f.contentDescription || "")];
    return [...new Set(lines.filter(s => typeof s === "string" && s.trim()))].join("\n");
  }
  return typeof payload.text === "string" ? payload.text : typeof payload.content === "string" ? payload.content : "";
}

function SharedFile({ file, token, remove }) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  async function fetchFile(preview) {
    setBusy(true); setError("");
    try {
      const response = await fetch(`${SERVER_URL}/api/files/${file.file_id}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error("File unavailable. Refresh or sign in again.");
      const blob = await response.blob();
      if (preview) setUrl(URL.createObjectURL(blob));
      else {
        const link = document.createElement("a"), downloadUrl = URL.createObjectURL(blob);
        link.href = downloadUrl; link.download = file.name; link.click();
        setTimeout(() => URL.revokeObjectURL(downloadUrl), 60000);
      }
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  const image = ["image/jpeg", "image/png", "image/webp"].includes(file.mime);
  const audio = ["audio/mp4", "audio/mpeg", "audio/wav", "audio/ogg"].includes(file.mime);
  return <article className="space-y-3 rounded-lg border border-slate-700 p-4">
    <p className="break-all font-medium">{file.name}</p>
    <p className="text-xs text-slate-400">{file.kind} · {(file.size / 1024).toFixed(1)} KiB · {stamp(file.created_at)}</p>
    <div className="flex flex-wrap gap-2">
      {(image || audio) && <button className="secondary" disabled={busy} onClick={() => fetchFile(true)}>Preview</button>}
      <button className="secondary" disabled={busy} onClick={() => fetchFile(false)}>Download</button>
      <button className="secondary text-rose-300" onClick={() => { if (confirm(`Delete cloud copy of ${file.name}? The phone's local copy remains.`)) remove(file.file_id); }}>Delete cloud copy</button>
    </div>
    {url && image && <img className="max-h-80 rounded-lg object-contain" src={url} alt={file.name} />}
    {url && audio && <audio className="w-full" src={url} controls />}
    {error && <p role="alert" className="text-sm text-rose-300">{error}</p>}
  </article>;
}

export default function FeaturePanels({ token, devices, events, api, featuresRefresh, activeDevice }) {
  const [device, setDevice] = useState("");
  const [tab, setTab] = useState("Text and notifications");
  const [files, setFiles] = useState([]);
  const [requests, setRequests] = useState([]);
  const [history, setHistory] = useState([]);
  const [latestEvents, setLatestEvents] = useState([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [logType, setLogType] = useState("all");
  const [logSearch, setLogSearch] = useState("");
  const [fence, setFence] = useState({ name: "", latitude: "", longitude: "", radius: "300" });
  const [exportKind, setExportKind] = useState("events");
  const [exportAll, setExportAll] = useState(false);
  const [rules, setRules] = useState({ interval: "5", tools: ruleTools });
  useEffect(() => { if (!device && devices.length) setDevice(devices[0].device_id); }, [device, devices]);
  // Choosing a phone on the roster card above is the primary way to switch here.
  useEffect(() => { if (activeDevice) setDevice(activeDevice); }, [activeDevice]);
  useEffect(() => {
    if (!device) return;
    const controller = new AbortController();
    let active = true;
    async function load() {
      try {
        const query = `?device_id=${encodeURIComponent(device)}`;
        const [f, r, h, latest] = await Promise.all([api(`/api/files${query}`, token, { signal: controller.signal }), api(`/api/requests${query}`, token, { signal: controller.signal }), api(`/api/events${query}&limit=100`, token, { signal: controller.signal }), api(`/api/state${query}`, token, { signal: controller.signal })]);
        if (active) { setFiles(f.files); setRequests(r.requests); setHistory(h.events); setLatestEvents(latest.events); setError(""); }
      } catch (e) {
        if (active && e.name !== "AbortError") setError(e.status === 404 ? "Deploy the updated backend to enable tool uploads and requests." : e.message);
      }
    }
    load(); const timer = setInterval(load, 15000);
    return () => { active = false; controller.abort(); clearInterval(timer); };
  // featuresRefresh increments when the socket receives features:changed, causing
  // an immediate reload without waiting for the 15-second polling interval.
  }, [device, token, api, refresh, featuresRefresh]);
  const incoming = [...new Map([...history, ...events.filter(e => e.device_id === device)].map(e => [String(e.event_id), e])).values()].sort((a, b) => Number(b.event_id) - Number(a.event_id)).slice(0, 100);
  const text = incoming.filter(e => ["screen_fields", "accessibility", "screen_text", "notification"].includes(e.payload?.type));
  const locations = incoming.filter(e => e.payload?.type === "location" && Number.isFinite(e.payload.latitude) && Number.isFinite(e.payload.longitude) && Math.abs(e.payload.latitude) <= 90 && Math.abs(e.payload.longitude) <= 180);
  const savedLocation = latestEvents.find(e => e.payload?.type === "location" && Number.isFinite(e.payload.latitude) && Number.isFinite(e.payload.longitude) && Math.abs(e.payload.latitude) <= 90 && Math.abs(e.payload.longitude) <= 180);
  const latest = locations[0]?.payload || savedLocation?.payload;
  const scans = [...new Map([...latestEvents, ...incoming].map(e => [String(e.event_id), e])).values()].filter(e => e.payload?.type === "environment_scan");
  const fences = incoming.filter(e => e.payload?.type === "geofence");
  // The phone answers a status request with the rules it is really obeying, newest report first.
  const phoneRules = [...latestEvents, ...incoming]
    .map(e => (e.payload?.type === "device_status" ? e.payload.rules : null)).find(Boolean) || null;
  async function send(action, args) {
    setError(""); setNotice("");
    try {
      const result = await api("/api/requests", token, { method: "POST", body: JSON.stringify({ device_id: device, action, ...(args ? { args } : {}) }) });
      setNotice(result.detail); setRefresh(n => n + 1);
    } catch (e) { setError(e.message); }
  }
  function sendGeofence() {
    const radius = Number(fence.radius);
    if (!fence.name.trim()) return setError("Give the boundary a name the owner will recognise.");
    if (![Number(fence.latitude), Number(fence.longitude)].every(v => Number.isFinite(v))) return setError("Enter a latitude and longitude, for example -33.86 and 151.21.");
    if (!Number.isFinite(radius) || radius < 100 || radius > 10000) return setError("Use a radius between 100 and 10000 metres.");
    send("request_geofence", { name: fence.name.trim(), latitude: Number(fence.latitude), longitude: Number(fence.longitude), radius_meters: radius });
  }
  function sendRules() {
    const minutes = Number(rules.interval);
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return setError("Use a whole number of minutes from 1 to 1440 for health samples.");
    // Ordered by the canonical list so a rule reads the same way every time it is sent.
    send("request_settings", { health_interval_minutes: minutes, tools_allowed: ruleTools.filter(name => rules.tools.includes(name)) });
  }
  async function download(kind, format) {
    setError(""); setNotice("");
    try {
      const query = new URLSearchParams({ format, ...(!exportAll && device ? { device_id: device } : {}) });
      const response = await fetch(`${SERVER_URL}/api/export/${kind}?${query}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || "Export failed.");
      const name = (response.headers.get("content-disposition") || "").match(/filename="?([^";]+)"?/)?.[1] || `system-health-${kind}.${format}`;
      const href = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = href; link.download = name; link.click();
      setTimeout(() => URL.revokeObjectURL(href), 60000);
      setNotice(`Downloaded ${name}. File contents and enrollment secrets are never part of an export.`);
    } catch (e) { setError(e.message); }
  }
  async function remove(id) {
    try { await api(`/api/files/${id}`, token, { method: "DELETE" }); setRefresh(n => n + 1); }
    catch (e) { setError(e.message); }
  }
  const tabs = ["Text and notifications", "Location", "Files", "Requests", "Phone rules", "Nearby scans", "Logs", "Export"];
  return <section className="space-y-5" aria-label="Device tools">
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div><h2 className="text-xl font-semibold">Device tools</h2><p className="mt-1 text-sm text-slate-400">Open “Device tools, location and shared files” in the updated Android app to use these features.</p></div>
      <label className="min-w-52 text-sm">Selected phone<select className="field" value={device} onChange={e => { setDevice(e.target.value); setHistory([]); setLatestEvents([]); setFiles([]); setRequests([]); }}>{!devices.length && <option value="">Enroll a phone first</option>}{devices.map(d => <option key={d.device_id} value={d.device_id}>{d.name} ({d.device_id})</option>)}</select></label>
    </div>
    <nav className="flex flex-wrap gap-2" aria-label="Tool panels">{tabs.map(t => <button key={t} className={tab === t ? "primary" : "secondary"} onClick={() => setTab(t)}>{t}</button>)}</nav>
    {error && <p role="alert" className="rounded-lg bg-rose-950 p-4 text-rose-200">{error}</p>}
    {notice && <p role="status" className="rounded-lg bg-indigo-950 p-4 text-indigo-200">{notice}</p>}
    {tab === "Text and notifications" && <div className={panel}>
      <h3 className="font-semibold">Readable shared text</h3>
      <p className="text-sm text-slate-400">Approve automatic app sharing or choose apps by name on the phone, then enable one accessibility reader and Notification Reader. Incoming message notifications and visible screen text are separate sources. Text that an app hides from accessibility cannot be extracted.</p>
      {!text.length && <p>No shared text received yet.</p>}
      {text.map(e => <article key={e.event_id} className="rounded-lg border border-slate-700 p-4">
        <p className="text-sm text-indigo-300">{e.payload.type === "notification" ? "Notification" : "Visible screen text"} · {e.payload.package || e.payload.package_name} · {stamp(e.payload.timestamp || e.created_at)}</p>
        <p className="mt-3 whitespace-pre-wrap break-words">{readable(e.payload) || e.payload.capture_status || "No readable text exposed in this event."}</p>
      </article>)}
    </div>}
    {tab === "Location" && <div className={panel}>
      <h3 className="font-semibold">Latest shared location</h3><p className="text-sm text-slate-400">Start location sharing on the phone. GPS may take time indoors. Geofences need “Allow all the time” location permission. Map tiles are provided by OpenStreetMap.</p>
      {latest ? <><p>{latest.latitude}, {latest.longitude} · ±{latest.accuracy} m · {stamp(latest.timestamp)}</p><MapContainer key={`${device}-${locations[0]?.event_id || savedLocation?.event_id}`} center={[latest.latitude, latest.longitude]} zoom={15} style={{ height: 360, borderRadius: 12 }}><TileLayer attribution='© OpenStreetMap contributors' url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" /><CircleMarker center={[latest.latitude, latest.longitude]} radius={8}><Popup>{stamp(latest.timestamp)}</Popup></CircleMarker></MapContainer></> : <p>No location uploaded yet.</p>}
      <h4 className="font-medium">Add a boundary on this phone</h4>
      <p className="text-sm text-slate-400">This queues a request; nothing is watched until the owner approves it on the phone. Android needs “Allow all the time” location access there, and crossings are shared only while location sharing is running. Copy the coordinates from a map site.</p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-40 text-sm">Name<input className="field" value={fence.name} onChange={e => setFence({ ...fence, name: e.target.value })} placeholder="Home" /></label>
        <label className="min-w-32 text-sm">Latitude<input className="field" type="number" step="any" value={fence.latitude} onChange={e => setFence({ ...fence, latitude: e.target.value })} placeholder="-33.86" /></label>
        <label className="min-w-32 text-sm">Longitude<input className="field" type="number" step="any" value={fence.longitude} onChange={e => setFence({ ...fence, longitude: e.target.value })} placeholder="151.21" /></label>
        <label className="min-w-28 text-sm">Radius (m)<input className="field" type="number" min="100" max="10000" value={fence.radius} onChange={e => setFence({ ...fence, radius: e.target.value })} /></label>
        <button className="secondary" disabled={!device} onClick={sendGeofence}>Ask the phone to watch it</button>
      </div>
      <h4 className="font-medium">Geofence events</h4>{fences.length ? fences.map(e => <pre key={e.event_id} className="overflow-auto text-sm">{JSON.stringify(e.payload, null, 2)}</pre>) : <p className="text-sm text-slate-400">No boundary events received yet.</p>}
    </div>}
    {tab === "Files" && <div className={panel}>
      <h3 className="font-semibold">Uploaded files</h3><p className="text-sm text-slate-400">Photos, screenshots, microphone clips and explicitly chosen documents appear here after upload. Limits: 4 MiB/file, 100 MiB and 500 files/phone. Uploads are deleted with the rest of the history after RETENTION_DAYS (30 by default).</p>
      <button className="secondary" onClick={() => setRefresh(n => n + 1)}>Refresh files</button>
      <div className="grid gap-4 md:grid-cols-2">{files.map(f => <SharedFile key={f.file_id} file={f} token={token} remove={remove} />)}</div>{!files.length && <p>No uploaded files yet. Capture or select a file on the phone.</p>}
    </div>}
    {tab === "Requests" && <div className={panel}>
      <h3 className="font-semibold">Ask the phone to run a tool</h3>
      <p className="text-sm text-slate-400">Requests expire after 10 minutes. While monitoring is on the phone checks for new work about every 10 seconds, so “pending” becomes “delivered” quickly. Photo and microphone requests are captured in the background and never open the phone’s screen, though Android still shows its own camera and microphone indicator; start monitoring from the phone itself, because Android allows a windowless capture only for a session begun on screen. Screenshots and a new boundary need Android permission and a visible screen on the phone, where the owner approves each one; the rest run silently. “Running” means the phone started it, and “completed” means the output reached this server — the record is linked below the request. “Reviewed” appears on requests from an older phone build whose report the owner read but did not upload. A request for a tool your own rules turned off comes back declined with the rule named.</p>
      <div className="flex flex-wrap gap-2">{quickActions.map(action => <button className="secondary" key={action} disabled={!device} onClick={() => send(action)}>{names[action]}</button>)}</div>
      {requests.map(r => {
        const statusMessage = r.detail || {
          pending: "Waiting for the phone to check in (every ~10 seconds while monitoring is on).",
          delivered: "Delivered to the phone. Waiting for it to run the tool.",
          running: "The phone is working on it now.",
          completed: "The output reached the server; it is shown below.",
          reviewed: "The owner reviewed the result on the phone and did not upload it.",
          failed: "The phone could not complete this request.",
          declined: "Declined on the phone.",
          expired: "Request expired before the phone responded (10-minute limit).",
        }[r.status] || "Unknown status.";
        const args = values(r.args);
        return <article className="rounded-lg border border-slate-700 p-3" key={r.request_id}>
          <div className="flex flex-wrap items-start justify-between gap-2">
            <p>{names[r.action] || r.action}{args.name ? ` — ${args.name}` : ""} · <strong className={statusColor(r.status)}>{r.status}</strong></p>
            {["failed", "declined", "expired"].includes(r.status) && <button className="secondary text-xs" onClick={() => send(r.action, Object.keys(args).length ? args : undefined)}>Retry</button>}
          </div>
          {args.name && <p className="text-xs text-slate-500">Boundary at {args.latitude}, {args.longitude} · {args.radius_meters} m radius</p>}
          <p className="mt-1 text-sm text-slate-400">{statusMessage}</p>
          <p className="text-xs text-slate-500">Queued {stamp(r.created_at)} · updated {stamp(r.updated_at)}</p>
          {["completed", "reviewed"].includes(r.status) && r.result_ref
            && <div className="mt-3"><RequestOutput output={r.result_ref} files={files} events={incoming} token={token} remove={remove} /></div>}
        </article>;
      })}
    </div>}
    {tab === "Phone rules" && <div className={panel}>
      <h3 className="font-semibold">Rules this phone obeys</h3>
      <p className="text-sm text-slate-400">Rules only reduce what the phone does for you. A tool you clear stays off even when its owner already approved the request, and the health cadence is the one timing you may change. Nothing here can grant an Android permission, start monitoring, or remove the owner's Stop control, and the phone shows the active rules on its own screens. Re-enrolling the phone clears them.</p>
      <p className="text-sm">{phoneRules ? <>The phone last reported: <strong>{reportedRules(phoneRules)}</strong></> : "No status report from this phone yet, so its current rules are unknown. Use “Phone status” in the Requests panel, then reopen this tab."}</p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-52 text-sm">Health sample every<input className="field ml-2 w-20" type="number" min="1" max="1440" value={rules.interval} onChange={e => setRules({ ...rules, interval: e.target.value })} />minutes</label>
        <button className="secondary" disabled={!phoneRules} onClick={() => setRules(fromReported(phoneRules))}>Start from what the phone reports</button>
      </div>
      <div className="grid gap-2 md:grid-cols-3">
        {ruleTools.map(name => <label key={name} className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={rules.tools.includes(name)} onChange={e => setRules({ ...rules, tools: e.target.checked ? [...rules.tools, name] : rules.tools.filter(tool => tool !== name) })} />
          {toolNames[name]}
        </label>)}
      </div>
      <button className="primary" disabled={!device} onClick={sendRules}>Send these rules to the phone</button>
      <p className="text-sm text-slate-400">{rules.tools.length === ruleTools.length ? "Every tool stays available, so this request only sets the sample cadence." : rules.tools.length ? `The phone will decline any request for: ${ruleTools.filter(name => !rules.tools.includes(name)).map(name => toolNames[name]).join(", ")}.` : "The phone will decline every dashboard tool request until you send new rules. Phone status still works."} While monitoring is on, the phone checks for new rules about every 10 seconds and answers with what it applied.</p>
    </div>}
    {tab === "Nearby scans" && <div className={panel}><h3 className="font-semibold">Nearby Wi-Fi and Bluetooth</h3><p className="text-sm text-slate-400">Run a scan from the phone while Wi-Fi, Bluetooth and Location are enabled. Only discoverable Bluetooth devices are returned.</p>{scans.length ? scans.map(e => <details open key={e.event_id}><summary>{stamp(e.created_at)}</summary><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap text-sm">{JSON.stringify(e.payload, null, 2)}</pre></details>) : <p>No scans uploaded yet.</p>}</div>}
    {tab === "Logs" && <div className={panel}><h3 className="font-semibold">Received event log</h3><p className="text-sm text-slate-400">Most recent 100 received events for this phone.</p><div className="flex flex-wrap gap-3"><label className="text-sm">Event type<select className="field" value={logType} onChange={e => setLogType(e.target.value)}><option value="all">All types</option>{[...new Set(incoming.map(e => typeof e.payload?.type === "string" ? e.payload.type : e.event_type))].map(t => <option key={t} value={t}>{t}</option>)}</select></label><label className="flex-1 text-sm">Search logs<input className="field" value={logSearch} onChange={e => setLogSearch(e.target.value)} placeholder="Search received details" /></label></div>{incoming.filter(e => (logType === "all" || (typeof e.payload?.type === "string" ? e.payload.type : e.event_type) === logType) && JSON.stringify(e.payload).toLowerCase().includes(logSearch.toLowerCase())).map(e => <details key={e.event_id} className="border-b border-slate-700 pb-3"><summary>{stamp(e.created_at)} · {typeof e.payload?.type === "string" ? e.payload.type : e.event_type}</summary><pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(e.payload, null, 2)}</pre></details>)}</div>}
    {tab === "Export" && <div className={panel}>
      <h3 className="font-semibold">Download a copy</h3>
      <p className="text-sm text-slate-400">Each export holds the most recent 1000 stored rows, newest first. It covers records and metadata only: captured photos, audio and documents stay behind the download button in the Files panel, and enrollment secrets are never included. A cell that starts like a spreadsheet formula is marked so a workbook cannot run it.</p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-44 text-sm">What to export<select className="field" value={exportKind} onChange={e => setExportKind(e.target.value)}>
          <option value="events">Received events</option>
          <option value="requests">Tool requests and their outcomes</option>
          <option value="files">Uploaded file records</option>
          <option value="devices">Enrolled phones</option>
        </select></label>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={exportAll} onChange={e => setExportAll(e.target.checked)} /> Every phone</label>
        <button className="secondary" onClick={() => download(exportKind, "csv")}>Download CSV</button>
        <button className="secondary" onClick={() => download(exportKind, "json")}>Download JSON</button>
      </div>
      <p className="text-sm text-slate-400">{exportAll ? "Includes every enrolled phone." : `Limited to ${devices.find(d => d.device_id === device)?.name || device || "no phone"}.`}</p>
    </div>}
  </section>;
}
