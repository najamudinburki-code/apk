import { useEffect, useMemo, useState } from "react";

/** Same dotted-number rule the phone uses, so both sides agree on what counts as an upgrade. */
function isNewer(remote, local) {
  const parts = value => String(value || "").trim().replace(/^v/, "").split(/[.\-+]/)
    .map(part => parseInt(part, 10)).filter(Number.isFinite);
  const a = parts(remote); const b = parts(local);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const left = a[index] || 0; const right = b[index] || 0;
    if (left !== right) return left > right;
  }
  return false;
}

export default function EnrollmentPanel({ api, token, devices = [], onChanged, onUnauthorized }) {
  const [requests, setRequests] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [automatic, setAutomatic] = useState(null);
  const [release, setRelease] = useState(undefined);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    async function load() {
      try {
        const [result, health] = await Promise.all([
          api("/api/enrollments", token, { signal: controller.signal }),
          api("/health", token, { signal: controller.signal }),
        ]);
        if (active) {
          setRequests(result.requests);
          setAutomatic(health.automatic_enrollment === true);
          setRelease(health.latest_app_version
            ? { version: health.latest_app_version, url: health.latest_app_url || "" } : null);
          setError("");
        }
      } catch (err) {
        if (!active || err.name === "AbortError") return;
        if (err.status === 401) onUnauthorized();
        setError(err.status === 404 ? "Deploy the updated backend to enable automatic phone connection." : "Could not load new phone requests.");
      }
    }
    load(); const timer = setInterval(load, 10_000);
    return () => { active = false; controller.abort(); clearInterval(timer); };
  }, [api, token, onUnauthorized]);

  async function decide(id, action) {
    setBusy(id); setError("");
    try {
      await api(`/api/enrollments/${encodeURIComponent(id)}/${action}`, token, { method: "POST", body: "{}" });
      setRequests(previous => previous.filter(request => request.device_id !== id));
      onChanged();
    } catch (err) {
      if (err.status === 401) onUnauthorized();
      setError(err.message || "Could not update phone approval.");
    } finally { setBusy(""); }
  }
  const behind = useMemo(() => {
    if (!release) return [];
    return devices.filter(device => {
      const running = (device.latest_health || device.latest_payload || {}).app_version;
      return running && isNewer(release.version, running);
    });
  }, [devices, release]);

  return <section className="rounded-xl border border-slate-800 bg-slate-900 p-6">
    <h2 className="text-lg font-semibold">Automatic phone connection</h2>
    <p className="mt-1 text-sm text-slate-400">Open the current APK and allow its Android permissions. It fills the server address, generates its own ID and token, and joins automatically. No dashboard approval is required for this APK. Connected phones appear in the device list below.</p>
    {release && <p className="mt-3 text-sm text-slate-300">
      This server advertises APK release <strong>{release.version}</strong>.
      {release.url && <> <a className="text-indigo-300 underline" href={release.url} rel="noreferrer" target="_blank">Download it</a>.</>}
      {" "}{behind.length
        ? <>Behind it: {behind.map(device => device.name).join(", ")} still report an older build. A phone shows this on its own home screen the next time it checks the server.</>
        : "Every phone that has reported a version is on it."}
    </p>}
    {release === null && <p className="mt-3 text-sm text-slate-500">This server does not advertise an APK release, so phones cannot report that a newer build exists. Set APP_RELEASE_VERSION when you publish one.</p>}
    {automatic === false && <p className="mt-3 text-amber-300">Automatic enrollment is unavailable on this server. Deploy the matching backend files or check its installation setting.</p>}
    {error && <p role="alert" className="mt-3 text-rose-300">{error}</p>}
    {!requests.length && <p className="mt-4 text-sm text-slate-400">Keep the app open while it connects. Legacy pending requests, when present, remain available here.</p>}
    {!!requests.length && <h3 className="mt-4 font-medium">Legacy pending requests</h3>}
    <div className="mt-4 grid gap-3 md:grid-cols-2">
      {requests.map(request => <article key={request.device_id} className="rounded-lg border border-slate-700 p-4">
        <h3 className="font-medium">{request.name}</h3>
        <p className="mt-1 break-all font-mono text-xs text-slate-400">{request.device_id}</p>
        <div className="mt-4 flex gap-3">
          <button className="primary" disabled={!!busy} onClick={() => decide(request.device_id, "approve")}>Approve phone</button>
          <button className="secondary" disabled={!!busy} onClick={() => decide(request.device_id, "reject")}>Decline</button>
        </div>
      </article>)}
    </div>
  </section>;
}
