import { useEffect, useRef, useState } from "react";
import { socket } from "../socket";

const MAX_ROWS = 500;

const LEVEL_STYLES = {
  info: "bg-sky-500/10 text-sky-300 ring-sky-400/30",
  success: "bg-emerald-500/10 text-emerald-300 ring-emerald-400/30",
  warn: "bg-amber-500/10 text-amber-300 ring-amber-400/30",
  error: "bg-rose-500/10 text-rose-300 ring-rose-400/30",
};

export default function SystemLogs() {
  const [events, setEvents] = useState([]);
  const [filter, setFilter] = useState("all");
  const idRef = useRef(0);

  useEffect(() => {
    function onLog(payload) {
      // payload: { level, source, message, timestamp }
      const entry = {
        _key: ++idRef.current,
        level: payload?.level || "info",
        source: payload?.source || "system",
        message: payload?.message || String(payload),
        timestamp: payload?.timestamp || new Date().toISOString(),
      };
      setEvents((prev) => [entry, ...prev].slice(0, MAX_ROWS));
    }

    socket.on("system:log", onLog);
    socket.emit("logs:subscribe");

    return () => {
      socket.off("system:log", onLog);
      socket.emit("logs:unsubscribe");
    };
  }, []);

  const shown =
    filter === "all" ? events : events.filter((e) => e.level === filter);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b border-slate-800 px-6 py-3">
        {["all", "info", "success", "warn", "error"].map((lvl) => (
          <button
            key={lvl}
            onClick={() => setFilter(lvl)}
            className={`rounded-md px-3 py-1 text-xs capitalize transition ${
              filter === lvl
                ? "bg-slate-700 text-white"
                : "text-slate-400 hover:bg-slate-800"
            }`}
          >
            {lvl}
          </button>
        ))}
        <span className="ml-auto text-xs text-slate-500">
          {shown.length} event{shown.length === 1 ? "" : "s"}
        </span>
      </div>

      <div className="min-h-0 flex-1 overflow-auto font-mono text-[13px]">
        {shown.length === 0 && (
          <p className="px-6 py-8 text-center text-slate-500">
            No events yet.
          </p>
        )}
        <ul className="divide-y divide-slate-800/60">
          {shown.map((e) => (
            <li
              key={e._key}
              className="flex items-start gap-3 px-6 py-2.5 hover:bg-slate-900/50"
            >
              <span className="w-20 shrink-0 text-slate-500">
                {new Date(e.timestamp).toLocaleTimeString()}
              </span>
              <span
                className={`w-16 shrink-0 rounded px-1.5 py-0.5 text-center text-[11px] font-medium uppercase ring-1 ring-inset ${
                  LEVEL_STYLES[e.level] || LEVEL_STYLES.info
                }`}
              >
                {e.level}
              </span>
              <span className="w-32 shrink-0 truncate text-slate-400">
                {e.source}
              </span>
              <span className="min-w-0 flex-1 break-words text-slate-200">
                {e.message}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
