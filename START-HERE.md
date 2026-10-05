# Install the completed System Health app (0.3.1)

You have a compiled, signed **test APK**. You can install it directly; Android Studio is optional.

## 1. Update your existing GitHub repository

Extract **Render-deployment.zip**. Open the inner folder that contains `backend`, `dashboard` and `docs`.

Open your existing GitHub repository `najamudinburki-code/apk`. Choose **Add file → Upload files**. Drag the extracted folders and root files into the browser upload area. Commit the update. Replace the files at the same paths; keep `backend` and `dashboard` directly at the repository root.

The new server needs `backend/installation.cjs`, `backend/enrollment.cjs`, `backend/features.cjs`, `backend/feature-store.cjs` and the updated server files. The dashboard needs `components/EnrollmentPanel.jsx`, `components/FeaturePanels.jsx` and updated `App.jsx`. Uploading the complete extracted contents is easier than picking individual files.

## 2. Deploy both existing Render services

Your existing backend URL is **https://apk-obeb.onrender.com**.

For the backend Web Service, keep:

- Runtime: **Node**
- Root Directory: **backend**
- Build Command: **npm ci**
- Start Command: **npm run start:render**

Keep your existing database URL, dashboard username/password, JWT secret and dashboard origin in Render's Environment page. These values are kept on the server, outside the APK.

Choose **Manual Deploy → Deploy latest commit** if auto-deploy has not started. Wait for **Live**.

Open **https://apk-obeb.onrender.com/health**. The live backend was reachable during this update, but returned the older `{"ok":true}` response. The new code has not been deployed to your Render account. After you deploy this bundle, the updated backend returns:

```json
{"ok":true,"api_version":4,"automatic_enrollment":true}
```

Deploy your existing dashboard Static Site too. Its existing build command and publish directory remain correct. `VITE_SERVER_URL` should be `https://apk-obeb.onrender.com`. Open your existing dashboard address and sign in. You will see **Device tools** with text, location, files, requests, scans, reports and logs.

The new database tables are created automatically. Existing enrollment and health history stay in the same database.

## 3. Install SystemHealth-debug.apk on your phone

Download the APK to your phone, open it, and approve Android's install prompt. Allow installation from your browser or file manager when Android asks.

**If Android says “App not installed” or reports a conflicting package:** your previous APK probably used a different signing key. An APK can update an installed app only when their signing keys match.

To keep the existing phone configuration, extract **APK-integrated.zip**, open **APK-complete/android** in Android Studio, and build using the same computer and signing configuration that produced your previous APK. Choose **Build → Generate App Bundles or APKs → Generate APKs** (menu wording varies).

Alternatively, use a fresh automatic setup: uninstall the previous app, then install this APK. Uninstalling clears that app's local settings and pending uploads. The new installation generates a new phone ID for automatic connection; the old phone's server history stays under its previous ID. No token needs to be copied for this automatic setup.

## 4. Automatic phone connection — no typed configuration or dashboard approval

The APK contains your public server address: **https://apk-obeb.onrender.com**, plus an invitation used only to register a phone. The matching invitation hash is already in the backend bundle. **No new Render environment variable is required.** On first launch, the app generates its own random phone ID and separate random device token, saves them encrypted, and connects automatically. No URL, ID, token or dashboard approval needs to be entered for this APK.

1. Deploy both updated Render folders as described above.
2. Install and **open** the app. Keep it open while it connects. If Render is waking up, the app retries.
3. Allow Android's notification permission when asked. Health monitoring then starts automatically and sends its first sample. If you decline the permission, enable notifications later and tap **Start System Health Monitor**.
4. Open your existing dashboard and sign in. The new phone appears automatically; there is no **Approve phone** step for this APK.

Installing the file alone does not start the app. Android permissions and the phone user's screen/notification sharing decision still apply. Camera, microphone, location and screenshots use their existing controls.

An update preserves an existing working enrollment. A pending phone using the previous automatic version can connect using the same ID and token after upgrading. Disabled or previously declined phones remain blocked. Each fresh installation gets its own identity, so phones do not merge their records. **Connect automatically** retries connection; **Advanced connection settings** retains custom/manual connections. **Stop** cancels automatic restart on the next app opening; tap Start to resume.

This APK is an invitation to your server: anyone given a copy can enroll a phone. It includes no dashboard password or database credentials. To stop future automatic joins, optionally set `AUTO_ENROLLMENT_KEY_HASH=disabled` on the backend and redeploy; existing enrolled phones keep working. Leave this setting absent for the bundled invitation to work. Older APKs without the invitation can still use the retained legacy approval/manual enrollment workflow.

### How it connects to your current server and dashboard

| Component | Connection |
| --- | --- |
| Android app | Fixed `https://apk-obeb.onrender.com` URL in `AutomaticEnrollment.kt`; unique credentials generated for each installation |
| Render backend | Verifies the APK invitation, activates that phone, and authenticates its later uploads using its own device token |
| Neon database | Backend reads/writes using the existing `DATABASE_URL` stored in Render; the APK does not connect directly to Neon |
| Browser dashboard | Built with `VITE_SERVER_URL=https://apk-obeb.onrender.com`; signs in to the same backend and receives its device records/events |

