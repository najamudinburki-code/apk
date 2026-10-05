# Connected feature status — 0.3.1

| Feature | Active path | Device condition |
| --- | --- | --- |
| Automatic enrollment | Fixed public URL + bundled invitation → generated per-installation credentials → automatic activation → encrypted saved enrollment | Deploy matching backend, open app and allow notifications; no typed URL/ID/token or dashboard approval |
| System health | CoreService → persistent SyncManager queue → authenticated server → health cards | Enroll and start monitoring |
| Screen text/fields | Automatic/all-app or selected-app approval → one accessibility reader → queue → readable dashboard panel | No typed IDs; grant access and approve scope; target app must expose text; passwords redacted |
| Notifications | NotificationReader → approved automatic/selected scope → queue → readable text panel | Grant notification access; Android may redact contents |
| Service management | Lifecycle status callbacks, Start/Stop, JobIntentService reader reconciliation | Reconnect enabled readers; system owns accessibility binding |
| Camera | Visible phone action → CameraController → saved file queue → dashboard Files | Camera permission and visible activity |
| Audio | Start/Stop → AudioRecorder → finalized AAC/M4A chunks → queue → playback/download | Microphone permission; ends when activity pauses |
| Screenshot | Android consent → one-shot projection foreground service → JPEG queue → Files | Per-session Android permission; secure windows remain protected |
| Location | Application-initialized TrackingSink → location FGS → authenticated queued reports → map | Precise location/GPS and explicit start; Stop monitoring ends sharing |
| Geofences | Add/remove/list/restore controls → original geofence APIs → sink → boundary history | Fine/background location; explicit location sharing |
| Environment scan | Visible scan control → EnvironmentScanner → report queue → Nearby scans | Location, Wi-Fi, Bluetooth enabled; required runtime permissions |
| Audit | Original redacted own-app audit → local review → export or explicit file share | Own private app data; no other-app sandbox bypass |
| Settings backup | Original backup utility + app selection/geofences → export/share/restore controls | Credentials and active consent excluded; boundaries restored with separate approval |
| Files | Android file/folder picker → local vault/export/delete → authenticated cloud upload/download/delete | Only selected accessible documents; storage quotas visible |
| Dashboard controls | Authenticated request queue → phone review → action result → output panels | Phone monitoring on; no automatic covert captures |
| Logs | Received event history with type/search filtering | Last 100 events; latest location also loaded independently |
| Alternative source | Original archives, variants and prototype components retained | Active app uses the integrated protocol above |

All source files compile. Android controls are connected to the retained utilities. Software build/integration checks passed; physical sensor, OEM power/reboot and accessibility behavior require phone tests. A compiled APK does not guarantee that another app exposes every field or message.
