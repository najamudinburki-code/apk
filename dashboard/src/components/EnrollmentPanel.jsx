import { useEffect, useState } from "react";

export default function EnrollmentPanel({ api, token, onChanged, onUnauthorized }) {
  const [requests, setRequests] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    async function load() {
      try {
        const result = await api("/api/enrollments", token, { signal: controller.signal });
        if (active) { setRequests(result.requests); setError(""); }
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
  return <section className="rounded-xl border border-slate-800 bg-slate-900 p-6">
    <h2 className="text-lg font-semibold">New phones — approve once</h2>
    <p className="mt-1 text-sm text-slate-400">Open the Android app. It fills the server address and generates its own ID and token. Match the phone ID below with the ID shown on your phone, then approve a phone you manage. No configuration needs to be typed on the phone.</p>
    {error && <p role="alert" className="mt-3 text-rose-300">{error}</p>}
    {!requests.length && <p className="mt-4 text-sm text-slate-400">No phones waiting for approval. Keep the app open while it connects.</p>}
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
