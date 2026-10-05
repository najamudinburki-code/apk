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
