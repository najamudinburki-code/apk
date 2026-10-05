#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
node -e 'if (Number(process.versions.node.split(".")[0]) < 24) { console.error("Install Node.js 24 or newer."); process.exit(1); }'
npm --prefix backend ci --include=dev
npm --prefix backend run setup:dev
npm --prefix dashboard ci --include=dev
echo 'Setup complete. Run ./start-dev.sh next.'
