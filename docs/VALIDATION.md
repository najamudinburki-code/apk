# Validation — handset session, 8 October 2026 evening

Driven on the owner's **TECNO Camon 20 (model CK6n, Android 14, HiOS)** over wireless ADB, running the
`dev` variant `0.5.0-dev` against the laptop's local backend through `adb reverse tcp:3000 tcp:3000`.
Every claim below was read off the phone's own screen (screenshot plus `uiautomator` hierarchy), not off a
build log. This is the session that the "not verified — no phone attached" rows in the record underneath
were waiting for; those rows stay as written for the day they describe.

**Live behaviour confirmed.** Monitoring ran the whole session and the home screen and tools screen both
showed server-acknowledged deliveries advancing on their own — `Last confirmed delivery: 8 Oct, 20:26:54`,
then `8 Oct, 20:31:28`, then `20:36:37` — so the ~5-minute cadence and the "confirmed by the server"
wording are real, not a button echo. Eight items left over from an earlier interrupted run stayed
reported as `8 items could not be sent and are kept aside` rather than being quietly dropped or counted
as sent. After `adb install -r` killed the service, the home screen read **"Ready — monitoring stopped"**
with `Sharing is off. Nothing reaches your dashboard until you start monitoring.` — it did not keep
claiming a session it no longer had. Restarting monitoring was left to the owner's own finger.

**Ten defects the handset showed and the build never could.** All are fixed, rebuilt and re-checked on the
phone unless a row says otherwise:

1. The primary button was invisible in dark mode: this ROM's `colorPrimary` is `#181B25`, the same colour
   as its own window background. The kit now picks the first theme accent that is actually distinguishable
   from the page (`accentColor()`), and the button was re-photographed painting solid blue with white text.
2. A `RippleDrawable` given a null mask never paints its content here — the button stayed the colour of the
   card. Filled buttons are now a `StateListDrawable` of rounded fills with a darker pressed copy.
3. Cards were indistinguishable from the page behind them, so the page read as one long column. Card fill
   is now lifted from the resolved surface and the emphasis border uses the same guarded accent.
4. `android.R.attr.colorError` on this ROM's light theme is `#FF5722`, which measures 2.4–2.8:1 as text.
   Alert words now go through `readableInk()`, which darkens that same colour until it reaches 4.5:1; the
   Stop outlines keep the loud colour and only the words change.
5. `Nothing waiting to upload` was drawn in the alert colour unconditionally — a red alarm saying nothing
   was wrong. The uploads row now goes red only when something is actually waiting, and the separate
   "Could not be sent" row carries the real alarm.
6. The search hint was ellipsized. Shortening it once fixed it at normal text and broke it again at 1.3×
   (`Search tools — try "photo" or "…`), which is a broken-looking field rather than a hint. It is now
   simply `Search tools`, and the example words live in the tool names themselves. **This one is built and
   installed but not yet re-photographed** — the shortened string has not been seen on the tools screen,
   because that screen needs the owner's finger (see "Still not verified").
7. **Searching emptied the whole tools screen.** A card-level flag hid everything the moment the box had
   any text, so the page appeared to have no tools at all. That flag is gone: a card leaves the page only
   when nothing inside it survives the filter, and the way out is pinned (`Back to monitoring` now carries
   `staysVisible = true`, as every Stop-weight control already did). Re-checked with `photo`, with `stop`,
   and with the box cleared.
8. The connection row could read `Not confirmed: Server accepted this phone` — quoting an older success as
   though it were the present state. It now reads `Not confirmed. Last report said: …`.
9. `8 item(s)`, `2 request(s)`, `4 frame(s)`, `3 upload(s)` were shown to the owner as `(s)` placeholders.
   One tested helper (`PlainStatus.count`) now covers all six sites.
