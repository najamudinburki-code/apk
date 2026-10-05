import { useEffect, useRef, useState } from "react";
import { socket } from "../socket";

export default function FileManager({ devices = [] }) {
  const [target, setTarget] = useState("");
  const [path, setPath] = useState("/");
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const reqRef = useRef(0);

  useEffect(() => {
    if (!target && devices.length) setTarget(devices[0].id);
  }, [devices, target]);

  useEffect(() => {
    function onListing(payload) {
      // payload: { deviceId, path, entries:[{name,type,size,modified}] }
      if (payload?.deviceId !== target) return;
      setLoading(false);
      setPath(payload.path || path);
      setEntries(Array.isArray(payload.entries) ? payload.entries : []);
    }
    socket.on("files:listing", onListing);
    return () => socket.off("files:listing", onListing);
  }, [target, path]);

  function list(nextPath = path) {
    if (!target) return;
    setLoading(true);
    reqRef.current += 1;
    socket.emit("files:list", { deviceId: target, path: nextPath });
  }

  useEffect(() => {
    if (target) list("/");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);

  function open(entry) {
    if (entry.type === "dir") {
      const next = path.endsWith("/")
        ? path + entry.name
        : path + "/" + entry.name;
      list(next);
    }
  }

  function up() {
    if (path === "/") return;
    const parts = path.split("/").filter(Boolean);
    parts.pop();
    list("/" + parts.join("/"));
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b border-slate-800 px-6 py-3">
        <select
          value={target}
          onChange={(e) => setTarget(e.target.value)}
          className="rounded-lg border border-slate-700 bg-slate-950 px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-indigo-400"
        >
          {devices.length === 0 && <option value="">No enrolled devices</option>}
          {devices.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name || d.id}
            </option>
          ))}
        </select>

        <button
          onClick={up}
          disabled={path === "/"}
          className="rounded-md px-2.5 py-1.5 text-sm text-slate-300 hover:bg-slate-800 disabled:opacity-40"
        >
          ↑ Up
        </button>

        <code className="min-w-0 flex-1 truncate rounded-md bg-slate-900 px-3 py-1.5 text-sm text-slate-300">
          {path}
        </code>

        <button
          onClick={() => list()}
          className="rounded-md bg-slate-700 px-3 py-1.5 text-sm text-white hover:bg-slate-600"
        >
          Refresh
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? (
          <p className="px-6 py-8 text-center text-sm text-slate-500">Loading…</p>
        ) : entries.length === 0 ? (
          <p className="px-6 py-8 text-center text-sm text-slate-500">
            Empty or no listing returned.
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-slate-900 text-left text-xs uppercase text-slate-500">
              <tr>
                <th className="px-6 py-2 font-medium">Name</th>
                <th className="px-6 py-2 font-medium">Type</th>
                <th className="px-6 py-2 font-medium">Size</th>
                <th className="px-6 py-2 font-medium">Modified</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {entries.map((e) => (
                <tr
                  key={e.name}
                  onClick={() => open(e)}
                  className={`${
                    e.type === "dir" ? "cursor-pointer" : ""
                  } hover:bg-slate-900/50`}
                >
                  <td className="px-6 py-2 text-slate-200">
                    {e.type === "dir" ? "📁 " : "📄 "}
                    {e.name}
                  </td>
                  <td className="px-6 py-2 text-slate-400">{e.type}</td>
                  <td className="px-6 py-2 text-slate-400">
                    {e.type === "dir" ? "—" : formatSize(e.size)}
                  </td>
                  <td className="px-6 py-2 text-slate-400">
                    {e.modified
                      ? new Date(e.modified).toLocaleString()
                      : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function formatSize(bytes) {
  if (bytes == null) return "—";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  let n = bytes;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}
