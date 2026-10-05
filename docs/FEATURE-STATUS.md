# Existing feature status

Every existing Android Kotlin file and function name remains. All active Android sources, including ScreenMonitorService, participate in the build. Alternative implementations and original dashboard components are retained.

| Feature | Current state | Remaining work |
| --- | --- | --- |
| System health | App → queued sync → authenticated backend → dashboard connected | Real-device/power/reboot testing |
| Screen field monitor | Supported APIs, password redaction, consent UI, package gate, sync connected | Accessibility grant and device tests |
| Accessibility text helper | Consent/debounce/traversal and sync connected | Enable one screen reader; device tests |
| Notification reader | Consent UI, Settings shortcut, lifecycle/status and sync connected | Notification access grant and device tests |
| Service manager / JobIntentService | Existing methods, job declaration, lifecycle callbacks and status retained | Decide optional reconciliation scheduling |
| Location/geofencing | Full source/models compile; service and receivers registered | Visible permission/setup UI, initialize TrackingSink, connect feeds |
| Camera | Capture/lifecycle methods retained; permission declared | Visible capture UI, permission flow, storage/upload design |
| Audio | Recording/lifecycle methods retained; permission declared | Visible controls, permission flow, playback/storage/upload |
| Environment scanner | Wi-Fi/Bluetooth code and permissions retained | Scan UI, runtime permissions, result display/feed |
| Security audit / settings backup | Original utilities compile and remain | User controls and export/import/results |
| Dashboard maps/files/controls | Original component files retained | Server feeds, Android transfer/command handlers, routing |
| Alternative sync | Original files retained under variants | Choose one protocol before integrating an alternative |

Camera/audio/scanning utilities require a visible activity and permissions. They are not invoked automatically by monitoring or server commands. No runtime permissions are automatically granted.

Password text is intentionally redacted. The unsupported raw-password recovery attempt was replaced by supported ordinary-text/metadata handling; its monitoring functions and callback remain.

Development builds are ready to compile. Production still needs phone testing, your signing key, retention/deletion, token rotation/revocation, HTTPS hosting, and completed optional workflows.
