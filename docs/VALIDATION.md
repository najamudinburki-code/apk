# Validation — completed bundle 0.3.1

- Android `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`: build successful, 0 lint errors. Nonblocking SDK/style warnings remain.
- Android unit tests: 6 passed, 0 failed. Five cover automatic/selected app scope; one verifies unique IDs and independent 256-bit tokens for 100 fresh installations.
- APK signature: verified, same debug certificate as the previous delivered version.
- Package: com.example.systemhealth, versionCode 5, versionName 0.3.1, minSdk 26, targetSdk 35.
- APK invitation and bundled backend SHA-256 hash: match verified without printing the invitation.
- Backend: 28 checks passed on SQLite and the same 28 on a local PostgreSQL-compatible PGlite engine via node-postgres. The live Neon instance was not queried.
- Enrollment checks cover immediate automatic joining, concurrent retry idempotence, invalid invitation rejection, no invitation-based dashboard access, upgrading pending phones, legacy approval, declined/disabled devices, expiry renewal, persistence and disabling future joins while existing phones continue uploading. Existing telemetry, media, requests, quotas, authentication and database TLS checks also pass.
- Dashboard production build: passed.
- Browser integration: immediate APK joining without an approval click, automatic device-list refresh, legacy approval/decline, manual enrollment, login/logout, readable screen/notification text, health, phone-reviewed requests, file upload/preview/download, map, reports, logs and mobile layout. Passed with no page errors.
- All 22 active Kotlin files from 0.3.0 and their existing function names remain; active source count is now 23. Original archives, variants and previous dashboard tools remain.
- Live Render `/health` was reachable over HTTPS and returned HTTP 200 with {"ok":true}; this older response indicates that the matching new backend still needs deployment. No remote code, settings or data were changed.

No physical Android phone is connected. Keystore behavior, runtime permissions, sensors, accessibility, OEM restrictions and automatic startup timing need phone verification. The APK is a signed debug/test build. Deploy the included backend/dashboard updates before using automatic enrollment.
