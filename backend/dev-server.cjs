"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { parseEnv } = require("node:util");
const filename = path.join(__dirname, ".env.dev");
if (!fs.existsSync(filename)) {
  console.error("Run npm run setup:dev first to create your local test settings.");
  process.exit(1);
}
const settings = parseEnv(fs.readFileSync(filename, "utf8"));

// The development entry point never inherits a Render/production connection.
// Explicit values from .env.dev are the sole source of database credentials.
for (const key of [
  "JWT_SECRET", "DASHBOARD_USERNAME", "DASHBOARD_PASSWORD", "DASHBOARD_ORIGIN",
  "DATABASE_URL", "DATABASE_PATH", "PG_SSL_MODE", "RENDER", "HOST", "PORT",
  "TLS_CERT_PATH", "TLS_KEY_PATH", "TRUST_PROXY_HOPS", "AUTO_ENROLLMENT_KEY_HASH",
]) delete process.env[key];
for (const key of [
  "JWT_SECRET", "DASHBOARD_USERNAME", "DASHBOARD_PASSWORD", "DATABASE_URL",
  "PG_SSL_MODE", "AUTO_ENROLLMENT_KEY_HASH",
]) if (settings[key] !== undefined) process.env[key] = settings[key];

process.env.NODE_ENV = "development";
process.env.PORT = "3000";
process.env.HOST = "127.0.0.1";
process.env.DASHBOARD_ORIGIN = "http://localhost:5173";
process.env.DATABASE_PATH = path.join(__dirname, "dev-data", "devices.sqlite");
process.env.TRUST_PROXY_HOPS = "0";
console.log(process.env.DATABASE_URL
  ? "Development backend: using the PostgreSQL connection explicitly set in .env.dev."
  : "Development backend: isolated local SQLite database under backend/dev-data/.");
require("./server.js");
