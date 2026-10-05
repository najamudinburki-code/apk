import { useEffect, useRef, useState } from "react";
import { socket } from "../socket";

export default function ControlPanel({ devices = [] }) {
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(null); // 'screenshot' | 'status' | null
  const [responses, setResponses] = useState([]);
  const seq = useRef(0);

  useEffect(() => {
    if (!target && devices.length) setTarget(devices[0].id);
  }, [devices, target]);

  useEffect(() => {
    function onAck(payload) {
      // payload: { command, deviceId, ok, detail, timestamp }
      setBusy(null);
      setResponses((prev) =>
        [
          {
            _key: ++seq.current,
            command: payload?.command,
            deviceId: payload?.deviceId,
            ok: payload?.ok !== false,
            detail: payload?.detail || "",
            timestamp: payload?.timestamp || new Date().toISOString(),
          },
          ...prev,
        ].slice(0, 50)
      );
    }
    socket.on("command:ack", onAck);
    return () => socket.off("command:ack", onAck);
  }, []);

  function sendCommand(command) {
    if (!target) return;
    setBusy(command);
    // Server is expected to relay this to the enrolled device agent,
    // which prompts/surfaces the action per its consent policy.
    socket.emit("command:send", {
      command, // 'request_screenshot' | 'request_status'
      deviceId: target,
      requestedAt: new Date().toISOString(),
    });
    // Safety timeout so the button doesn't hang if no ack comes back.
    setTimeout(() => setBusy((b) => (b === command ? null : b)), 15000);
  }

  const selected = devices.find((d) => d.id === target);

  return (
    <div className="mx-auto max-w-3xl px-6 py-6">
      <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-5">
        <label className="mb-1 block text-xs font-medium text-slate-400">
          Target device
        </label>
        <select
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 outline-none focus:border-indigo-400"
        >
          {devices.length === 0 && <option value="">No enrolled devices</option>}
          {devices.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name || d.id} {d.online ? "• online" : "• offline"}
            </option>
          ))}
        </select>

        <div className="mt-5 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <button
            disabled={!target || busy === "request_screenshot"}
            onClick={() => sendCommand("request_screenshot")}
            className="flex items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-3 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy === "request_screenshot" ? "Requesting…" : "Request Screenshot"}
          </button>
          <button
            disabled={!target || busy === "request_status"}
            onClick={() => sendCommand("request_status")}
            className="flex items-center justify-center gap-2 rounded-lg bg-slate-700 px-4 py-3 text-sm font-medium text-white transition hover:bg-slate-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy === "request_status" ? "Requesting…" : "Request System Status"}
          </button>
        </div>

        {selected && (
          <p className="mt-3 text-xs text-slate-500">
            Commands are delivered to the device’s enrolled management agent,
            which handles them per the device’s configured consent policy.
          </p>
        )}
      </div>

      {/* Command responses */}
      <div className="mt-6">
        <h2 className="mb-2 text-sm font-semibold text-slate-300">
          Recent command responses
        </h2>
        <div className="rounded-xl border border-slate-800 bg-slate-900/40">
          {responses.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-slate-500">
              No responses yet.
            </p>
          ) : (
            <ul className="divide-y divide-slate-800/60 text-sm">
              {responses.map((r) => (
                <li key={r._key} className="flex items-center gap-3 px-4 py-2.5">
                  <span
                    className={`h-2 w-2 shrink-0 rounded-full ${
                      r.ok ? "bg-emerald-400" : "bg-rose-500"
                    }`}
                  />
                  <span className="w-40 shrink-0 truncate text-slate-400">
                    {r.command} → {r.deviceId}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-slate-300">
                    {r.detail || (r.ok ? "Acknowledged" : "Failed")}
                  </span>
                  <span className="shrink-0 text-xs text-slate-500">
                    {new Date(r.timestamp).toLocaleTimeString()}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
