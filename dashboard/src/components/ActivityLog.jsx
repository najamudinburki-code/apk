import { useEffect, useState } from "react";

const PAGE = 50;

/** History straight from the server: the phone and type filters run in the query, and paging walks
 *  backwards by event id so a busy phone can never make a row appear twice or be skipped. */
export default function ActivityLog({ api, token, devices, liveCount, onUnauthorized }) {
  const [rows, setRows] = useState([]);
  const [device, setDevice] = useState("");
  const [type, setType] = useState("");
  const [needle, setNeedle] = useState("");
  const [exhausted, setExhausted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [seenAt, setSeenAt] = useState(0);

  async function page(after) {
    const query = new URLSearchParams({ limit: String(PAGE),
      ...(device ? { device_id: device } : {}), ...(type ? { type } : {}), ...(after ? { after: String(after) } : {}) });
    return api(`/api/events?${query}`, token);
  }

  async function load(replace) {
    setBusy(true); setError("");
    try {
      const result = await page(replace ? "" : (rows.length ? rows[rows.length - 1].event_id : ""));
      const incoming = result.events;
      setRows(current => replace ? incoming : [...current, ...incoming]);
      setExhausted(incoming.length < PAGE);
      if (replace) setSeenAt(liveCount);
    } catch (err) {
      if (err.status === 401) onUnauthorized();
      else setError(err.message || "Could not load the activity log.");
    } finally { setBusy(false); }
  }

  useEffect(() => { load(true); }, [device, type, token]);

  // The chosen type stays listed even when the current page no longer contains it, so the owner
  // can widen the filter again without guessing what was there before.
  const types = [...new Set(rows.map(row => row.payload?.type)
    .filter(value => typeof value === "string").concat(type ? [type] : []))].sort();
  const text = needle.trim().toLowerCase();
  const shown = rows.filter(row => !text
    || JSON.stringify(row.payload).toLowerCase().includes(text)
    || String(row.device_id).toLowerCase().includes(text));
  const arrived = Math.max(0, liveCount - seenAt);

  return <section className="overflow-hidden rounded-xl border border-slate-800 bg-slate-900">
    <div className="flex flex-wrap items-end justify-between gap-4 p-5">
      <div>
        <h2 className="text-lg font-semibold">Activity log</h2>
        <p className="mt-1 text-sm text-slate-400">Everything the phones have delivered, oldest page by page. The server keeps history for a limited time, so anything here may already be gone next month.</p>
      </div>
      <div className="flex flex-wrap items-end gap-3 text-sm">
        <label className="block">Phone<select className="field" value={device} onChange={event => setDevice(event.target.value)}>
          <option value="">All phones</option>
          {devices.map(row => <option key={row.device_id} value={row.device_id}>{row.name}</option>)}
        </select></label>
        <label className="block">Type<select className="field" value={type} onChange={event => setType(event.target.value)}>
          <option value="">All types</option>
          {types.map(value => <option key={value} value={value}>{value}</option>)}
        </select></label>
        <label className="block">Search loaded rows<input className="field" value={needle} onChange={event => setNeedle(event.target.value)} placeholder="text, package, battery" /></label>
        <button className="secondary" disabled={busy} onClick={() => load(true)}>{busy ? "Loading…" : "Refresh"}</button>
      </div>
    </div>
    {arrived > 0 && <p className="border-t border-slate-800 bg-slate-800/60 px-5 py-2 text-sm text-indigo-200">
      {arrived} new event{arrived === 1 ? "" : "s"} since this page loaded. <button className="underline" disabled={busy} onClick={() => load(true)}>Show the newest page</button>
    </p>}
    {error && <p role="alert" className="border-t border-slate-800 px-5 py-2 text-sm text-rose-300">{error}</p>}
    {!rows.length && !busy && <p className="border-t border-slate-800 p-5 text-sm text-slate-400">No stored events match these filters yet. Open the phone app, allow notifications, and wait for its automatic connection.</p>}
    {!!rows.length && <div className="overflow-x-auto border-t border-slate-800">
      <table className="w-full text-left text-sm">
        <thead className="bg-slate-800 text-slate-300"><tr><th className="p-4">Received</th><th className="p-4">Phone</th><th className="p-4">Type</th><th className="p-4">Battery</th><th className="p-4">Details</th></tr></thead>
        <tbody>{shown.map(row => <tr key={row.event_id} className="border-t border-slate-800">
          <td className="p-4 whitespace-nowrap">{new Date(row.created_at).toLocaleString()}</td>
          <td className="p-4">{row.device_id}</td>
          <td className="p-4">{typeof row.payload?.type === "string" ? row.payload.type : row.event_type}</td>
          <td className="p-4">{Number.isFinite(row.payload?.battery_percent) ? `${row.payload.battery_percent}%` : "—"}</td>
          <td className="p-4"><details><summary className="cursor-pointer">View payload</summary><pre className="mt-2 max-h-72 max-w-md overflow-auto whitespace-pre-wrap break-all text-xs">{JSON.stringify(row.payload, null, 2)}</pre></details></td>
        </tr>)}</tbody>
      </table>
      <div className="flex items-center gap-4 p-5 text-sm text-slate-400">
        <button className="secondary" disabled={busy || exhausted} onClick={() => load(false)}>{busy ? "Loading…" : exhausted ? "Nothing older stored" : "Load 50 older"}</button>
        <p>Showing {shown.length} of {rows.length} loaded event{rows.length === 1 ? "" : "s"}. Search only filters the rows already loaded here.</p>
      </div>
    </div>}
  </section>;
}
