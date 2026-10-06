# System Health backend

For isolated local tests: `npm ci`, `npm run setup:dev`, then `npm run dev`. This loads `.env.dev`, clears inherited production connection settings and restarts on loaded-code changes. SQLite lives in `dev-data/`; an explicit Neon dev branch URL is optional. See `../LOCAL-DEVELOPMENT.md`.

Requires Node.js 24+. Inside this folder run `npm ci`, `npm run setup`, and `npm start`. Setup creates random dashboard/JWT credentials in a private .env if one does not already exist, and prints the login details. Do not share .env. Defaults: server port 3000, dashboard origin http://localhost:5173, SQLite data under data/. Run `npm test` for integration tests.

| Endpoint | Authentication | Purpose |
| --- | --- | --- |
| POST /api/login | Dashboard username/password JSON | One-hour Bearer JWT |
| GET /api/devices | Bearer JWT | Roster, latest payload, last seen, enabled flag, upload-session state |
| POST /api/devices | Bearer JWT | Enroll device_id/name; return a token once |
| POST /api/devices/:id/token | Bearer JWT | Replace a device token; the previous one stops working immediately |
| POST /api/devices/:id/enabled | Bearer JWT `{enabled: boolean}` | Disable or re-enable a device and drop its live session |
| GET /api/events?device_id=...&limit=100 | Bearer JWT | Saved history; optional device filter, limit 1–200 |

Socket auth for a dashboard is {role: dashboard, token: JWT}. Device auth is {role: device, device_id: ID, token: enrollment token}. Devices emit data:receive with a JSON object and acknowledgement callback. After database persistence, the server acknowledges {ok: true, event_id} and broadcasts data:received to authenticated dashboards. Device identity comes from authentication. Only enrollment-token hashes are stored.

For direct HTTPS set both TLS_CERT_PATH and TLS_KEY_PATH to readable PEM files, or use an HTTPS reverse proxy. The phone must trust the certificate. Set DASHBOARD_ORIGIN and the dashboard's VITE_SERVER_URL for deployment.

Dashboard-to-phone work uses HTTP request polling, not Socket.IO: `POST /api/requests` queues an action, the phone fetches `GET /api/device/requests` about every ten seconds while monitoring is on, and it reports `POST /api/device/requests/:id/result`. A phone poll refreshes that device's `last_seen` and moves queued actions to `delivered`. The old `command:send` socket event was removed because the Android app never listened for it.

`RETENTION_DAYS` (integer 1–3650, default 30) bounds stored events, uploaded files, idempotency receipts, finished tool requests and abandoned enrollment requests; a sweep runs once at startup and then every six hours. Captured media therefore ages out with the record that describes it, so keep anything you need by downloading it from the dashboard first. Repeated uploads are deduplicated by their event id. A phone is cut off with `POST /api/devices/:id/enabled` (`{"enabled":false}`, reversible) or its credential is destroyed with `POST /api/devices/:id/token`, which returns a replacement token exactly once and also drops that phone's live connection. Both management routes are rate limited. Backups of the database itself remain your host's responsibility.

For Render hosting, follow [RENDER-SETUP.md](../RENDER-SETUP.md). `npm run start:render` reads Render environment variables directly. Set DATABASE_URL for PostgreSQL; omit it to retain local SQLite. PostgreSQL TLS verifies certificates. The cloud schema is applied before listening. `/health` is a public process-readiness endpoint and does not query the cloud database per request. Local integration tests use SQLite; TEST_DATABASE_URL selects a fresh, disposable localhost PostgreSQL database with TLS disabled only for testing. Do not point tests at a real data database.
