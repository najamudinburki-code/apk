# Repairs and reasons

| Change | Why it was needed |
| --- | --- |
| Gradle project and wrapper | Android Studio needs build files and dependencies in addition to Kotlin files |
| Component names and registrations | Android needs the exact activity/service/job/receiver classes |
| BIND_JOB_SERVICE string | The public SDK does not expose the constant previously referenced |
| Screen monitor public APIs | AI-generated flag/isTextView/textEntries references prevented compilation |
| Password redaction | Preserve ordinary text/metadata monitoring without collecting passwords |
| Bounded traversal/text and content-based duplicate check | Prevent oversized uploads, excessive tree work, and missed same-length text changes |
| Visible capture consent and CoreService consumer | Readers lacked approved-package setup and a sync destination |
| Lifecycle/status and Stop cleanup | Show connected state and release pending work/resources |
| Utility permissions and location/geofence declarations | Existing modules lacked declarations needed when called |
| Icon and private-data transfer exclusions | Provide a launcher icon and exclude credentials/queues from Android backup/transfer |
| Enrollment settings/local-development URL handling | Give the phone a server identity/token without embedding private credentials |
| Backend authentication/persistence/setup/tests | Provide a durable authenticated destination |
| Dashboard event details and separate health sample | Show each data type without screen events clearing health cards |
| Beginner instructions and feature inventory | Explain what to open/build/connect and identify retained unfinished prototypes |

No existing Kotlin file or function name was removed. ScreenMonitorService is in the normal build. The consent gate remains; there is no bypass or forced Settings grant. Unfinished utility/prototype workflows are documented rather than described as completed features.

## Render hosting update — 5 October 2026

Added optional PostgreSQL persistence for a free Render + Neon deployment, retained local SQLite, and kept every Android file unchanged. Added start:render, proxy hop validation, a process-readiness health endpoint, explicit host binding, Node pin, ignore rules, render.yaml and a beginner deployment guide. Enrollment, telemetry, event history, latest health selection, dispatch and shutdown use the selected database. Cloud transactions persist telemetry before acknowledgement; TLS validates the database certificate. The existing consent controls remain. The hosted setup starts with a new database; local records are not automatically migrated.


## 0.2.0 — complete tool connections

Added the visible FeaturesActivity, application-level location/geofence sink, persistent authenticated tool outbox, one-shot OS-approved screenshot service, installed-app selector and reader reconciliation control. Connected retained camera/audio/scan/audit/backup APIs. Added selected-folder browsing, local export/delete, settings selection restore and separately approved geofence restore.

Added PostgreSQL/SQLite shared-file storage, atomic event retry receipts, phone-reviewed request routes and persistent latest feature state. Connected dashboard text, maps, file preview/download/delete, requests, scans, reports and searchable/type-filtered received logs.

Expanded accessibility capture to custom text nodes and focused fields, bounded UTF-8 payload size, enabled window-content changes, and improved MessagingStyle notification fallback with an Android-version guard. Chrome is selectable despite its com.android prefix.

Existing source functions and original archives remain. No enrollment/database credentials are embedded in the APK. Installation and actual verification limits are documented in START-HERE.md.


## 0.2.1 — automatic app coverage

Removed the required package-ID input from the normal sharing flow. The default is now All supported apps automatically, including future apps as their screen/notification events arrive. Choose apps by name remains available; all previously selected-only controls and public enableAppCapture/ParentalCapture.enable entry points remain.

Saved automatic scope is separate from active sharing approval. Both screen readers and NotificationReader use the same approved session policy. Stop, visible sharing notices, password redaction and existing own-app/Android-system exclusions remain. This automatic scope is for screen text/notifications; camera, audio and screenshot controls retain their previous explicit actions.

Backup/export/restore now includes automatic versus selected scope, and old backups remain compatible. Restoring does not turn sharing on. Version raised to 0.2.1 (versionCode 3). Added policy tests for empty saved lists, future installs, selected scope and existing exclusions. No previous Kotlin file or function name was removed; backend/dashboard code is unchanged from 0.2.0.


## 0.3.0 — automatic device connection

Fixed the public Render address in AutomaticEnrollment and added per-installation random phone IDs and 256-bit device tokens. Credentials remain encrypted in Android Keystore-backed settings. First launch registers the phone and polls while the app is visible; dashboard approval enables uploads and starts health monitoring after notification permission. Stop cancels a deferred automatic start. An existing working enrollment is preserved and advanced manual connection settings remain.