10. **The home screen nagged about a server that was answering it.** `ConnectionDiagnostics.verified()` only
    counted a manual "Check the connection now" tap within the last two minutes, and an acknowledged upload
    wrote a different key entirely. So with samples landing at 20:26:54, 20:31:28 and 20:36:37 the phone still
    read `Connected to your dashboard → Not confirmed`, and the headline stayed "Needs your attention" with a
    `Check the connection again` button, while the dashboard was receiving data the whole time. Only the phone
    could show this, because on the PC the two minutes are a constant nothing ever changes. A delivery now
    proves the connection for `deliveryWindowMs` = three sample periods with a 15-minute floor, and a manual
    probe still counts for its own two minutes. `ConnectionDiagnosticsTest` covers the window against the
    phone's own cadence (1, 5, 10 and 240 minutes) and the inside/boundary/outside/fresh-boot cases.
    **Re-built and installed; the no-probe flip has not been re-photographed yet** — it needs monitoring
    running long enough for the probe flag to expire, which is the next check in this session.

**What the phone said when the laptop stopped answering, and when it was busy.** Twice the dev tunnel died
(`adb reverse` ends with the ADB session, not with the app). Both times the phone stopped advancing
"last delivery" and said so, and the eight items already in the queue stayed reported as unsent instead of
being counted as delivered. Later, while a Gradle build had the laptop's load average at 24, a connection
probe timed out and the phone reported `Server is slow or waking up. Keep the app open and retry.` — the
truth: the server was up, it just could not answer in fifteen seconds. Nothing in this session ever showed a
fake success. The one behaviour worth knowing before relying on dashboard requests: after repeated failures
the queue loop backs off to `cadenceMs` = 20 s, 40 s, 80 s, 160 s, capped at 300 s, so a queued request can
sit for up to five minutes after the connection comes back, while the home screen correctly says monitoring
is on. Requests expire after ten minutes, so the cap is smaller than the expiry and nothing is dropped.

**Appearance matrix actually photographed.** Home in light and in dark, at the phone's normal text size and
at `font_scale 1.3`; Device tools in dark at both sizes (its light capture at 1.3 is the one cell still
open, waiting on the tap described under "Still not verified"). Headings, card titles, secondary labels,
alert rows, the camera spinner, checkboxes, the fold, the search box and every button weight render with
readable contrast and nothing truncates or overlaps; Stop buttons wrap to two lines instead of clipping.
Dark mode was forced with `cmd uimode night yes` and returned to the phone's own auto setting afterwards,
and the text scale was put back to `1.0`.

**Tool search, measured.** `photo` keeps the photo tool, the pinned state rows and the Stop controls;
`stop` keeps `Stop the recording`, `Stop the live camera view now`, `Stop sharing location`,
`Cancel the active request` and `Back to monitoring`, and also `Take one screenshot` because its own
explanation ends "one capture, then it stops" — a match on real wording, not a filter bug. Emptying the box
restores all seven cards.

**Re-measured on the build PC after these fixes:** `:app:assembleDev` and `:app:testDevUnitTest` →
BUILD SUCCESSFUL, **129 checks across 19 classes, 0 failures, 0 errors, 0 skipped** (counted from
`app/build/test-results/testDevUnitTest/`; 127 across 18 before the tenth defect was fixed, the extra two
being the new `ConnectionDiagnosticsTest`). `:app:lintDev` was last run before that fix: **0 errors, 82
warnings** in the pre-existing categories (`UseKtx` 47, `InlinedApi` 17, `ObsoleteSdkInt` 8,
`StaticFieldLeak` 5, dependency and target-SDK notices). It has not been re-run since, because a Gradle
build on this laptop is heavy enough to make the phone's connection probes time out.

**Still not verified.** The guided setup screen has never been rendered on a handset: it is not exported, so
neither `am start` nor `run-as … am start` could open it and no scripted tap was attempted. TalkBack was not
run — reading order, heading announcements and the polite live regions are designed for but unmeasured, so
START-HERE.md no longer claims the app "speaks properly to TalkBack". Also unmeasured: the geofence and
enrolment dialogs' hint text at enlarged font, rotation, small screens, and every capture path inside the
rebuilt screens (photo, microphone, screenshot, live view, scan, sharing, uploads) beyond the telemetry and
delivery states quoted above. Reboot, long Doze idle and the release-signed APK against Render remain as the
older records describe them.

