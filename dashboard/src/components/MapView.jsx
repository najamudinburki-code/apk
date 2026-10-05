import { useEffect, useMemo, useRef, useState } from "react";
import { MapContainer, TileLayer, Marker, Popup, useMap } from "react-leaflet";
import L from "leaflet";
import { socket } from "../socket";

// Fix default marker asset paths under bundlers (Vite).
import markerIcon2x from "leaflet/dist/images/marker-icon-2x.png";
import markerIcon from "leaflet/dist/images/marker-icon.png";
import markerShadow from "leaflet/dist/images/marker-shadow.png";

L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

// Keeps the viewport sensibly framed as devices stream in.
function FitBounds({ positions }) {
  const map = useMap();
  useEffect(() => {
    if (positions.length === 0) return;
    const bounds = L.latLngBounds(positions.map((p) => [p.lat, p.lng]));
    map.fitBounds(bounds.pad(0.25), { animate: true });
  }, [positions, map]);
  return null;
}

export default function MapView() {
  // Map of deviceId -> { id, name, lat, lng, updatedAt }
  const [locations, setLocations] = useState({});
  const lastSeenRef = useRef({});

  useEffect(() => {
    function onLocation(payload) {
      // payload: { id, name, lat, lng, accuracy?, timestamp }
      if (!payload || typeof payload.lat !== "number") return;
      lastSeenRef.current[payload.id] = Date.now();
      setLocations((prev) => ({
        ...prev,
        [payload.id]: {
          id: payload.id,
          name: payload.name || payload.id,
          lat: payload.lat,
          lng: payload.lng,
          accuracy: payload.accuracy,
          updatedAt: payload.timestamp || new Date().toISOString(),
        },
      }));
    }

    socket.on("device:location", onLocation);
    socket.emit("map:subscribe"); // request the live GPS feed

    return () => {
      socket.off("device:location", onLocation);
      socket.emit("map:unsubscribe");
    };
  }, []);

  const markers = useMemo(() => Object.values(locations), [locations]);
  const positions = markers.map((m) => ({ lat: m.lat, lng: m.lng }));

  return (
    <div className="relative h-full w-full">
      {markers.length === 0 && (
        <div className="pointer-events-none absolute inset-0 z-[500] flex items-center justify-center">
          <p className="rounded-lg bg-slate-900/80 px-4 py-2 text-sm text-slate-300 ring-1 ring-slate-700">
            Waiting for GPS data from enrolled devices…
          </p>
        </div>
      )}
      <MapContainer
        center={[20, 0]}
        zoom={2}
        scrollWheelZoom
        className="h-full w-full"
        style={{ background: "#0b1220" }}
      >
        <TileLayer
          attribution='&copy; OpenStreetMap contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        {markers.map((m) => (
          <Marker key={m.id} position={[m.lat, m.lng]}>
            <Popup>
              <div className="text-sm">
                <p className="font-semibold">{m.name}</p>
                <p className="text-slate-500">ID: {m.id}</p>
                <p className="text-slate-500">
                  {m.lat.toFixed(5)}, {m.lng.toFixed(5)}
                </p>
                {m.accuracy != null && (
                  <p className="text-slate-500">±{Math.round(m.accuracy)} m</p>
                )}
                <p className="text-slate-400">
                  Updated {new Date(m.updatedAt).toLocaleTimeString()}
                </p>
              </div>
            </Popup>
          </Marker>
        ))}
        <FitBounds positions={positions} />
      </MapContainer>
    </div>
  );
}
