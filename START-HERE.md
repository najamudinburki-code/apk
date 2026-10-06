# Install the System Health app (0.4.0)

You have a compiled, signed **test APK**. You can install it directly; Android Studio is optional.

**Editing locally?** Use **LOCAL-DEVELOPMENT.md** and the SETUP-DEV / START-DEV / CONNECT-PHONE shortcuts. Choose the separate Android `dev` variant for laptop testing; the installation instructions below apply to the existing Render-connected APK.

## Install or update in three steps

Open [Install System Health](https://system-health-install.jkrick33.chatgpt.site) on your Android phone, or scan the QR shown there. This installation page is private to your ChatGPT account; sign in with that account if prompted.

1. Tap **Download latest APK**, then open the downloaded file and tap **Install** or **Update**. Android may ask you to allow installation from that browser/file manager. Version 0.4.0 uses the same signing certificate as the previous delivered test APK and keeps your existing enrollment and settings.
2. Open System Health and follow **Connect → Permissions → Check**. Connection is automatic. Select the tools to prepare, allow the missing permissions, then review the connection check. Granted permissions are skipped, and missing permissions can be enabled later. The saved wizard stage and tool choices are kept when you leave setup.
3. Tap **Finish — open home**. Check the server result, monitoring state and **Last successful upload**. That timestamp is recorded only after the server acknowledges an upload. New installations start health monitoring after permitted automatic enrollment and status-notification permission. Existing stopped monitoring remains stopped; tap Start when ready.

If your backend health check already shows `api_version: 4` and `automatic_enrollment: true`, **no Render backend/dashboard redeployment is needed** for this Android update. The installation page is hosted separately from your existing dashboard; your data still goes to the configured Render backend.

## Front camera and retained tools

Open **Device tools, location and shared files**. The photo tool now defaults to **Front camera**. **Rear camera** remains available in the camera selector; your selection is remembered. A missing chosen lens produces an error and does not silently substitute the other camera. Both local photo consent and reviewed dashboard photo requests show which lens is used.

All prior tools and function names remain. The home screen groups configuration and reader diagnostics under **Advanced settings**. Start, Stop, app-text sharing, guided setup and Device tools stay easy to reach. Sensitive requests still need phone review, and camera/microphone/screenshot controls remain visible.

## Test this upgrade on your phone

- Install as an update and confirm your existing phone appears under its original identity.
- Complete the wizard, deny one optional permission, and confirm the remaining setup works. Reopen **Guided setup / permissions** and confirm allowed permissions are skipped.
- Run **Check connection** and confirm both a reachable server and accepted phone credentials. Disable internet briefly, retry, and confirm the app reports a connection problem instead of claiming it is connected.
- Start monitoring and confirm the successful-upload timestamp changes after the first sample. Compare the dashboard sample time.
- Take an approved front-camera test photo. Confirm it is from the front lens in dashboard Files. Switch to Rear and repeat to check the retained option.
- Expand **Advanced settings** and confirm manual connection settings, accessibility settings, notification access, reader reconnect and detailed status are still present.
- Confirm Stop ends sharing and the active notices clear. Quiet, grouped notifications remain visible; phone/OEM presentation can vary.

This is a signed test APK. Build, lint and unit checks pass, but no physical phone is connected here. Actual sensor, permission, notification and installation behavior still needs the phone checks above.

The sections below describe first-time deployment. Skip them if your current version-4 backend/dashboard already work.

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

Open **https://apk-obeb.onrender.com/health**. The matching backend returns:

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
2. Install and **open** the app. Complete or skip the permission checklist, then return to the app and keep it open while it connects. If Render is waking up, the app retries.
3. Allow Android's status-notification permission in setup. Health monitoring then starts automatically and sends its first sample after enrollment. If you decline or skip that permission, enable notifications later and tap **Start System Health Monitor**.
4. Open your existing dashboard and sign in. The new phone appears automatically; there is no **Approve phone** step for this APK.

Installing the file alone does not start the app. Android permissions and the phone user's screen/notification sharing decision still apply. Camera, microphone, location and screenshots use their existing controls.

An update preserves an existing working enrollment. A pending phone using the previous automatic version can connect using the same ID and token after upgrading. Disabled or previously declined phones remain blocked. Each fresh installation gets its own identity, so phones do not merge their records. **Connect automatically** retries connection; **Advanced connection settings** retains custom/manual connections. **Stop** cancels automatic restart on the next app opening; tap Start to resume.

This APK is an invitation to your server: anyone given a copy can enroll a phone. It includes no dashboard password or database credentials. To stop future automatic joins, optionally set `AUTO_ENROLLMENT_KEY_HASH=disabled` on the backend and redeploy; existing enrolled phones keep working. Leave this setting absent for the bundled invitation to work. Older APKs without the invitation can still use the retained legacy approval/manual enrollment workflow.

### How it connects to your current server and dashboard

| Component | Connection |
| --- | --- |
| Android app | Normal `https://apk-obeb.onrender.com` URL from `android/app/build.gradle.kts`; separate `dev` variant uses the laptop; unique credentials generated for each installation |
| Render backend | Verifies the APK invitation, activates that phone, and authenticates its later uploads using its own device token |
| Neon database | Backend reads/writes using the existing `DATABASE_URL` stored in Render; the APK does not connect directly to Neon |
| Browser dashboard | Built with `VITE_SERVER_URL=https://apk-obeb.onrender.com`; signs in to the same backend and receives its device records/events |

The dashboard's website address can differ from the backend address. Keep your existing dashboard URL; no dashboard URL is needed in the phone app. Future phones using this APK connect to the same backend and appear in the same dashboard after their first launch. Your computer does not need to stay on.

If you change the backend's URL later, update the normal `API_BASE_URL` in `android/app/build.gradle.kts` and rebuild the APK for new installs; update `VITE_SERVER_URL` and rebuild the dashboard too. Existing phones can switch using Advanced connection settings with an enrollment valid on the new server. Keeping the same database retains existing identities and history. If you change only the dashboard's domain, update the backend's `DASHBOARD_ORIGIN` to that exact HTTPS origin; the APK's backend address does not change.

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
| Remote requests | Send a request in dashboard **Requests**; its status advances pending → delivered → completed as the phone works through it | Request result, then the corresponding output panel |

Phone status, security audit and settings backup run silently. Camera, microphone, screenshot, location and file picking need the visible tools screen, so those requests open it on the phone; Android can refuse that launch while the screen is locked, and the request then reports a failure you can re-send. Leaving the tools screen stops microphone recording. Screen capture still uses Android's per-session approval.

“Completed” on a request means its phone action finished or its output was queued; read the result detail and check the output panel to confirm delivery. Reports can be reviewed/exported locally without sharing them.

Uploads and request checks run about every 10 seconds while monitoring is on — every 5 seconds when items are already waiting, and longer only after a connection failure. The phone retains pending uploads across app restarts, tied to their original server/device configuration. Free hosting can take time to wake; leave monitoring on and refresh the dashboard.

Limits are displayed in the app: 4 MiB per file, 50 MiB of local saved tool files, 200 pending tool items, and 100 MiB / 500 cloud files per device. When the 200-item queue fills, the phone drops its oldest health or status sample to make room rather than discarding what it cannot remake. An item the server rejects permanently, or one that fails 40 times, moves to the app's internal unsent folder instead of blocking later uploads. Export or delete old local files when the phone vault is full; delete old cloud files from the dashboard when its quota is full. The server also deletes cloud history and uploaded files older than its retention window (30 days unless `RETENTION_DAYS` says otherwise), so export anything you want to keep. **Clear pending uploads** cancels unsent tool items while preserving local saved files.

## What was checked

- Backend: `npm test` → 31 passing checks against SQLite. They cover invitation-based joining and concurrent join retries, upgrade of pending phones, disabling future joins without disconnecting existing phones, legacy registration and approval/decline, token isolation, duplicate approval, expiry renewal, disabled phones, auth, rate limits, persisted telemetry, duplicate retries, 4 MiB files, downloads, the pending → delivered → completed request flow, token rotation that revokes the old credential, the retention sweep, and server restarts. The PostgreSQL code path only runs when `TEST_DATABASE_URL` points at a live database, which was not available for this run.
- Android: 24 unit checks passed (request routing, upload cadence, app-capture scope, camera selection, enrollment identity, permission plan, readiness). `assembleDebug` and `assembleRelease` both build, and Android's blocking lint check (`lintVitalRelease`) passes; the release build now runs R8 with `proguard-rules.pro` and produces `app-release-unsigned.apk`, so it must be signed before use. Package `com.example.systemhealth`, version `0.5.0`, Android 8+.
- Dashboard: production build passes (`npm run build`).
- Not checked in this run: anything that needs a physical phone, and browser-level dashboard checks. No device was connected.

Physical camera, microphone, GPS, Bluetooth, accessibility behavior, OEM battery restrictions and reboot behavior still need testing on your Camon 20. The debug APK installs directly; the unsigned release APK needs signing first.
