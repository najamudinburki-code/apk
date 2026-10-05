"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");
const root = path.resolve(__dirname, "..");
if (Number(process.versions.node.split(".")[0]) < 24) {
  console.error("Install Node.js 24 or newer, then run setup again.");
  process.exit(1);
}
for (const relative of ["backend/.env.dev", "backend/node_modules", "dashboard/node_modules"]) {
  if (!fs.existsSync(path.join(root, relative))) {
    console.error("Run SETUP-DEV.cmd (Windows) or ./setup-dev.sh (Linux/macOS) first.");
    process.exit(1);
  }
}

console.log("Local dashboard: http://localhost:5173");
console.log("Sign in using admin_dev and the password in backend/.env.dev.");
console.log("Keep this window open. Ctrl+C stops both development servers.");
const children = [];
let stopping = false;
let remaining = 2;
let exitCode = 0;

function stop(code) {
  if (stopping) return;
  stopping = true;
  exitCode = code;
  for (const child of children) {
    if (!child.pid || child.exitCode !== null || child.signalCode !== null) continue;
    if (process.platform === "win32") {
      spawn("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    } else child.kill("SIGTERM");
  }
}

function launch(folder, args) {
  const child = spawn(process.execPath, args, { cwd: path.join(root, folder), stdio: "inherit" });
  children.push(child);
  child.on("error", (error) => {
    console.error(`Could not start ${folder}: ${error.message}`);
    stop(1);
  });
  child.on("close", (code) => {
    remaining -= 1;
    if (!stopping) stop(code || 1);
    if (remaining === 0) process.exit(exitCode);
  });
}

process.on("SIGINT", () => stop(0));
process.on("SIGTERM", () => stop(0));
launch("backend", ["--watch", "--watch-preserve-output", "dev-server.cjs"]);
launch("dashboard", ["dev-server.mjs"]);