**This does not make the app release-ready.** It is a `dev`-variant debug build pointed at a laptop, and the
release artifact has still never been installed on a phone.

---

# Validation — interface rebuild (source), 2026-10-08

Measured on the build PC right after the last UI edit. No phone or emulator was attached, so nothing here
is evidence about appearance, screen readers or a sensor. The live camera view record below is that
feature's own run and stays accurate for it; the check counts changed because this pass added 34 checks.

- Android: `:app:testDevUnitTest` → BUILD SUCCESSFUL, **127 unit checks across 18 test classes, 0 failures,
  0 errors, 0 skipped**, counted from `app/build/test-results/testDevUnitTest/` rather than a console line.
  93 were passing before this pass.
- New Android coverage, all Android-free logic so it runs on the JVM: `HomeOverviewTest` (13 — each blocker
  picks its own primary action, a running session with nothing to fix offers no button, an unacknowledged
  first sample is never reported as delivered, the four calm service lines stay out of the attention card
  while any other line is promoted to it, a live stream / unsent items / a stalled queue and an available
  update each come with the way out, Stop rows appear only with something to stop, and rules, reports and
  waiting requests are listed only when they exist); `ToolCatalogTest` (11 — every id tagged on the tools
  screen exists in the catalog and ids are unique, every category has a tool, every control has a name and
  an explanation, blank search shows everything, "stop" keeps every way to stop something, extra words
  narrow instead of widening, an unmatched word hides the card rather than showing all of it, and an unknown
  dashboard action keeps the server's own name instead of a guess); `PlainStatusTest` (10 — all eight ledger
  states and an unknown one, permission names and the Settings route after a refusal, a reason for every
  setup step, on/off pairs, the three live-view states, singular and plural counts, absent rules and
  schedules saying so, and a saved local file never described as uploaded).
- Android: `:app:assembleDev` → BUILD SUCCESSFUL, `app/build/outputs/apk/dev/app-dev.apk`.
- Android: `:app:lintDev` → BUILD SUCCESSFUL, **0 errors**, 82 non-blocking warnings in the pre-existing
  categories (`UseKtx` 47, `InlinedApi` 17, `ObsoleteSdkInt` 8, `StaticFieldLeak` 5, dependency notices).
  Nothing new in kind: the kit resolves colours from the theme and holds no static context.
- Files: 4 new in `app/src/main/java/com/example/systemhealth/` (`ScreenKit.kt`, `HomeOverview.kt`,
  `ToolCatalog.kt`, `PlainStatus.kt`), 2 new resources (`res/values/themes.xml`, `res/values-night/themes.xml`),
  3 rewritten screens (`MainActivity.kt`, `FeaturesActivity.kt`, `PermissionSetupActivity.kt`), 1 manifest
  line (the app theme), 3 new test files. No service, receiver, permission, `build.gradle.kts` dependency,
  backend or dashboard file was touched.
- Preserved by inspection: enrollment (automatic and manual), every consent dialog and its wording, the
  two Stop controls, all tool actions and their request states, the dashboard-rule semantics, the single
  file outbox, all preference keys (`permission_setup`, `feature_options`, `remote_policy`,
  `system_health_settings`, the screen-monitor prefs) and the device identity. `docs/UI-BEFORE-AFTER.md`
  maps each old control to where it is now.
- Caught by the compiler and fixed: a data class nested inside an inner class, `DisplayMetrics.fontScale`
  (the scale is on `Configuration`), `android.R.attr.colorSurface` (not a framework attribute — the card
  fill now resolves `colorBackground` with a measured fallback), and
  `NotificationManagerCompat.getEnabledListenerPackages()`, which takes a `Context` and was being handed a
  package name.
- Still unverified without a handset: light and dark appearance and the resolved contrast, large system
  font layout, small screens, long device names, empty lists and keyboard behaviour over the search box,
  TalkBack order, heading announcements and the change-gated redraw, the refused-permission guidance
  rendering after a real decline, setup resuming from a mid-wizard exit and after rotation, and every
  capture, sharing and upload path. A green build says nothing about those.
