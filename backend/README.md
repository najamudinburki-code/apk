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
| POST /api/devices/:id/name | Bearer JWT `{name}` | Rename a phone for the roster; its device id, credential and enabled flag stay untouched |
| POST /api/sessions/revoke | Bearer JWT | End every dashboard sign-in now instead of at its scheduled expiry. The cut-off instant is stored, so it also survives a restart; phone credentials are untouched |
| GET /api/events?device_id=...&type=...&after=...&limit=100 | Bearer JWT | Saved history; optional device and event-type filters, limit 1–200, and `after` = the lowest event id already shown so a caller can page backwards without repeating or skipping a row |
| GET /api/requests?device_id=... | Bearer JWT | Newest 100 tool requests with their delivery state; reading the ledger also marks requests whose 10 minutes have run out as `expired` |
| GET /api/state?device_id=... | Bearer JWT | Latest stored event of each type for one phone, which is what the tool panels open with |
| GET /api/files?device_id=... | Bearer JWT | Newest 500 uploaded file records; bytes are never listed inline |
| GET /api/files/:id · DELETE /api/files/:id | Bearer JWT | Download one file (always as an attachment, with a sandboxed CSP so an HTML or SVG payload cannot run on the API origin) or delete its record and bytes |
| GET /api/export/:kind?device_id=...&format=csv\|json | Bearer JWT | Download the newest 1000 rows of `events`, `requests`, `files` or `devices`; captured file bytes and token hashes are never included |

Sockets are dashboard-only: authenticate with {role: dashboard, token: JWT}. A phone never opens a socket, so one authenticated HTTP transport carries every report and capture. Device calls send `Authorization: Bearer <enrollment token>` plus `X-Device-Id`; the server checks the token hash and the enabled flag before touching the payload. `POST /api/device/events` persists first, then answers `{ok: true, event_id}` and broadcasts `data:received` to authenticated dashboards. Device identity comes from authentication, never from the payload. Only enrollment-token hashes are stored.

For direct HTTPS set both TLS_CERT_PATH and TLS_KEY_PATH to readable PEM files, or use an HTTPS reverse proxy. The phone must trust the certificate. Set DASHBOARD_ORIGIN and the dashboard's VITE_SERVER_URL for deployment.

Dashboard-to-phone work also uses HTTP polling: `POST /api/requests` queues an action, the phone fetches `GET /api/device/requests` about every ten seconds while monitoring is on, and it reports `POST /api/device/requests/:id/result`. The same phone loop sends its queued reports and runs any report the owner scheduled on the phone, so nothing here needs a second service or a new Android permission; stopping monitoring stops all of it. A phone poll refreshes that device's `last_seen` and moves queued actions to `delivered`. Because checking in is the only liveness signal, `GET /api/devices` reports `online` from `last_seen` being under 45 seconds old rather than from a live socket.

Two actions carry values, and both are validated server-side before anything is stored; every other action is refused if it sends `args`, so nothing else can smuggle parameters to the phone.

`{"action":"request_geofence","args":{"name":"Home","latitude":-33.86,"longitude":151.21,"radius_meters":250}}` needs a name, both coordinates and a radius between 100 and 10000 metres. The values are stored in `device_requests.args`, handed back as an object on the device poll, and adding a boundary still needs the owner's approval on the phone screen.

`{"action":"request_settings","args":{"health_interval_minutes":2,"tools_allowed":["photo","audio"]}}` sets the rules a phone then obeys. `health_interval_minutes` is a whole number from 1 to 1440 and `tools_allowed` is a subset of `audio, geofence, location, photo, screenshot, scan`; at least one of the two is required, an unknown tool name is rejected, and duplicates are collapsed. Rules can only take behaviour away: they never grant an Android permission, never turn monitoring on, and `request_settings` and `request_status` are outside the governable list so a narrowed phone can always be widened again by the same dashboard. The phone stores the rules, applies them to later remote requests, reports a rule-blocked request back as `declined` with the rule named, and echoes the live rule set in its next `device_status`, so the dashboard can show what the phone actually accepted. Configuring a phone for a different server clears the rules it was given.

Up to 20 outstanding requests per phone are accepted; more returns 429. `GET /api/export/:kind` is rate limited to 12 downloads a minute per client and the management routes are limited too.

`RETENTION_DAYS` (integer 1–3650, default 30) bounds stored events, uploaded files, idempotency receipts, finished tool requests and abandoned enrollment requests; a sweep runs once at startup and then every six hours. Captured media therefore ages out with the record that describes it, so keep anything you need by downloading it from the dashboard first. Repeated uploads are deduplicated by their event id. A phone is cut off with `POST /api/devices/:id/enabled` (`{"enabled":false}`, reversible) or its credential is destroyed with `POST /api/devices/:id/token`, which returns a replacement token exactly once and also drops that phone's live connection. Both management routes are rate limited. Backups of the database itself remain your host's responsibility.

For Render hosting, follow [RENDER-SETUP.md](../RENDER-SETUP.md). `npm run start:render` reads Render environment variables directly. Set DATABASE_URL for PostgreSQL; omit it to retain local SQLite. PostgreSQL TLS verifies certificates. The cloud schema is applied before listening. `/health` is a public process-readiness endpoint and does not query the cloud database per request. Set `APP_RELEASE_VERSION` (and optionally `APP_RELEASE_URL`) to the newest APK you actually host; `/health` then answers `latest_app_version` and `latest_app_url`, the phone compares them with its own build during the connection check and only mentions a strictly newer version, so leaving them unset simply means phones never hear about an update. Do not put a signing key, dashboard password or database URL in this file. Local integration tests use SQLite; TEST_DATABASE_URL selects a fresh, disposable localhost PostgreSQL database with TLS disabled only for testing. Do not point tests at a real data database.
