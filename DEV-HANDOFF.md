# DEV-HANDOFF.md

Handoff notes for the 0.5.0 work. Written 2026-10-07. If you are an AI picking this up, read
`PROJECT.md` (editing contract) and `LOCAL-DEVELOPMENT.md` (workflow) first, then this file.

## Where the project stands

One Android app (`android/`, versionCode 8 / versionName 0.5.0), one Node backend (`backend/`,
API version 4), one React dashboard (`dashboard/`). Everything is built and tested in this
working tree; **nothing has been committed or pushed, and nothing has been deployed.**

## What changed in this round

- **One transport.** The phone's socket stack (`SyncManager.kt`, `NetworkMonitor.kt`) is deleted.
  Every report, capture and request answer now goes over the authenticated HTTP device routes from
  a file outbox. `QueuePolicy.kt` holds the queue decisions; `FeatureBridge.cadenceMs` holds the
  timing (10 s normally, 5 s with a backlog, backoff up to 5 min on failures).
- **Honest delivery.** Requests move `pending → delivered → running → completed / reviewed /
  declined / failed / expired`, and a completed result names the event or file that proves it.
- **Headless where Android allows it.** Dashboard photo and microphone requests run through
  `HeadlessCapture.kt` on the foreground subtype `CoreService` already declared. Nearby scans use
  one engine, `HeadlessScan.kt`, for both the screen and the background loop;
  `EnvironmentScanner.kt` is gone. Screenshot, location start, geofence approval and file picking
  still need the visible tools screen because that is where Android asks.
- **Dashboard rules** (`request_settings`, `RemotePolicy.kt`). Narrowing only: health cadence and
  which remote tools may run. Server-side whitelist plus a second check on the phone; the rules and
  status channels cannot be switched off; rules never grant a permission. Re-enrolling to a new
  server clears them (`SyncSettingsStore.persist`).
- **Scheduled reports** (`ReportSchedule.kt`). Owner opts in on the tools screen; they run inside the
  existing monitoring loop, so no WorkManager, no new dependency, no new permission, and Stop stops
  them. Report-only tools can repeat; capture tools cannot.
- **Dashboard**: Requests ledger with retry, device rename/disable/rotate-token, Phone rules tab,
  Activity log (`ActivityLog.jsx`, server-paged via `after`), CSV/JSON exports with a
  spreadsheet-formula guard, Leaflet map with a boundary request, and "Sign out everywhere"
  (`POST /api/sessions/revoke`, stored watermark).
- **Update awareness**: `/health` can advertise `latest_app_version` / `latest_app_url`; the phone
  only mentions a strictly newer build.
- **Build**: version from `android/version.properties`; release signing reads
  `android/keystore.properties` or CI environment, never source; R8 map stays in its default
  location; `.github/workflows/backend-dashboard.yml` joined `android.yml`.

## Verified here

| Check | Result |
| --- | --- |
| `backend` `npm test` | 39/39 pass (SQLite; the PostgreSQL branch needs a live `TEST_DATABASE_URL`) |
| `:app:testDevUnitTest` / `:app:testDebugUnitTest` | 49 checks each, 0 failures, 0 errors |
| `:app:assembleDev` / `:app:assembleDebug` / `:app:assembleRelease` | all succeed; release is unsigned |
| `:app:lintDev` / `:app:lintDebug` | 0 errors, 100 non-blocking warnings |
| `dashboard` `npm run build` | passes, 387.58 kB bundle |
| Rules flow in a browser | exercised against a throwaway backend + temp SQLite; found and fixed a real dashboard checkbox bug |

Every Android row above was re-run after the unused-code pass recorded under "Open engineering items",
on 2026-10-07: `BUILD SUCCESSFUL in 9m 39s`, 147 tasks, 38 executed.

Gradle on this 3.9 GB machine needs
`JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` and
`bash gradlew --no-daemon --max-workers=1 <tasks>`.

## Not verified — do not claim otherwise

A TECNO Camon 20 (Android 14) ran the `dev` build against the local development backend on the evening of
2026-10-07. That session confirmed headless photo and microphone capture, installation as an update,
scheduled reports, the rules round trip and the ongoing notice, and it found three real bugs — see
"Camon 20 session" in `docs/CHANGES.md`. It does not clear the release: **reboot behaviour, accessibility
and notification-listener extraction, scheduled reports through a long Doze idle, Android's camera and
microphone indicator timing, screen capture on this OEM, geofence entry, and the release-signed APK
against Render** all still need the phone checklist in `START-HERE.md`. The notification-switch refusal
message added after the session was built but never displayed on a phone, because that ROM rejects the
`appops` command that would have re-created the blocked state. Unit tests and a debug build are not
release readiness, and neither is one evening with a handset plugged in.

## What you still need to do

1. **Deploy** the bundled `backend/` and `dashboard/` to your existing Render services to get the new
   dashboard tabs and `request_settings`. The 0.5.0 phone still works against the already-deployed
   API version 4 backend; nothing new is *required* there.
2. **Release signing** if you want a non-debug APK: create your own keystore, put the four values in
   `android/keystore.properties` (already git-ignored), rebuild `:app:assembleRelease`, and keep
   `app/build/outputs/mapping/release/mapping.txt` with the release so crash reports are readable.
