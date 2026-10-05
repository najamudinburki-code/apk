# System Health backend

Requires Node.js 24+. Inside this folder run `npm ci`, `npm run setup`, and `npm start`. Setup creates random dashboard/JWT credentials in a private .env if one does not already exist, and prints the login details. Do not share .env. Defaults: server port 3000, dashboard origin http://localhost:5173, SQLite data under data/. Run `npm test` for integration tests.

| Endpoint | Authentication | Purpose |
| --- | --- | --- |
| POST /api/login | Dashboard username/password JSON | One-hour Bearer JWT |
| GET /api/devices | Bearer JWT | Roster, latest payload, last seen, upload-session state |
| POST /api/devices | Bearer JWT | Enroll device_id/name; return a token once |
| GET /api/events?device_id=...&limit=100 | Bearer JWT | Saved history; optional device filter, limit 1–200 |

Socket auth for a dashboard is {role: dashboard, token: JWT}. Device auth is {role: device, device_id: ID, token: enrollment token}. Devices emit data:receive with a JSON object and acknowledgement callback. After database persistence, the server acknowledges {ok: true, event_id} and broadcasts data:received to authenticated dashboards. Device identity comes from authentication. Only enrollment-token hashes are stored.

For direct HTTPS set both TLS_CERT_PATH and TLS_KEY_PATH to readable PEM files, or use an HTTPS reverse proxy. The phone must trust the certificate. Set DASHBOARD_ORIGIN and the dashboard's VITE_SERVER_URL for deployment.

The existing command dispatch prototype does not implement Android command execution/response. Production retention, backups, credential revocation/reset, and deduplication are also unfinished. See ../docs/CONNECTOR-REVIEW.md.

For Render hosting, follow [RENDER-SETUP.md](../RENDER-SETUP.md). `npm run start:render` reads Render environment variables directly. Set DATABASE_URL for PostgreSQL; omit it to retain local SQLite. PostgreSQL TLS verifies certificates. The cloud schema is applied before listening. `/health` is a public process-readiness endpoint and does not query the cloud database per request. Local integration tests use SQLite; TEST_DATABASE_URL selects a fresh, disposable localhost PostgreSQL database with TLS disabled only for testing. Do not point tests at a real data database.