Added persistent PostgreSQL/SQLite enrollment requests, authenticated dashboard approval/decline and proof-authenticated phone status polling. Public registration cannot activate a device. Pending requests expire after 24 hours and can be renewed by the same phone; declined/disabled phones stay blocked. No dashboard password, database password or shared device token is embedded in the APK. The health API version is now 3.

Added a New phones dashboard panel while retaining the manual enrollment form and every previous tool panel. Updated tests cover pending phones, duplicate approval, credential isolation, expiry renewal and disabled phones. Version 0.3.0 uses versionCode 4. All previous Kotlin files and function names remain.


## 0.3.1 — automatic connection without dashboard approval

The current APK now registers with an enrollment-only invitation, whose matching SHA-256 hash is bundled in the backend. New installations join immediately, generate their own ID/device token, and start health monitoring on first launch after Android notification permission. No URL, phone ID, token, package-name input or dashboard approval is needed in this path. A pending installation upgrading from 0.3.0 keeps its identity and joins automatically. Existing approved phones keep their credentials. Disabled/declined phones are not reactivated.

The default invitation needs no new Render environment variable. Anyone given this APK can enroll a phone; it contains no dashboard password or database credentials. Optional AUTO_ENROLLMENT_KEY_HASH=disabled stops future automatic joins while existing device connections continue. Registration without an invitation retains the legacy pending workflow. Manual enrollment and advanced phone settings remain available.

The dashboard now explains automatic connection and refreshes its device roster when an enrollment changes, so new phones appear immediately. All existing tool panels and legacy approval/decline controls remain. Android permissions, screen/notification sharing disclosure, visible notifications, password redaction, per-action sensor controls and Stop remain. No previous Kotlin file or function name was removed.

VersionCode is 5, versionName 0.3.1. Health reports api_version 4 and automatic_enrollment. Twenty-eight backend checks pass in each storage test setup; Android build/lint/six unit checks and dashboard build/browser checks pass. The matching backend/dashboard bundle still needs one deployment to the user's existing Render services.

## 0.3.2 — permission checklist and quieter visible notifications

Added an optional PermissionSetupActivity that opens once on first launch after this update and can be reopened from the main app. The user chooses camera, microphone, foreground location, nearby Bluetooth and status-notification permissions to prepare. Already granted permissions are skipped; denied or skipped permissions do not cause repeated automatic prompts. The permission queue and checkbox selection survive activity recreation. Background location is separate; accessibility/notification access remain Android settings choices; screen sharing still uses per-capture Android consent. Setup does not start any capture, reader or location sharing.

Added a visible, silent notification group with a summary naming the active statuses. Every foreground service retains its own notice and existing controls. Request notifications now use a quiet default channel with no sound, vibration or badge; stable request IDs prevent reordered/retried lists from reposting the same notice. Counts update when requests arrive or are completed. Existing channel choices remain user-controlled.

MainActivity retains Start, Stop, manual connection settings, all tools and both app-selection modes. Sensitive dashboard commands still wait for phone review. Health and already approved uploads remain automatic. No backend or dashboard changes are needed if API version 4 is already deployed.

VersionCode 6, versionName 0.3.2. All 23 previous active Kotlin files and their existing function names remain; four new Kotlin implementation files and two test files were added. Physical Android/OEM permission and notification behavior still needs a phone check.


## 0.4.0 — front camera, guided setup, readiness and simpler installation

- CameraController keeps the original capturePhoto entry point and adds explicit lens selection. Front is the default; a remembered Front/Rear selector retains the rear-camera tool. Missing chosen cameras report an error rather than silently capturing from another lens. Consent and reviewed dashboard photo requests identify the chosen lens. Camera permission, visible activity, cancellation, orientation, timeout and upload controls remain.
- PermissionSetupActivity now has Connect, Permissions and Check stages. Automatic enrollment and existing configuration remain; tool permissions are selected explicitly, already granted permissions are skipped, Android special access remains optional, and stage/choices survive interruption. Check performs read-only health and authenticated device API requests. Only the existing health auto-start flag can start health monitoring; previously stopped monitoring stays stopped. Setup does not start sensitive capture or approve remote commands.
- Home status separates saved enrollment from a verified connection and displays acknowledged upload time, monitoring state, notification access and pending tool uploads. Socket and feature transports record only server-accepted delivery timestamps; diagnostic timestamps cannot cause otherwise successful uploads to fail.
- MainActivity now has a simpler home screen and collapsible Advanced settings. All previous actions, Kotlin files and function names remain. Device tools, both sharing scopes, Start/Stop, manual configuration, Android settings, reader reconnect and detailed diagnostics remain reachable.
- Added a separate private installation page with a permanent download path, QR code, current APK version and three installation steps. It does not change Render data routing or require another API version-4 backend deployment.
- VersionCode 7, versionName 0.4.0. Added camera-selection and readiness tests. Phone hardware, installation and actual Android UI behavior still need physical testing.


