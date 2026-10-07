# Validation — Android update 0.5.0

Checked on 2026-10-07 on the build PC. The Android rows below were first measured on the PC, then a physical-phone session was run the same evening; see "Physical phone session" for what a real handset confirmed and what still has no evidence.

- Android: `:app:testDevUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDev`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDev`, `:app:lintDebug` — BUILD SUCCESSFUL. **49 unit checks pass in each variant, 0 failures and 0 errors** across 11 test classes. Lint reports **0 errors**; 100 non-blocking style/target-SDK warnings remain (three fewer than before the unused-code pass).
- New coverage in this version: `QueuePolicyTest` (outbox shedding, batch size, retry retirement), `RemotePolicyTest` (a rule list that was never sent leaves the phone's own choices in charge, an omitted tool stays available, an empty list stops every governed tool, unknown names are dropped, cadence bounds), `ReportScheduleTest` (cadence boundaries, a never-run report becomes due, only report-only tools can repeat), `DeviceCommandRouterTest` (the rules channel stays silent and routable), `UpdateAwarenessTest` (only a strictly newer version is mentioned, `-dev` suffixes do not compare as upgrades).
- Package `com.example.systemhealth`, versionCode 8, versionName 0.5.0, minSdk 26, targetSdk 35. The version now comes only from `android/version.properties`.
- Release build runs R8 and produces `app-release-unsigned.apk` — unsigned, because no `android/keystore.properties` and no CI signing environment exist here. Its deobfuscation map is written once, to `app/build/outputs/mapping/release/mapping.txt`; the duplicate `-printmapping` line that used to drop a copy into `android/app/` is removed. An unsigned release installs nowhere, which is intended until you add your own key.
- Backend: `npm test` → **39 passing checks** on SQLite, covering the previous enrolment/telemetry/file/request surface plus the rules payload validation, the full request lifecycle, dashboard session revocation surviving a restart, and the advertised APK release. The PostgreSQL branch of the suite runs only with a live `TEST_DATABASE_URL`, which was not available; no real database was queried.
- Dashboard: `npm run build` passes (387.58 kB JS bundle). A build without `VITE_SERVER_URL` falls back to `http://localhost:3000` and cannot reach a hosted backend.
- Rules flow was exercised end to end in a real browser against a **throwaway** backend on a spare port with a temporary SQLite file and self-generated test credentials: send rules, the phone's `device_status` echo, and a switched-off tool returning `declined` with the rule named. This found and fixed a genuine dashboard bug where unchecking a tool sent only that tool. No `.env`, `.env.dev`, production database or Render setting was read or changed, and the scratch process, database and files were deleted afterwards.
- Consent surface retained: monitoring still starts from the visible app, camera/microphone capture still runs only on the foreground-service subtype that visible start declared, screenshot/file/location/geofence actions still ask on screen, Stop still cancels, required foreground notices stay visible, and dashboard rules can only remove capability — never grant a permission or start monitoring. The visible-start rule, the ongoing notice, the decline-with-rule-named answer and the rules echo were all confirmed on the handset below; the rest stays inspection-only.
- Not verified on hardware: reboot and boot-service start, accessibility and notification-listener behaviour on the Camon 20, scheduled reports surviving a long Doze idle, the microphone indicator's exact timing, whether the captured JPEGs show the expected framing, and the two refusal paths added after the phone session (the notification-switch message and the "start monitoring first" answer, which cannot be provoked on demand while the poll loop is dead). `START-HERE.md` lists the phone checks for each.
- No commit or push was made. The working tree holds the changes.

## Physical phone session — TECNO Camon 20, 2026-10-07 evening

A real handset (TECNO CK6n, Android 14 / SDK 34, HiOS) ran `app-dev.apk` versionName `0.5.0-dev`, package
`com.example.systemhealth.dev`, against the local development backend over wireless ADB with a
`tcp:3000` reverse tunnel. Every claim below is read back from the server's SQLite ledger or the phone's
own files, not from the screen. Nothing was done against Render or production, and the phone enrolled
itself into the throwaway development database only.

- Installed as an update three times with `adb install -r` over the same app data. Enrolment, permission
  choices and the saved connection survived every reinstall; the phone never had to be paired again.
- Automatic enrolment worked without an approval click: one device row, `enabled`, created 15:54Z.
- Telemetry: 18 `system_health` and 8 `device_status` uploads. A dashboard status request answered in
  about 6 seconds at best, and the phone polls every 10 seconds (5 with a backlog).
- Headless camera: 16 photos captured with the app in the background, each around 480 KB, uploaded to
  Files. Android's own sensor light stayed on during each capture.
- Headless microphone: one 15-second recording, request `16:48:23Z` answered `completed` at `16:48:54Z`
  with a 79,846-byte `.m4a` in Files.
- Capture queueing, measured after the fix: three photos sent inside 1.4 seconds (`16:57:48.370`,
  `:49.082`, `:49.725`) all reached `completed` at `17:05:01`, `:06` and `:12` — one per poll, about six
  seconds apart, none refused. A later burst of four behaved the same way.
- Other tools on hardware: location fix (3), security audit report, settings backup (2), nearby scan, and
  a file pick the owner cancelled, which answered `declined — Capture or file selection cancelled.` Those
  three tools were removed from the app later the same day at the owner's request (see `docs/CHANGES.md`),
  so this is the only record of them running on a real handset.
- Honest refusals, not silent ones: a scan with Bluetooth switched off answered
  `Enable Bluetooth before running a nearby scan.` instead of uploading an empty list.
- Rules: 6 `request_settings` completed, each echoing the applied rules back as a `settings_applied`
  event; a photo sent while the rule kept photo off answered
  `declined — A dashboard rule keeps photo off on this phone.`
- Scheduled reports: ticking status, scan, audit and backup on the phone produced three uploads inside the
  same second and the backup 19 seconds later, with no dashboard request behind any of them; the phone's
  `report_schedule` preferences carry matching `last_run` stamps.
- Two real defects found only because the handset was attached, both fixed and rebuilt the same evening:
  `HeadlessCapture` never cleared its busy flag after a **successful** capture, so one delivered photo
  made every later camera and microphone request fail with "Phone is already finishing another capture."
  until monitoring stopped (this is the source of all 7 `request_photo` failures above), and a second
  capture arriving in the same poll was answered as a failure instead of waiting for the lens.
- Also corrected the same evening: the development launcher now passes the release-announcement keys
  through, and the backend accepts a comma-separated origin list so a local dashboard reached as
  `localhost` or `127.0.0.1` no longer shows a misleading "Cannot reach the server".
- Reproduced a real setup trap: with the app's notification switch off, `CoreService` refuses to run at
  all — correct, because monitoring must keep a visible notice — but the phone still toasted "Starting
  monitoring. Check the ongoing notification." Dashboard requests then sat at `delivered` forever with no
  explanation. `requestMonitoringStart()` now checks `areNotificationsEnabled()` first, says what is
  missing and opens that settings screen. **This last change is built and installed but its message was
  never displayed on the phone**: the ROM rejects `cmd appops set … POST_NOTIFICATION deny`, so the
  blocked path could not be re-created on demand.

## Validation — Android update 0.4.0

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
