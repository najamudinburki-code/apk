"use strict";
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const development = process.argv.includes("--dev");
const filename = development ? ".env.dev" : ".env";
const username = development ? "admin_dev" : "admin";
const destination = path.join(__dirname, filename);
if (fs.existsSync(destination)) {
  console.log(`Existing ${filename} kept. Edit it if you need different settings.`);
  process.exit(0);
}
const password = crypto.randomBytes(24).toString("base64url");
const secret = crypto.randomBytes(48).toString("base64url");
fs.writeFileSync(destination, [
  `JWT_SECRET=${secret}`, `DASHBOARD_USERNAME=${username}`,
  `DASHBOARD_PASSWORD=${password}`, "DASHBOARD_ORIGIN=http://localhost:5173",
  "PORT=3000",
  ...(development ? ["# Optional: only the connection string for your Neon dev branch.", "DATABASE_URL="] : []),
  ""
].join("\n"), { flag: "wx", mode: 0o600 });
console.log(`${filename} created. Dashboard username: ${username}`);
console.log(`Dashboard password: ${password}`);
console.log(`Keep this password private. Run ${development ? "npm run dev" : "npm start"} next.`);