## Local development workflow (source-only update, app version 0.4.0)

- Added a debuggable `dev` build type with its own package/name and loopback API URL; normal debug/release defaults keep the existing Render URL. Automatic enrollment now reads the generated API_BASE_URL. No existing Kotlin file/function name was removed.
- Added isolated backend `.env.dev` setup, watched startup and separate SQLite dev data. The local runner clears inherited production configuration; a Neon dev branch URL is optional and explicit.
- Added a local dashboard entry point that overrides other endpoint configuration only in this dev process. Production build/start commands and server API implementation are unchanged.
- Added Windows/shell setup, start and USB forwarding launchers, LOCAL-DEVELOPMENT.md, PROJECT.md, and a GitHub Actions test-APK build workflow.
- Existing delivered APK/installation site and Render/Neon account configuration were not changed. Native Kotlin updates use Apply Changes or Run; one-second native updates are not promised.


## 0.5.0 — one transport, honest delivery, dashboard control

VersionCode 8, versionName 0.5.0. `android/version.properties` is now the only place the app version is written.

**One upload path.** The socket stack (`SyncManager.kt`, `NetworkMonitor.kt`) is gone: every report, capture and request answer now travels over the same authenticated HTTP device routes the health data already used, from a file outbox under the app's own storage. `QueuePolicy` holds the decisions that used to be scattered through the queue — 200 pending entries, 20 per send, 40 attempts, and only `system_health`/`device_status` may be shed when the queue is full. The loop sends every 10 seconds, every 5 seconds while a backlog exists, and backs off to at most 5 minutes when the server keeps refusing. A socket is now only ever used by the dashboard.

**Delivery the ledger can prove.** A request moves `pending → delivered → running → completed / reviewed / declined / failed / expired`, and a completed result records which event or file proves it (`result_ref`). The phone reports what it actually did: a capture that failed says so instead of reporting success, and nothing is marked delivered before the server accepted it.

**Capture without a window, and the tools that still need one.** `request_photo` and `request_audio` run headless on the camera/microphone subtype that `CoreService` already declared, so a dashboard request no longer drags the tools screen to the front. Android's own camera and microphone indicators still appear and cannot be suppressed. Screenshot, file picking, geofence approval and location start remain visible, consented actions. `HeadlessScan` is the single scan engine for both the screen and the background loop, and `EnvironmentScanner.kt` is deleted. `DeviceCommandRouter` classifies each action so the routing is unit-testable.

**Dashboard.** A delivery ledger with a fix-it list, device rename/disable/revoke with token rotation, server-paged Activity log, CSV/JSON exports of the newest 1000 rows with a spreadsheet-formula guard, a boundary request the owner approves on the phone, a Phone rules tab, and "Sign out everywhere" that ends live dashboard sessions immediately through a stored watermark rather than waiting for tokens to expire.

**Phone rules (`request_settings`).** The dashboard can only narrow a phone: health sample cadence (1–1440 minutes) and which remote tools may run, validated by a server-side whitelist and again on the phone. Rules never grant an Android permission, never enable monitoring, and the rules and status channels cannot be switched off, so a narrowed phone can always be widened again. The phone stores the rules, declines a rule-blocked request with the rule named, and echoes the live rule set in `device_status`; re-enrolling to a different server clears them.

**Scheduled reports.** The owner can opt a report-only tool (status, scan, audit, backup) into repeating every 15/30/60/240 minutes. It runs inside the existing monitoring loop rather than WorkManager — no new dependency, no new permission, and it stops the moment monitoring stops. Camera, microphone, screenshot and file tools cannot be scheduled.

**Update awareness.** `/health` can advertise `latest_app_version` and `latest_app_url`; a phone only mentions an update when the number is strictly higher than its own build, so a `-dev` install is never told a same-number release is newer.

