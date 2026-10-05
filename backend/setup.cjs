"use strict";
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const destination = path.join(__dirname, ".env");
if (fs.existsSync(destination)) {
  console.log("Existing .env kept. Edit it if you need different settings.");
  process.exit(0);
}
const password = crypto.randomBytes(24).toString("base64url");
const secret = crypto.randomBytes(48).toString("base64url");
fs.writeFileSync(destination, [
  `JWT_SECRET=${secret}`, "DASHBOARD_USERNAME=admin",
  `DASHBOARD_PASSWORD=${password}`, "DASHBOARD_ORIGIN=http://localhost:5173",
  "PORT=3000", ""
].join("\n"), { flag: "wx", mode: 0o600 });
console.log("Backend settings created. Dashboard username: admin");
console.log(`Dashboard password: ${password}`);
console.log("Keep this password private. Run npm start next.");
