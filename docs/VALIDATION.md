# Validation — completed bundle 0.3.0

- Android `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`: build successful, 0 lint errors. Nonblocking SDK/style warnings remain.
- Android unit tests: 6 passed, 0 failed. Five cover automatic/selected app scope; one verifies unique IDs and independent 256-bit tokens for 100 fresh installations.
- APK signature: verified, same debug certificate as the previous delivered version.
- Package: com.example.systemhealth, versionCode 4, versionName 0.3.0, minSdk 26, targetSdk 35.
- Backend: 24 checks passed on SQLite and the same 24 on a local PostgreSQL-compatible PGlite engine via the node-postgres wire connection. The live Neon instance was not tested.
- Enrollment checks cover pending-only registration, role separation, token proof, retry idempotence, duplicate approvals, declined/disabled devices, expiry renewal and server restart persistence. Existing telemetry, media, request, quota, authentication and TLS checks also pass.
- Dashboard production build: passed.
- Browser integration: automatic phone registration approval/decline, manual enrollment, login/logout, readable screen/notification text, health, phone-reviewed requests, file upload/preview/download, maps, reports, logs, mobile layout; passed with no page errors.
- All 20 active Kotlin files from 0.2.1 and their existing function names remain; the active source set now has 22 Kotlin files. Original archives, variants and all previous dashboard tools remain.

No physical Android phone is connected. Android Keystore behavior on the user's phone, runtime permissions, sensors, OEM restrictions and automatic startup timing need phone verification. The delivered APK is a signed debug/test build. Deploy the included backend/dashboard updates before using automatic enrollment.