**Build and checks.** Release signing reads `keystore.properties` (ignored) instead of embedding a key, R8 keeps its map at the default `build/outputs/mapping/release/mapping.txt`, low-memory Gradle settings are documented, cleartext stays limited to the local `dev` variant, and CI runs the Android build/tests/lint plus backend integration tests and the dashboard build.

**What is not claimed.** Backend integration tests, Android unit tests, the `dev`/`debug`/`release` assemblies and the dashboard build pass in this environment, and the rules flow was exercised against a throwaway local backend and database. This is not a signed production release, and the hosted release-signed APK with Render has never been run on a phone.

### Camon 20 session, 7 October 2026 — three repairs found on hardware

A physical TECNO Camon 20 (Android 14) ran the `dev` build against the local development backend; the
measured results and the remaining gaps are in `docs/VALIDATION.md`. Three defects came out of it:

- **A successful capture left the phone permanently busy.** `HeadlessCapture` cleared its in-flight
  request only on the failure paths, so the first headless photo completed and every camera and
  microphone request after it answered "Phone is already finishing another capture." until monitoring
  stopped. The queue now releases the slot once the file is handed over, the same way the failure path
  always has.
- **A second capture arriving during the first was refused instead of waiting.** The lens and microphone
  genuinely serve one request at a time, but answering the extra ones with a failure threw away work the
  owner had asked for. The poll loop now dispatches at most one capture per pass and leaves the rest in
  the server's queue, where they run on a later poll or expire after ten minutes. Three photos sent
  inside 1.4 seconds now all arrive, about six seconds apart.
- **Monitoring could report a start that never happened.** `CoreService` correctly refuses to run while
  the app's notifications are switched off — it must keep a visible activity notice — but the home
  screen still toasted "Starting monitoring. Check the ongoing notification.", leaving dashboard
  requests stuck at `delivered` with no explanation anywhere. The start action now checks the switch
  first, says what is missing and opens that settings screen.

Two setup papercuts the same session exposed are fixed too: the development launcher now forwards the
release-announcement keys it used to drop, and the backend accepts a comma-separated list of exact
dashboard origins, so a local dashboard opened as `localhost` instead of `127.0.0.1` no longer reports
"Cannot reach the server".

### Three tools removed, 7 October 2026

The owner asked for the app to stop offering the local security audit, the single-document file picker
and the settings backup/restore pair: two of them produced reports nobody read, and the file picker only
ran on the handset, which is the one place they are not when operating from the dashboard. Nothing was
left half-removed.

- **Android.** `SecurityAuditTool.kt` and `SettingsBackupTool.kt` are deleted, along with the whole
  `com.example.utility.security` and `com.example.utility.backup` packages. The tools screen loses
  "Run local security audit", "Create settings backup", "Restore settings backup", "Select a file to
  share" and "Restore backed-up geofences" — that last one only ever read the list a backup had saved,
  so it had no other way to be filled. `audit()`, `backup()`, `pickFile()`, `restore()`, `decodeFence()`
  and `FeatureBridge.share()`/`backupReport()` are gone, and activity-result codes 41 and 42 no longer
  exist. `DeviceCommandRouter`, `RemotePolicy.tools` and `ReportSchedule.tools` lost the three names, so
  the report-only tools left to schedule are phone status and a nearby scan.
- **Backend.** `request_audit`, `request_files` and `request_backup` are no longer accepted actions, the
  rule whitelist is `audio, geofence, location, photo, screenshot, scan`, and `POST /api/device/files`
  refuses an `audit` or `backup` kind. The `reviewed` result state stays in the protocol: an older
  installed build can still report it, and existing ledger rows keep their history.
- **Upgrading in place.** A phone that had the audit or backup report switched on keeps that choice in
  its own preferences, so `ReportSchedule.enabled` now ignores a stored name this build does not have
  instead of falling through and uploading a status report under the old label. Turning any schedule off
  and on again clears the stale entry.
- **Dashboard.** Quick-action buttons, rule checkboxes and the "Audit and backups" tab are removed.
  Files an older build already uploaded stay listed and downloadable in **Files** until the retention
  window deletes them.
- **Still there, deliberately.** "Choose / browse a folder" and "View / export / delete local files",
  because those serve the folder vault rather than the removed picker. No consent control, activity
  notice or Stop path changed.