The dashboard's website address can differ from the backend address. Keep your existing dashboard URL; no dashboard URL is needed in the phone app. Future phones using this APK connect to the same backend and appear in the same dashboard after their first launch. Your computer does not need to stay on.

If you change the backend's URL later, update `AutomaticEnrollment.SERVER_URL` and rebuild the APK for new installs; update `VITE_SERVER_URL` and rebuild the dashboard too. Existing phones can switch using Advanced connection settings with an enrollment valid on the new server. Keeping the same database retains existing identities and history. If you change only the dashboard's domain, update the backend's `DASHBOARD_ORIGIN` to that exact HTTPS origin; the APK's backend address does not change.

For screen text and notifications:

1. Tap **Approve app text and notifications**.
2. Leave **All supported apps automatically** selected, tap **Continue**, read the disclosure, then tap **Approve sharing**. No package names need to be typed. Newly installed supported apps are included automatically. You can still use **Choose apps by name** to restrict sharing.
3. Enable **one** of the two System Health screen readers in Accessibility settings. Enable **Notification Reader** in Notification access settings.
4. If Android shows “Restricted settings”, open the System Health app-information page, use its menu's **Allow restricted settings** option, then return to the access setting.
5. Open WhatsApp, Chrome or another supported app and generate an ordinary visible text change or a new message notification. Look in the dashboard's **Text and notifications** panel.

Screen text and incoming message notifications are different sources. Some apps expose incomplete screen text; Android may hide sensitive notification contents. This app cannot supply text the operating system or target app does not expose. Password fields are redacted. Automatic scope includes ordinary app screen text and notifications; the existing exclusions for this app itself and Android system components remain. Camera, microphone and screenshots still use their separate controls. Your automatic/selected-app choice is saved. Sharing approval expires when the app process ends, so approve it again after a restart without typing package names.

## 5. Use the other tools

Open **Device tools, location and shared files** in the phone app.

| Tool | What to do | Where to see the result |
| --- | --- | --- |
| Camera | Take and share photo; allow Camera permission | Dashboard **Files** |
| Microphone | Start recording; allow Microphone; keep the tools screen open; tap Stop | **Files**, with audio playback/download |
| Screenshot | Approve Android's screen-sharing dialog; open the selected screen during the 5-second countdown | **Files** |
| Location | Start sharing location; allow precise location; enable GPS | **Location**, with accuracy and sample time |
| Geofences | Add a name, coordinates and radius; grant Location **Allow all the time**; start location sharing | **Location → Geofence events** |
| Nearby scan | Enable Wi-Fi, Bluetooth and Location; run one scan and keep the tools screen open | **Nearby scans** |
| Security audit | Run local audit; review the redacted report; choose Share report or Export | **Audit and backups**, if shared |
| Settings backup | Create a backup; choose Export or Share report | **Audit and backups**, if shared |
| Restore | Select an exported backup; approve text sharing again; use Restore backed-up geofences to register saved boundaries | Restored automatic/selected-app scope and boundaries |
| File manager | Choose a file or browse an Android-approved folder; confirm which file to upload | **Files** |
| Remote requests | Send a request in dashboard **Requests**, then approve it in the phone's **Review dashboard requests** screen | Request result, then the corresponding output panel |

Camera and microphone require a visible activity. Leaving the tools screen stops microphone recording. Screen capture uses Android's per-session approval and a visible notification. A remote request never silently starts capture.

“Completed” on a request means its phone action finished or its output was queued; read the result detail and check the output panel to confirm delivery. Reports can be reviewed/exported locally without sharing them.

Tool uploads retry every 30 seconds while monitoring is on. The phone retains pending uploads across app restarts, tied to their original server/device configuration. Free hosting can take time to wake; leave monitoring on and refresh the dashboard.

Limits are displayed in the app: 4 MiB per file, 50 MiB of local saved tool files, 200 pending tool items, and 100 MiB / 500 cloud files per device. Export or delete old local files when the phone vault is full; delete old cloud files from the dashboard when its quota is full. **Clear pending uploads** cancels unsent tool items while preserving local saved files.

## What was checked

- Android APK compilation and Android lint: passed, with no lint errors. Nonblocking SDK/style warnings remain.
- APK signing and package/version metadata: verified (`com.example.systemhealth`, version `0.3.1`, Android 8+).
- Backend: 28 passing checks against both SQLite and a local PostgreSQL-compatible test engine. Tests cover immediate invitation-based joining, concurrent join retries, upgrade of pending phones, disabling future joins without disconnecting existing phones, legacy registration and approval/decline, token isolation, duplicate approval, expiry renewal, disabled phones, auth, persisted telemetry, duplicate retries, 4 MiB files, downloads, requests, and server restarts.
- Dashboard: production build and browser checks passed for immediate automatic joining without approval, legacy approval/decline, login, manual enrollment, readable text, safe rendering, file preview/download, request submission, location map, reports, logs, and mobile layout.
- Android unit checks: 6 passed, covering automatic/selected app scope and unique per-installation IDs and 256-bit tokens.
- All 22 active Kotlin files from version 0.3.0 remain (23 active Kotlin files now); no previous Kotlin function names were removed. Original archives and alternative source implementations remain in the full source ZIP.

Physical camera, microphone, GPS, Bluetooth, accessibility behavior, OEM battery restrictions and reboot behavior still need testing on your Camon 20. The delivered APK is a debug/test build, not a Google Play release.
