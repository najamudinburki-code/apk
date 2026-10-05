import { useEffect, useState } from "react";
import { MapContainer, TileLayer, CircleMarker, Popup } from "react-leaflet";
import "leaflet/dist/leaflet.css";
import { SERVER_URL } from "../socket";

const names = { request_status: "Phone status", request_screenshot: "Screenshot", request_photo: "Photo", request_audio: "15-second audio", request_location: "Start location", request_scan: "Nearby scan", request_audit: "Security audit", request_files: "Choose a file", request_backup: "Settings backup" };
const stamp = value => value ? new Date(value).toLocaleString() : "—";
const panel = "space-y-4 rounded-xl border border-slate-800 bg-slate-900 p-5";

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

export default function FeaturePanels({ token, devices, events, api }) {
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
  useEffect(() => { if (!device && devices.length) setDevice(devices[0].device_id); }, [device, devices]);
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
  }, [device, token, api, refresh]);
  const incoming = [...new Map([...history, ...events.filter(e => e.device_id === device)].map(e => [String(e.event_id), e])).values()].sort((a, b) => Number(b.event_id) - Number(a.event_id)).slice(0, 100);
  const text = incoming.filter(e => ["screen_fields", "accessibility", "screen_text", "notification"].includes(e.payload?.type));
  const locations = incoming.filter(e => e.payload?.type === "location" && Number.isFinite(e.payload.latitude) && Number.isFinite(e.payload.longitude) && Math.abs(e.payload.latitude) <= 90 && Math.abs(e.payload.longitude) <= 180);
  const savedLocation = latestEvents.find(e => e.payload?.type === "location" && Number.isFinite(e.payload.latitude) && Number.isFinite(e.payload.longitude) && Math.abs(e.payload.latitude) <= 90 && Math.abs(e.payload.longitude) <= 180);
  const latest = locations[0]?.payload || savedLocation?.payload;
  const scans = [...new Map([...latestEvents, ...incoming].map(e => [String(e.event_id), e])).values()].filter(e => e.payload?.type === "environment_scan");
  const fences = incoming.filter(e => e.payload?.type === "geofence");
  async function send(action) {
    setError(""); setNotice("");
    try {
      const result = await api("/api/requests", token, { method: "POST", body: JSON.stringify({ device_id: device, action }) });
      setNotice(result.detail); setRefresh(n => n + 1);
    } catch (e) { setError(e.message); }
  }
  async function remove(id) {
    try { await api(`/api/files/${id}`, token, { method: "DELETE" }); setRefresh(n => n + 1); }
    catch (e) { setError(e.message); }
  }
  const tabs = ["Text and notifications", "Location", "Files", "Requests", "Nearby scans", "Audit and backups", "Logs"];
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
      <h4 className="font-medium">Geofence events</h4>{fences.length ? fences.map(e => <pre key={e.event_id} className="overflow-auto text-sm">{JSON.stringify(e.payload, null, 2)}</pre>) : <p className="text-sm text-slate-400">No boundary events received yet.</p>}
    </div>}
    {tab === "Files" && <div className={panel}>
      <h3 className="font-semibold">Uploaded files</h3><p className="text-sm text-slate-400">Photos, screenshots, microphone clips and explicitly chosen documents appear here after upload. Limits: 4 MiB/file, 100 MiB and 500 files/phone. Files persist in your PostgreSQL database.</p>
      <button className="secondary" onClick={() => setRefresh(n => n + 1)}>Refresh files</button>
      <div className="grid gap-4 md:grid-cols-2">{files.map(f => <SharedFile key={f.file_id} file={f} token={token} remove={remove} />)}</div>{!files.length && <p>No uploaded files yet. Capture or select a file on the phone.</p>}
    </div>}
    {tab === "Requests" && <div className={panel}>
      <h3 className="font-semibold">Ask the phone to run a tool</h3><p className="text-sm text-slate-400">Requests expire after 10 minutes. The phone checks every 30 seconds while monitoring is on. Its user reviews and approves each request. Camera, microphone and screenshots also require Android permission. “Completed” means output was queued on the phone; verify delivery in Files or the corresponding panel.</p>
      <div className="flex flex-wrap gap-2">{Object.entries(names).map(([action, name]) => <button className="secondary" key={action} disabled={!device} onClick={() => send(action)}>{name}</button>)}</div>
      {requests.map(r => <article className="rounded-lg border border-slate-700 p-3" key={r.request_id}><p>{names[r.action] || r.action} · <strong>{r.status}</strong></p><p className="text-sm text-slate-400">{r.detail || "Awaiting phone review"} · {stamp(r.created_at)}</p></article>)}
    </div>}
    {tab === "Nearby scans" && <div className={panel}><h3 className="font-semibold">Nearby Wi-Fi and Bluetooth</h3><p className="text-sm text-slate-400">Run a scan from the phone while Wi-Fi, Bluetooth and Location are enabled. Only discoverable Bluetooth devices are returned.</p>{scans.length ? scans.map(e => <details open key={e.event_id}><summary>{stamp(e.created_at)}</summary><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap text-sm">{JSON.stringify(e.payload, null, 2)}</pre></details>) : <p>No scans uploaded yet.</p>}</div>}
    {tab === "Audit and backups" && <div className={panel}><h3 className="font-semibold">Shared reports and settings backups</h3><p className="text-sm text-slate-400">Audits inspect this app's own private data and redact detected secrets. Backups contain this app's selected app list and geofence definitions. Enrollment credentials and active consent are excluded. Download a backup and use “Restore settings backup” on the phone to restore the app selection; tap “Restore backed-up geofences” to review and register saved boundaries.</p><div className="grid gap-4 md:grid-cols-2">{files.filter(f => ["audit", "backup"].includes(f.kind)).map(f => <SharedFile key={f.file_id} file={f} token={token} remove={remove} />)}</div>{!files.some(f => ["audit", "backup"].includes(f.kind)) && <p>No reports shared yet. Run the tool on the phone, review its report, then tap Share report.</p>}</div>}
    {tab === "Logs" && <div className={panel}><h3 className="font-semibold">Received event log</h3><p className="text-sm text-slate-400">Most recent 100 received events for this phone.</p><div className="flex flex-wrap gap-3"><label className="text-sm">Event type<select className="field" value={logType} onChange={e => setLogType(e.target.value)}><option value="all">All types</option>{[...new Set(incoming.map(e => typeof e.payload?.type === "string" ? e.payload.type : e.event_type))].map(t => <option key={t} value={t}>{t}</option>)}</select></label><label className="flex-1 text-sm">Search logs<input className="field" value={logSearch} onChange={e => setLogSearch(e.target.value)} placeholder="Search received details" /></label></div>{incoming.filter(e => (logType === "all" || (typeof e.payload?.type === "string" ? e.payload.type : e.event_type) === logType) && JSON.stringify(e.payload).toLowerCase().includes(logSearch.toLowerCase())).map(e => <details key={e.event_id} className="border-b border-slate-700 pb-3"><summary>{stamp(e.created_at)} · {typeof e.payload?.type === "string" ? e.payload.type : e.event_type}</summary><pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(e.payload, null, 2)}</pre></details>)}</div>}
  </section>;
}
