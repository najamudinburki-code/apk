Historical snapshot: this review describes the 0.2/0.3 source layout, including `SyncManager`,
`NetworkMonitor` and a `MainActivity` nested inside `CoreService.kt`. None of those are current —
the phone has one HTTP path in `FeatureBridge.kt` and `MainActivity` has its own file. Read
`docs/FEATURE-STATUS.md`, `docs/CHANGES.md` and `../DEV-HANDOFF.md` for the live architecture.

# Connector review

The applicationId and namespace are com.example.systemhealth. Source packages retain their names; imports and fully qualified manifest names connect them. MainActivity is inside CoreService.kt. ParentalCapture is inside AccessibilityHelperService.kt; do not add duplicate classes.

CoreService owns SyncManager/NetworkMonitor, starts health sampling once, registers the capture consumer after sync initialization, and clears the consumer/consent on shutdown. SQLite/network work uses IO; service callbacks and consent controls use main. Status UI checks CoreService, both accessibility readers, and NotificationReaderService through ServiceManager.

The visible consent dialog supports All supported apps automatically or selection by installed app name and discloses server storage. ParentalCapture requires a consumer, an explicitly approved automatic/selected scope, enablement and a visible notification. Consent expires on process death. Stop disables capture. Android Settings grants remain separate; Android binds the readers after approval.

AutomaticEnrollment uses the fixed public Render URL and separate random credentials per installation. SyncSettingsStore stores a pending/approved marker alongside Keystore-encrypted settings. Registration with the APK's bundled invitation activates the phone immediately; the backend contains only the invitation hash. Registration without an invitation retains the legacy pending/approval workflow. The visible activity connects automatically, then starts health monitoring after notification permission. Upgrading a pending phone preserves its identity; rejected/disabled phones stay blocked. The dashboard listens for enrollment changes so a newly joined phone appears without a manual refresh. Stop cancels deferred startup, and existing saved manual configurations keep their prior behavior. Advanced manual settings remain.

AccessibilityHelperService checks consent before traversal and delayed delivery, skips password nodes/events, bounds traversal/text, debounces for 500 ms, and cancels pending work during teardown. ScreenMonitorService retains field extraction/callbacks, uses public SDK APIs, redacts passwords, checks both approved-package preferences and the shared gate, bounds traversal, and releases nodes. Both report lifecycle status.

NotificationReaderService processes on main, falls back from blank EXTRA_TEXT to EXTRA_BIG_TEXT, skips blank/system/self notifications, checks consent, bounds text, and removes pending work at disconnection/destruction. Payload types are screen_text, screen_fields, and notification.

SyncManager authenticates the enrolled device, queues records in SQLite, emits data:receive, and removes records after persistence acknowledgement. The backend stores events and emits data:received to authenticated dashboards. The dashboard displays payload details and keeps the latest system_health independently of the latest screen/notification event.

Manifest registrations cover the activity, CoreService, boot/job components, accessibility readers, notification reader, location service, and geofence receivers. BIND_* permissions protect service binding. Existing utility permissions are declared; runtime grants are still required. Hardware is optional. Private data is excluded from Android backup/transfer.

JobIntentService remains as requested. It is deprecated and cannot bypass background-start restrictions or force-enable system-bound services. Alternative sync files remain under variants, outside the active source set, because they use a different protocol.

Uploads are at least once; lost acknowledgements can cause duplicates. Queues/history have no retention cap. Online status reflects short upload connections. Android may delay timing, recreation, and reboot work; five-minute delivery is not guaranteed under idle/force-stop restrictions. Unfinished workflows are in FEATURE-STATUS.md. Device behavior has not been verified.
