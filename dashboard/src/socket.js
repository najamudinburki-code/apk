import { io } from "socket.io-client";

export const SERVER_URL = (import.meta.env.VITE_SERVER_URL || "http://localhost:3000").replace(/\/$/, "");

export function createDashboardSocket(token) {
  return io(SERVER_URL, { autoConnect: false, auth: { role: "dashboard", token } });
}
