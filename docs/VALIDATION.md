# Validation — Android update 0.4.0

- Android assembleDebug, lintDebug and testDebugUnitTest passed. Lint has zero errors; nonblocking SDK/style warnings remain.
- All 21 unit tests passed with zero failures/errors: 14 existing checks, three front/rear camera-selection checks and four readiness-state checks.
- Camera tests verify front selection even when rear is listed first, retained explicit rear choice and no silent rear fallback when front is unavailable.
- Readiness tests verify that saved credentials alone cannot claim connectivity, stopped state remains explicit, first-upload waiting is shown and missing enrollment/notification access is handled.
- Package com.example.systemhealth; versionCode 7; versionName 0.4.0; minSdk 26; targetSdk 35. Signing certificate verified against the previous delivered APK.
- All 27 prior active Kotlin files and their existing function names remain. Three implementation files and two test files were added.
- Backend, dashboard, original archives and variants are unchanged. No Render deployments or settings were changed. The new installation page is a separate private Site and serves the matching APK.
- Connection verification is read-only: public health plus authenticated device-request GET. Upload timestamps are recorded only after successful server acknowledgement and are tied to the enrollment identity.
- Previous sensitive sharing approvals, foreground notices, Stop/Cancel, both app-selection modes, manual connection settings, special-access links and reader reconciliation remain.
- The private installation page contains a permanent APK path, QR, version and short setup instructions. Static links/assets and the APK signature/identity were checked before publication.
- No Android phone/emulator is attached. Actual camera lens/rotation, installation, permission prompts, wizard interruption, Stop cleanup and OEM notifications still need physical-phone testing.

## Previous validation records

# Validation — Android update 0.3.2

- Android assembleDebug, lintDebug and testDebugUnitTest passed. Lint reports zero errors; nonblocking SDK/style warnings remain.
- 14 unit tests passed with zero failures/errors: six existing checks plus five permission-plan checks and three request-alert checks.
- New permission tests cover Android API gates, explicit tool choice, skipping granted access, coarse/fine request pairing and exclusion of background/special access from runtime batches.
- Request tests cover reordered lists, retried IDs and notice updates for new/completed requests.
- Package com.example.systemhealth; versionCode 6; versionName 0.3.2; minSdk 26; targetSdk 35.
- APK signature verified against the previous delivered APK; signing certificate is unchanged.
- All 23 previous active Kotlin files and existing function names remain. Four implementation files and two unit-test files were added.
- Backend and dashboard implementation files are unchanged in this update. This APK uses the existing API version 4 enrollment and upload APIs. No remote deployments or settings were changed.
- The optional permission checklist prepares tools without invoking camera, audio, location, scans or app-text sharing. Existing Stop/Cancel and sensitive-request review paths remain.
- Notifications remain visible. Active foreground-service notices are grouped with a summary, request alerts use a quiet default channel, and unchanged IDs do not repost the same request list.
- No Android phone or emulator is attached. First-launch setup, rotation, granted/denied permission behavior, notification grouping, Stop cleanup and OEM behavior still require a physical-phone test. This is a signed debug/test APK.

## Previous 0.3.1 validation record

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
