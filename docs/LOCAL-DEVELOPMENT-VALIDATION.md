# Local development update verification

Source-only workflow update for app 0.4.0, checked 2026-10-05.

- Android `assembleDev` and normal `assembleDebug`: passed. Dev package/name are separate; the dev API is loopback. Normal debug and release BuildConfig values remain the Render URL/original package. Release manifest has no development HTTP configuration.
- Android unit checks: 21 passed in each of dev and normal debug variants; zero failures, errors or skipped tests.
- Dev Android lint: zero errors; existing SDK/style warnings remain nonblocking.
- Backend: all 28 existing integration/TLS checks passed. Production server/store/API implementations and dependency locks were not changed.
- Dashboard: production build passed; local development HTTP checks confirmed the local endpoint overrides an inherited production URL and the Vite HMR client is served.
- End-to-end local launcher checks: generated credentials work; setup is idempotent; inherited production database/credential configuration is overridden; the separate SQLite database is used; saving a loaded backend file restarts it; stopping the launcher stops both listeners; normal .env is unchanged.
- JavaScript/shell syntax, Windows CRLF format, CI YAML triggers/permissions, and Git ignore behavior were checked. Windows launchers were reviewed but could not be executed on Windows here. The actual launcher/server smoke check ran on Linux with Node 24.19.0.
- All 30 existing main Kotlin files/function names remain; original archives and variants are byte-identical. No permission/cancellation/visible monitoring/review controls were removed.

No physical Android phone is connected here. USB forwarding, real-device installation, Android Studio Apply Changes, sensor behavior, and OEM UI require laptop/phone verification. The GitHub workflow was prepared, not run in the user's account. No Neon branch was created, no Render account setting changed, and the existing delivered APK/private install site was not replaced.
