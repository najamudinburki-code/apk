// Process environment wins over Vite .env files, keeping local tests on the laptop.
process.env.VITE_SERVER_URL = "http://localhost:3000";
const { createServer } = await import("vite");
const server = await createServer({
  mode: "development",
  server: { host: "127.0.0.1", port: 5173, strictPort: true },
});
await server.listen();
server.printUrls();
server.bindCLIShortcuts({ print: true });