3. **Optional** `APP_RELEASE_VERSION` (and `APP_RELEASE_URL`) in Render's Environment page once you
   actually host a newer APK, so phones can mention the update.
4. **Phone test** the checklist, then commit. Nothing is committed yet.

## Open engineering items

- **Dead-code pass (done 2026-10-07).** Removed as unreferenced: `AppIdentity.VERSION_CODE`,
  `NotificationPresentation.clearActivityLog()`, `NotificationPresentation.LOCATION_CHANNEL` (a
  phantom "Location sharing" notification channel that no code ever posted to), the unread
  `feature_options/screenshot_status` preference write in `ScreenCaptureService`, the unused
  `android.app.NotificationManager` import there, and `CoreService.ACTION_STOP` with its unreachable
  `onStartCommand` arm (nothing ever sent that action; the app's Stop path disables monitoring and
  `stopSelf`s, which still works). The live location disclosure was **not** weakened: the
  `LocationTrackingService` channel is what actually carries the location notice, and it now reads
  "Location sharing / Shown while this phone's location is being shared with your dashboard"
  instead of the old "Vehicle location tracking … fleet management" wording. An already-installed
  phone keeps its old channel label until the channel is recreated — the id did not change, so this
  is a wording fix for new installs, not a rename you can count on for the Camon 20.
- **Kept on purpose**, even though nothing in the active app calls them: the unused public helpers in
  `systemmanagement/ServiceManager.kt` (`checkAllServiceStatuses`, `startAllServices`,
  `restartAllIfNeeded`, `stopAllServices`, `enqueueStopAll`, `lastRequestResult`, `settingsIntent`,
  `isServiceRunning`) and `fleet/tracking/LocationTracker.kt`
  (`foregroundPermissions`, `backgroundPermission`). This project has promised since 0.2.0 that
  original source function names remain, and `archives/`/`variants/` reference that shape. Deleting
  them is a documentation decision, not a cleanup — say so before doing it.
- **That promise now has one deliberate exception.** On 2026-10-07 the owner asked for the security
  audit, single-file picker and settings backup features gone completely, so `utility/security/SecurityAuditTool.kt`
  and `utility/backup/SettingsBackupTool.kt` (with `readSharedPreferences`, `backupOwnSettings` and
  `backupInstalledApps`) are deleted rather than kept-unused. The originals still exist under
  `archives/`, and `docs/FILE-MAP.json` marks both rows `removed`.
- **Known minor edge case:** `FeaturesActivity.runPendingRequests` blocks while a *screen-started*
  request or recording is active, but a background silent/headless request that is already `running`
  does not set that flag, so tapping the same entry in the picker can start a second local capture
  and upload a duplicate report. Fixing it means exposing the in-flight ids from `FeatureBridge`; it
  was left alone rather than adding state for a rare duplicate.
- File-level outbox behaviour (writes, renames, quota accounting) needs Robolectric or a device to
  test; the decisions behind it are covered by `QueuePolicyTest`, `RemotePolicyTest` and
  `ReportScheduleTest`.
- Historical review files under `docs/` (`REVIEW.md`, `CONNECTOR-REVIEW.md`, `ORIGINAL-REVIEW.md`,
  `PRESERVATION.md`, `SCREEN-ERRORS.md`, `INTEGRATION-STEPS.md`) describe the 0.3.x architecture,
  including `SyncManager`. Each now carries a "historical snapshot" line — they are records, not
  current state. `docs/FEATURE-STATUS.md`, `docs/CHANGES.md` and this file are the live description.
- `docs/ADDED-FILES.json` is the frozen list of what the original integration added; it is not
  regenerated per version. `docs/FINAL-FILE-MAP.json` is the authoritative "what should exist now".
- `docs/FILE-MAP.json` keeps each uploaded file's original hash forever and its `current_*` fields
  describe the working tree; those were refreshed against disk on 2026-10-07 and each row now carries
  a `preservation` verdict: 9 rows byte-identical to the upload (the six `archives/` ZIPs and three
  dashboard files), 5 identical apart from Windows line endings, 20 rewritten during
  development, and 7 removed with a `status` line saying where the work went. Nothing reads this file
  programmatically; it is evidence, not input.
- `archives/` and `variants/` stay untouched for active-app work.

## Key files

| Area | File |
| --- | --- |
| Queue, transport, requests | `android/app/src/main/java/com/example/systemhealth/FeatureBridge.kt`, `QueuePolicy.kt`, `DeviceCommandRouter.kt` |
| Rules and schedules | `RemotePolicy.kt`, `ReportSchedule.kt`, `CoreService.kt` (cadence), `SyncSettingsStore.kt` (clearing) |
| Capture | `HeadlessCapture.kt`, `HeadlessScan.kt`, `CameraController.kt`, `AudioRecorder.kt`, `ScreenCaptureService.kt` |
| Update notice | `UpdateAwareness.kt`, `backend/server.js` (`/health`) |
| Phone UI | `MainActivity.kt`, `FeaturesActivity.kt`, `PermissionSetupActivity.kt` |
| Server | `backend/server.js`, `features.cjs`, `feature-store.cjs`, `store.cjs`, `schema*.sql` |
| Dashboard | `dashboard/src/App.jsx`, `components/FeaturePanels.jsx`, `components/ActivityLog.jsx`, `components/EnrollmentPanel.jsx` |
