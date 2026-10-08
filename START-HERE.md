# Install the System Health app (0.5.0)

You have a compiled, signed **test APK**. You can install it directly; Android Studio is optional.

**Editing locally?** Use **LOCAL-DEVELOPMENT.md** and the SETUP-DEV / START-DEV / CONNECT-PHONE shortcuts. Choose the separate Android `dev` variant for laptop testing; the installation instructions below apply to the existing Render-connected APK.

## Install or update in three steps

Open [Install System Health](https://system-health-install.jkrick33.chatgpt.site) on your Android phone, or scan the QR shown there. This installation page is private to your ChatGPT account; sign in with that account if prompted.

1. Tap **Download latest APK**, then open the downloaded file and tap **Install** or **Update**. Android may ask you to allow installation from that browser/file manager. This 0.5.0 test APK is the debug build, so on the computer that built it it keeps the same signing certificate as the previous delivered test APK and your existing enrollment and settings survive. A release build is unsigned until you give it your own key (see `docs/ANDROID-BUILD.md`); never reuse the debug key for a published app.
2. Open System Health and follow **Connect this phone → Choose what this phone may do → Check and start**. Connection is automatic. Each permission line now says which tool needs it and what stays off without it, so you know why before Android asks. Select the tools to prepare, allow the missing permissions, then review the connection check. Granted permissions are skipped, and a permission you declined says how to change your mind later. The saved wizard stage and tool choices are kept when you leave setup.
3. Tap **Finish — open home**, or **Start System Health Monitor** on the last step. Check the server result, monitoring state and **Last upload the server accepted**. That timestamp is recorded only after the server acknowledges an upload. New installations start health monitoring after permitted automatic enrollment and status-notification permission. Existing stopped monitoring remains stopped; the home screen puts **Start System Health Monitor** as its one main button when ready.

If your backend health check already shows `api_version: 4` and `automatic_enrollment: true`, **no Render backend/dashboard redeployment is needed** for this Android update; the phone talks to the same routes it did in 0.4.0. Redeploy the bundled backend and dashboard when you want the new dashboard tabs (Phone rules, the Requests ledger, Activity log, Export) or the optional `APP_RELEASE_VERSION` update notice. The installation page is hosted separately from your existing dashboard; your data still goes to the configured Render backend.

## Front camera and retained tools

Open **Device tools, location and shared files**. The photo tool now defaults to **Front camera**. **Rear camera** remains available in the camera selector; your selection is remembered. A missing chosen lens produces an error and does not silently substitute the other camera. Both local photo consent and reviewed dashboard photo requests show which lens is used.

The home screen groups configuration and reader diagnostics under **Show details and diagnostics**. Start, Stop, app-text sharing, guided setup and Device tools stay easy to reach. The local security audit, the settings backup/export/restore pair and the single-document file picker were removed on 2026-10-07, so their buttons, dashboard panel and server actions are gone; everything else keeps its function. Screenshot, location start and geofence approval still need the visible tools screen, because that is where Android asks for consent; camera and microphone answers to dashboard requests are captured headless inside monitoring that you started, with Android's own indicator showing. Nothing in this app captures without one of those consented paths.

## What changed on the phone's screens (8 October 2026)

The app does the same things it did; the way you reach them is easier to read.

- The home screen now answers, in order: is this phone connected, is monitoring on, is anything waiting to upload, does something need me — and then shows **one** main button for that situation. The two Stop buttons stay on that same first card whenever there is something to stop.
- Anything that needs you appears in a red-edged **Needs your attention** card: a camera stream in progress, items that could not be sent, a version update, or Android refusing to keep monitoring running.
- Long technical text moved into **Show details and diagnostics**, which stays available and keeps the exact wording for reading out loud.
- Device tools is grouped into cards by purpose — Dashboard requests, Camera/microphone/screen, Location and places to watch, Nearby scan, Reports that repeat, Shared files, Notices and privacy — and a search box at the top narrows the page by ordinary words. Searching can never hide a Stop button, a state row, or the way back. ✅ checked on the phone 2026-10-08 with `photo` and `stop`.
- Setup explains why each permission is needed before Android asks, marks all three steps as Done / You are here / Up next, and tells you what to do if you declined one. Not yet seen on a phone — see the note under the checklist below.
- The screen follows your phone's light or dark setting and grows with your font size. The home screen was photographed on the Camon 20 in light and dark, at normal and enlarged (1.3×) text, and Device tools in dark at both. Controls carry spoken labels and card titles are announced as headings, but **TalkBack itself has not been run**, so the reading order is designed rather than tested.
- Nothing is called finished just because you tapped a button. A file saved only on the phone says **Saved on this phone**; the same file is reported as received once the server accepts it, and **Files saved on this phone** shows which of the two each one is.

`docs/UI-BEFORE-AFTER.md` lists every old button and where it is now.

## New in this version on the phone

- **Dashboard rules.** The dashboard can only make this phone do *less*: a different health sample cadence, or switching remote tools off. It can never grant an Android permission, turn monitoring on, or hide a capture. The rules are shown on the phone's home status and tools screen, and the phone reports back the rule set it is actually obeying. Point the phone at a different server and the old rules are cleared.
- **Scheduled reports.** On the tools screen you can have phone status or a nearby scan repeat every 15, 30, 60 or 240 minutes. They run inside normal monitoring, so they stop the moment you tap Stop, and no camera, microphone or screenshot tool can be scheduled.
- **Update notice.** When the backend advertises a newer APK, the home screen says which version and where to get it. It only ever mentions a strictly newer version than the one installed.

## Test this upgrade on your phone

- Install as an update and confirm your existing phone appears under its original identity. ✅ 2026-10-07
- Complete the wizard, deny one optional permission, and confirm the remaining setup works. Reopen **Guided setup / permissions** and confirm allowed permissions are skipped. ✅ 2026-10-07
- Run **Check connection** and confirm both a reachable server and accepted phone credentials. Disable internet briefly, retry, and confirm the app reports a connection problem instead of claiming it is connected. Partial — the reachable-server half is checked; the airplane-mode half is not.
- Start monitoring and confirm the successful-upload timestamp changes after the first sample. Compare the dashboard sample time. ✅ 2026-10-07
- Take an approved front-camera test photo. Confirm it is from the front lens in dashboard Files. Switch to Rear and repeat to check the retained option. Partial — photos arrive from the front lens selection; nobody has opened one to confirm the framing, and Rear has not been tried.
- Live camera view, once you build this source into an APK: leave the new switch off and send the request —
  it must come back **declined** naming the allowance, not stream. Then switch it on and send it, and confirm
  the frames in **Files** are legible and upright, from the lens the selector shows, that the notice line and
  Android's indicator appear for the whole run, that the request turns **completed** only when a frame has
  arrived, that **Stop the live camera view now** ends it in the same second, that a second request works
  after a stop, and that it stops itself at 120 seconds. Not tested — no handset has run this code.
- Expand **Show details and diagnostics** and confirm manual connection settings, accessibility settings, notification access, reader reconnect and detailed status are still present. ✅ 2026-10-07 as **Advanced settings**; the fold holds the same controls with new wording, so it needs a second look
- Send a **Phone rules** change from the dashboard (for example switch Photo off) and confirm it appears in the home-screen rule summary, that a later photo request comes back **declined** naming the rule, and that the dashboard shows the rule set the phone reported. Send the rules back on and confirm the photo works again without touching Android permissions. ✅ 2026-10-07, both directions
- Switch on one **Scheduled report** at the shortest cadence, keep monitoring on, and confirm it arrives again without you tapping anything — then tap Stop and confirm it stops with monitoring. ✅ 2026-10-07 (status and scan both fired on their own; the audit and backup reports were taken out of the app afterwards)
- If your backend sets `APP_RELEASE_VERSION`, confirm the home screen mentions only a version newer than the installed one, and shows nothing when the advertised version is the same or lower. ✅ 2026-10-07, against a deliberately advertised version
- Confirm Stop ends sharing and the active notices clear. Quiet, grouped notifications remain visible; phone/OEM presentation can vary. Partial — reported working on the Camon 20, but the server recorded no answer behind it, so the notice-clearing half is unconfirmed by measurement.
- Walk the rebuilt **Device tools** screen: search `photo`, then `stop`, then clear the box, and confirm a search never hides a Stop control or the way back, and that clearing it restores every card. ✅ 2026-10-08, both themes
- Queue every dashboard tool from the server and confirm the phone answers with what it really did. ✅ 2026-10-08 evening — status, a rules change, a photo (493 KB JPEG), a microphone recording (two M4A chunks) and an owner-approved screenshot (112 KB) each came back **completed** with a real payload behind them; a scan with Bluetooth off, a live view the rules keep off, an invented action name and a stop with nothing streaming each came back **refused or failed with the reason**. Location and watched areas were driven too: a location request reported **running** until a real GPS fix (accuracy 7.1 m) landed, and the boundary request that followed reached its **Allow/Decline dialog on the screen**, where the owner's own tap on *Decline* came back **declined** — "Declined the dashboard's area on the phone." — and the removing path cleared it. `docs/VALIDATION.md` has the table.
- Open both rebuilt screens in **light and dark** and with the system **text size enlarged**, and confirm nothing is unreadable, cut off or overlapping. ✅ 2026-10-08 — home screen and Device tools, each in light and dark, each at normal and 1.3× text; this is what caught the invisible primary button, the sub-readable alert text and the two cut-off strings. Guided setup has since been seen on the phone too, all three steps (light, normal text, portrait; step 3 also landscape); its dark mode and enlarged text still need looking at.
- Run **Guided setup and permissions** on the rebuilt screens, decline one optional permission, and confirm the refusal card explains that tool and links to the Android page; then leave setup halfway and come back. Partial ✅ 2026-10-09 00:17 — **all three steps** have now been rendered on the phone, each photographed top and bottom, step 3 in portrait and landscape, and step 3's "Last upload the server accepted: 9 Oct, 00:16:21" matched `event:927` on the dev backend to the second. **The refusal card and a deliberate "Finish later" exit are still unseen**, and only your finger can reach them: the screen is not openable from a computer. What was seen instead is that the wizard reopens on the step it was left on, not on "the first step that still needs you" as its own intro sentence claims.
- With TalkBack on, listen to the home screen top to bottom and confirm the reading order and the announced headings. ❌ Not done — spoken labels are in place but nobody has heard them.

This is a signed test APK. Build, lint and unit checks pass. Several of the checks above were re-run on a
physical TECNO Camon 20 (Android 14) on 2026-10-07 against a **local development backend**, and the results
are recorded in `docs/VALIDATION.md`; the ticks mark those. That session found and fixed two real capture
bugs, so it does not clear the release build: the hosted, release-signed APK with Render has still never
been run on a phone, and reboot/auto-start, long Doze idle and screen capture on this OEM remain untested.
Geofence entry, which used to sit in that same untested list, was measured on 2026-10-08 (see the checklist
above) — the app recorded real ENTER rows and cleared them on removal.

The same phone was driven again on the evening of **2026-10-08** and into the early hours of **9 October** against
the local backend to check the rebuilt interface: monitoring, server-confirmed deliveries, both themes, enlarged
text, the tools search, the whole dashboard request pipeline and all three guided-setup screens were photographed
or read out of the server, and **twelve defects** were found and fixed — nine in what the screen showed, one in
the connection status logic, one where a location request left the tools screen believing it was still busy, and
one where the last setup screen had no primary action at all. A **thirteenth** — the setup screens draw every
message, all-clear included, in the same colour they use for refusals — is recorded and deliberately left unfixed,
because closing it costs another reinstall and another tap on Start monitoring. The full list, with what a
computer cannot reach, is at the top of `docs/VALIDATION.md`. That pass changed nothing about the sentence
above: it was a debug build against a laptop, so the release artifact is still untested on a phone.

The sections below describe first-time deployment. Skip them if your current version-4 backend/dashboard already work.

## 1. Update your existing GitHub repository

Extract **Render-deployment.zip**. Open the inner folder that contains `backend`, `dashboard` and `docs`.

Open your existing GitHub repository `najamudinburki-code/apk`. Choose **Add file → Upload files**. Drag the extracted folders and root files into the browser upload area. Commit the update. Replace the files at the same paths; keep `backend` and `dashboard` directly at the repository root.

The new server needs `backend/installation.cjs`, `backend/enrollment.cjs`, `backend/features.cjs`, `backend/feature-store.cjs` and the updated server files. The dashboard needs `components/EnrollmentPanel.jsx`, `components/FeaturePanels.jsx`, the new `components/ActivityLog.jsx` and updated `App.jsx`. Uploading the complete extracted contents is easier than picking individual files, and `docs/FINAL-FILE-MAP.json` lists every file that should exist afterwards.

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

1. Tap **App text and notification sharing** on the home screen, then **Approve sharing** in the dialog.
2. Leave **All supported apps automatically** selected, tap **Continue**, read the disclosure, then tap **Approve sharing**. No package names need to be typed. Newly installed supported apps are included automatically. You can still use **Choose apps by name** to restrict sharing.
3. Enable **one** of the two System Health screen readers in Accessibility settings. Enable **Notification Reader** in Notification access settings.
4. If Android shows “Restricted settings”, open the System Health app-information page, use its menu's **Allow restricted settings** option, then return to the access setting.
5. Open WhatsApp, Chrome or another supported app and generate an ordinary visible text change or a new message notification. Look in the dashboard's **Text and notifications** panel.

Screen text and incoming message notifications are different sources. Some apps expose incomplete screen text; Android may hide sensitive notification contents. This app cannot supply text the operating system or target app does not expose. Password fields are redacted. Automatic scope includes ordinary app screen text and notifications; the existing exclusions for this app itself and Android system components remain. Camera, microphone and screenshots still use their separate controls. Your automatic/selected-app choice is saved. Sharing approval expires when the app process ends, so approve it again after a restart without typing package names.

## 5. Use the other tools

Open **Device tools, location and shared files** in the phone app.

| Tool | What to do | Where to see the result |
| --- | --- | --- |
| Camera | Choose front or rear under **Which camera**, then **Take one photo**; allow Camera permission | Dashboard **Files** |
| Live camera view | Allow **Allow the dashboard to start a live camera view** on the tools screen (off until you switch it on), keep monitoring running, then send the dashboard request; tap **Stop the live camera view now** to end it early | **Files**, as a stream of small JPEG frames, with a start and a stop row in the phone's logs |
| Microphone | **Record microphone audio**; allow Microphone; keep the tools screen open; tap **Stop the recording** | **Files**, with audio playback/download |
| Screenshot | **Take one screenshot**, then approve Android's screen-sharing dialog; open the selected screen during the 5-second countdown | **Files** |
| Location | **Start sharing location**; allow precise location; enable GPS | **Location**, with accuracy and sample time |
| Geofences | **Watch an area** — add a name, coordinates and radius; grant Location **Allow all the time**; start location sharing. **See or remove watched areas** lists them | **Location → Geofence events** |
| Nearby scan | Enable Wi-Fi, Bluetooth and Location; **Scan nearby Wi-Fi and Bluetooth** on the phone, or send the dashboard request and it runs without opening the screen | **Nearby scans** |
| Folder browsing | **Choose a folder to browse** with Android's picker, open it on the phone, then confirm the one file to upload. **Files saved on this phone** says whether each one was received by the server or is still waiting | **Files** |
| Remote requests | Send a request in dashboard **Requests** or **Phone rules**; the ledger shows pending → delivered → running → completed / reviewed / declined / failed / expired | Request result, then the corresponding output panel |
| Scheduled reports | On the tools screen pick **How often** and switch on the phone status report or the nearby scan | Same output panels as the one-off tool, repeated while monitoring runs |

Phone status, nearby scans, photo capture and microphone recording run in the background: a dashboard request for any of them never opens the phone's screen. Android hands out camera and microphone access only when monitoring starts from a visible app, so tap **Start monitoring** in the app after installing or after any boot; a session that Android restarted at boot keeps reporting and screen reading but answers a photo or microphone request with a failure you can re-send after starting monitoring yourself. Android still shows its own camera and microphone indicator while they run, and that indicator cannot be turned off by the app. Screenshot, location and geofence approval still need the visible tools screen because Android asks for consent there, so those requests open it; Android can refuse that launch while the screen is locked, and the request then reports a failure you can re-send. A recording or photo started on the tools screen stops when you leave it. A dashboard microphone request records for 15 seconds.

**Live camera view** (in source as of 2026-10-08, not yet in the delivered APK) is the one tool that repeats instead of answering once, so it has its own switch and its own limits. Nothing streams until you tick **Allow the dashboard to start a live camera view** on the tools screen, which is off in a fresh install. That tick is only half of it: the dashboard's own **Phone rules** must also list live view, and if they do not, the phone answers the request `declined` — "A dashboard rule keeps live view off on this phone" — without opening the lens. Both halves were measured on the Camon 20 on 2026-10-08: the owner's tick was on, the rule set (audio, geofence, location, photo, scan, screenshot, …) has never included live view, and zero frames were sent. A session then sends about two small frames a second for at most 120 seconds or 6 MiB and stops by itself; the monitoring notice reads "Live camera view is streaming to your dashboard" while it runs, Android's camera indicator stays lit and cannot be suppressed, and the phone logs a start row and a stop row with the frame, byte and skipped counts. **Stop the live camera view now** on the same screen ends it early, and so does the dashboard's Stop request even if your rules keep the tool off — a rule can prevent a stream, never strand one. A photo or microphone request sent during a stream waits for the lens instead of failing.

A request that a dashboard rule keeps switched off is answered **declined** with the rule named, so the ledger never shows something as delivered that quietly did nothing. "Completed" means the phone finished the action, and the ledger links the event or file that proves it; read the result detail and check the output panel to confirm delivery. Reports can be reviewed/exported locally without sharing them.

Uploads and request checks run about every 10 seconds while monitoring is on — every 5 seconds when items are already waiting, and longer only after a connection failure. The phone retains pending uploads across app restarts, tied to their original server/device configuration. Free hosting can take time to wake; leave monitoring on and refresh the dashboard.

Limits are displayed in the app: 4 MiB per file, 50 MiB of local saved tool files, 200 pending tool items, and 100 MiB / 500 cloud files per device. When the 200-item queue fills, the phone drops its oldest health or status sample to make room rather than discarding what it cannot remake. An item the server rejects permanently, or one that fails 40 times, moves to the app's internal unsent folder instead of blocking later uploads. Export or delete old local files when the phone vault is full; delete old cloud files from the dashboard when its quota is full. The server also deletes cloud history and uploaded files older than its retention window (30 days unless `RETENTION_DAYS` says otherwise), so export anything you want to keep. **Clear pending uploads** cancels unsent tool items while preserving local saved files.

## What was checked

**8 October 2026, for the interface rebuild:** Android `:app:testDevUnitTest` → **127 checks across 18
test classes, 0 failures** (the 34 new ones cover the home screen's decisions, the tool names and search,
and the plain-word state text; one check proves every control tagged on the tools screen is named in the
tool catalog). `:app:assembleDev` produced `app/build/outputs/apk/dev/app-dev.apk` and `:app:lintDev`
passed with **0 errors**. No phone or emulator was attached, so the light/dark appearance, large-font
layout, TalkBack order, the refused-permission guidance and every capture path are **unverified** — see
`docs/UI-BEFORE-AFTER.md` for the split between what the build proves and what still needs the handset.
Backend, dashboard, request actions, preference keys and enrollment were not changed.

**8 October 2026, for the live camera view added since the 0.5.0 APK:** Android `:app:testDevUnitTest` →
**93 checks, 0 failures** (dev variant only; the debug and release tasks have not been re-run since);
`:app:assembleDev` and `:app:lintDev` pass with **0 lint errors**; backend `npm test` → **40 checks pass**;
dashboard `npm run build` passes at 387.02 kB. No phone was attached for any of that, so the live view has
never opened a camera, and the delivered APK below does not contain it. The rows after this one are the
0.5.0 record.

- Backend: `npm test` → **39 passing checks** against SQLite. They cover invitation-based joining and concurrent join retries, upgrade of pending phones, disabling future joins without disconnecting existing phones, legacy registration and approval/decline, token isolation, duplicate approval, expiry renewal, disabled phones, auth, rate limits, persisted telemetry, duplicate retries, 4 MiB files, downloads, the full request lifecycle including `running` and rule-blocked `declined`, the rules payload being rejected when it asks for an unknown tool or an out-of-range cadence, token rotation that revokes the old credential, the retention sweep, dashboard session revocation that survives a restart, the advertised APK release, and server restarts. The PostgreSQL code path only runs when `TEST_DATABASE_URL` points at a live database, which was not available for this run.
- Android: **49 unit checks passed, zero failures** in both the `dev` and `debug` variants (request routing including the rules channel, upload cadence, outbox shedding and retirement, app-capture scope, camera selection, enrollment identity, permission plan, readiness, dashboard-rule interpretation, report scheduling, and update-version comparison). `:app:assembleDev`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDev` and `:app:lintDebug` all pass; lint reports **0 errors** (100 style/target-SDK warnings remain, none blocking). Package `com.example.systemhealth`, versionCode 8, versionName `0.5.0`, Android 8+. The release build runs R8 and writes `app-release-unsigned.apk`, so it must be signed with your own key before use; its deobfuscation map is at `app/build/outputs/mapping/release/mapping.txt`.
- Dashboard: production build passes (`npm run build`, 387.58 kB bundle). A built bundle without `VITE_SERVER_URL` falls back to `http://localhost:3000` and cannot reach your hosted backend — set it before deploying.
- Rules flow in a browser: the Phone rules tab was exercised against a throwaway local backend and temporary SQLite file with self-generated test credentials — sending rules, seeing the phone's reported rule set, and confirming a switched-off tool is declined. No production or dev `.env`, database or deployment was read or changed, and the scratch backend, database and files were removed afterwards.
- Not checked in this run: anything that needs a physical phone, and no full dashboard browser walkthrough of every tab. No device was connected.

Physical camera, microphone, GPS, Bluetooth, accessibility behavior, OEM battery restrictions and reboot behavior still need testing on your Camon 20. The debug APK installs directly; the unsigned release APK needs signing first.