- This is a `dev`-variant debug build against a local server. It is not signed for release and must not be
  called release-ready on the strength of these checks.

## Previous validation record: live camera view

Measured on the build PC the same day the feature was written. No phone was attached, so nothing below
is evidence about a camera, a sensor light, or a notification.

- Android: `:app:testDevUnitTest` → BUILD SUCCESSFUL, **93 unit checks across 15 test classes, 0 failures,
  0 errors, 0 skipped** (read from `app/build/test-results/testDevUnitTest/`, not from a console line).
  Only the `dev` variant was re-run after this feature; `testDebugUnitTest`, `assembleDebug` and
  `assembleRelease` have not been re-run since, so the 0.5.0 rows below still describe those.
- Android: `:app:compileDevKotlin`, `:app:assembleDev` and `:app:lintDev` → BUILD SUCCESSFUL, **0 lint
  errors**, 99 non-blocking warnings in the same pre-existing categories (the new `StaticFieldLeak` row
  for `LiveStreamBridge.session` matches the three the verified `HeadlessCapture` already has: it holds
  an application context, and the session reference is cleared when the stream ends).
- A review pass over the new code found three defects, all now fixed and rebuilt: the session's time and
  byte limits were only checked **when a frame arrived**, so a camera that delivered one frame and then
  went quiet without reporting an error would have held the lens and blocked every later live view until
  monitoring stopped (a deadline job now ends it, reusing the same `limitReached` wording); the started/
  ended alerts and the notice refresh ran outside the throw-safe queue, so a failing notification could
  strand an open camera; and the flag that decided which frame answers the request was a read-then-write
  shared with the stop path, which could answer one request twice. `LiveViewPolicy.overBudget` was
  removed as dead once `limitReached` proved to be the only limit anyone consults.
- New Android coverage, all pure logic: `LiveViewPolicyTest` (10 — cadence gate, the 2 fps the owner is
  told, preview size chosen inside VGA with a smallest-usable fallback, zero sizes refused, byte and
  time limits and which reason is stated first); `CameraOwnerTest` (6 — first job wins, a live view
  cannot take the lens from a photo, only the holder frees it, a late teardown cannot cut another job
  off, and 8 threads starting at once produce exactly one winner); 5 rotation checks added to
  `CameraSelectionTest` (Android's 0..3 rotation codes, front turns with the display and rear against
  it, always a quarter turn); 2 added to `DeviceCommandRouterTest` (a live view is a headless capture,
  its stop command is silent so it can never queue behind the lens it must free); 1 added to
  `QueuePolicyTest` (a stale frame may be shed to clear a full queue, a photo or document may not).
- Backend: `npm test` → **40 checks pass, 0 fail**. The new one covers the whole server-side shape of a
  stream: arguments refused with 400 (the phone owns the rate and limits), the start request's wording
  naming the owner allowance and the 120-second ceiling, `live_view` accepted as a rule, a `live_frame`
  upload stored and linked as the proof that answers the request, the stop request accepted, both rows
  ending `completed`, and an unknown `video` kind still refused. This run also caught a test I had
  broken: my new upload made the later retention check count two files instead of one, so the live-view
  check now deletes its own frame the way the dashboard would.
- Dashboard: `npm run build` passes (387.02 kB JS bundle).
- Not verified: whether a real camera opens, whether a frame is legible or upright, front versus rear on
  this handset, the indicator and notice lines, every refusal message, the 10-second no-frame watchdog,
  the deadline job that ends a stalled session at 120 s, the 6 MiB stop, behaviour when the vault or the
  queue is full, airplane mode mid-stream, and whether the phone can start a second live view immediately
  after stopping one. `START-HERE.md` and `docs/SILENT_VERIFICATION.md` carry those as phone checks to run.
- No commit, push, deployment or production/Render data touched. Not in the delivered 0.5.0 APK.

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
