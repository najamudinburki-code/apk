# System Health 0.3.0

A buildable Android app, authenticated Node backend, and responsive web dashboard.

Start with **START-HERE.md**. The ready APK is supplied separately as **SystemHealth-debug.apk**.

- Open `android/` in Android Studio to build the app.
- Deploy `backend/` to the existing Render Node Web Service.
- Deploy `dashboard/` to the existing Render Static Site.
- PostgreSQL on Neon keeps enrollment, events, shared files and requests persistent. Local development also supports SQLite.

App text/notification sharing defaults to **All supported apps automatically**. No package-name entry is required; new supported apps are covered as they generate events. **Choose apps by name** remains available. The choice is saved and backed up, while active sharing requires approval and Android access permissions.

The APK contains the public Render server address and generates separate encrypted credentials for each installation. New phones need one **Approve phone** click in the dashboard, with no typed phone configuration. Existing enrollments, advanced manual settings and Stop behavior remain.

The Android tools now have visible controls and upload paths for camera, microphone, one-shot screenshots, location/geofences, nearby Wi-Fi/Bluetooth scans, app-data audits, settings backups/restoration and explicitly selected files/folders. The dashboard displays readable text, maps, files, requests, scans, reports and received logs. Remote requests require review on the phone.

All previous Kotlin files and function names remain. Original archives and alternative implementations remain under `archives/` and `variants/`. Older prototype dashboard component files are retained; `FeaturePanels.jsx` implements the integrated authenticated tool panels.

Builds and integration checks passed. Real-device hardware, accessibility, power and reboot behavior require phone verification. This bundle supplies a debug/test APK; production publishing needs your own release signing and device validation.

See `docs/FEATURE-STATUS.md`, `docs/CHANGES.md`, and `docs/VALIDATION.md` for scope and verification.
