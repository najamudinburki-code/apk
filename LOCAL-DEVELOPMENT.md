# Edit, save and test locally

Keep `android/`, `backend/`, `dashboard/`, `tools/`, and the root files together in one permanent folder on your laptop. The native app lives in `android/app/`; do not move it to a root `app/` folder.

## Windows: one-time setup

1. Install [Node.js 24 or newer](https://nodejs.org/en/download) and [Android Studio](https://developer.android.com/studio). Open the project root in your editor and `android/` in Android Studio. Let Gradle sync finish. Install Android SDK 35 and Platform-Tools if prompted.
2. Double-click **SETUP-DEV.cmd**. It installs backend/dashboard dependencies and creates `backend/.env.dev` with a random dashboard password. Username: **admin_dev**. Keep the printed password; it is also in that local file. Running setup again keeps your credentials.
3. Double-click **START-DEV.cmd**, leave its window open, and visit **http://localhost:5173**. Sign in with the development login. Ctrl+C stops both servers.
4. Enable Developer options and USB debugging on your phone. Connect by USB and accept its computer authorization prompt. Double-click **CONNECT-PHONE.cmd**. Repeat after reconnecting the cable or restarting the phone.
5. In Android Studio, open **View → Tool Windows → Build Variants**, select **dev** for `app`, select your connected phone, and click **Run ▶**. This installs **System Health Dev** beside your existing app, with separate settings and enrollment. Follow its visible setup and permission choices.

Initial dependency/Gradle downloads require internet. Local testing with SQLite needs no GitHub, Render or cloud database.

## Daily loop

1. Start START-DEV, connect the phone with CONNECT-PHONE, and Run the `dev` app if needed.
2. Give an AI the current file, **PROJECT.md**, and related files it needs. Paste its returned replacement into the same file and save.
3. Test using the appropriate update action below.
4. Review changes in your editor's Source Control diff view, commit locally when useful, and push when ready to deploy.

| Changed file | Update behavior | What to do |
| --- | --- | --- |
| Dashboard JSX/CSS | Vite updates the browser, often almost immediately | Test at localhost:5173. After CONNECT-PHONE, the phone browser can use this address too. |
| Backend JS/CJS | Node restarts for changes to loaded modules | Retry the action; clients reconnect. Existing upload/polling intervals still apply. |
| Kotlin method body | Android Studio may apply the compiled changes | Use Apply Code Changes; if screen initialization in `onCreate` changed, restart the activity or click Run. |
| Kotlin fields/signatures, manifest, dependencies, build settings | Rebuild/restart may be needed | Click Run; sync Gradle for build/dependency edits. |
| `.env.dev` or development server configuration | Configuration files may not be watched | Stop and restart START-DEV. |

**One-second updates are possible for many dashboard edits, not guaranteed for Kotlin.** This app uses Android views rather than Compose, so Compose Live Edit does not apply. Apply Changes still builds/checks an APK. See [Android's Apply Changes guide](https://developer.android.com/studio/run#apply-changes).

## Connections and test data

| Component | Connection/data |
| --- | --- |
| Android `dev` | `http://127.0.0.1:3000` through ADB reverse; package `com.example.systemhealth.dev`, separate settings/identity |
| Android normal `debug` and `release` | Existing `https://apk-obeb.onrender.com`; original package and connection workflow |
| Backend `npm run dev` | Loopback port 3000, credentials from `.env.dev`, default database `backend/dev-data/devices.sqlite` |
| Dashboard `npm run dev:local` / START-DEV | Explicit laptop backend URL, overriding other Vite environment values for this process |

The backend development entry point clears inherited production settings before loading `.env.dev`. It never loads the normal `.env` and does not inherit a production DATABASE_URL from your terminal. The dashboard local launcher also overrides a configured Render URL. Production commands stay unchanged.

The dev app allows HTTP only to localhost/127.0.0.1 and does not fall back to Render when USB disconnects. Existing normal-debug HTTP behavior is retained; release does not gain the dev network configuration. Permissions, Stop controls, visible monitoring notices and the on-screen consent for screenshot, location, geofence and file tools remain. Anything the dashboard can switch off with a rule stays off on the phone until the rule is lifted.

### Optional Neon `dev` branch

SQLite is the quickest isolated starting point. For PostgreSQL testing:

1. In your [Neon Console](https://console.neon.tech), create a separate `dev` branch and copy **that branch's** connection string. Choose schema-only branching if available and you do not need production records, or use a separate empty test database/project. Ordinary branching can copy parent data.
2. Paste the dev connection string into **`backend/.env.dev`**, replacing `DATABASE_URL=`. Keep the production string in Render. The backend initializes its tables at startup.
3. Restart START-DEV. It reports PostgreSQL mode without printing the connection string. Confirm the branch in Neon before testing writes.

Scripts cannot identify a branch's friendly name from its connection string: they use the URL you explicitly put in `.env.dev`. Only use the test branch URL. All `.env` files and test databases are ignored by Git; `.env.example` stays shareable. This source update does not create a Neon branch.

## Manual commands

After installing dependencies:

```bash
# Terminal 1, inside backend/
npm run setup:dev
npm run dev

# Terminal 2, inside dashboard/
npm run dev:local

# Terminal 3, with one authorized USB phone and adb on PATH
adb reverse tcp:3000 tcp:3000
adb reverse tcp:5173 tcp:5173
```

Inside `android/`:

```bash
# Linux/macOS
bash gradlew :app:assembleDev :app:testDevUnitTest :app:lintDev
bash gradlew :app:installDev

# Windows
gradlew.bat :app:assembleDev :app:testDevUnitTest :app:lintDev
gradlew.bat :app:installDev
```

On a machine with about 4 GB of free memory, add `--no-daemon --max-workers=1` to any Gradle
command; `docs/ANDROID-BUILD.md` explains the memory settings. Android Studio's own Run button uses
its daemon and can need more room.

The dev APK is `android/app/build/outputs/apk/dev/app-dev.apk`. `installDev` installs it; open System Health Dev manually afterward. Android Studio's Run launches it too.

Linux/macOS: run `bash setup-dev.sh`, then `bash start-dev.sh`, and `bash connect-phone.sh`. On Windows with WSL, use the Windows launchers for straightforward USB access; a WSL backend requires additional host networking setup and is not this guide's default.

## Push when finished

Put `.github/` and all component folders at the GitHub repository root. `.github/workflows/android.yml` checks and builds the `dev` and normal `debug` variants (`assembleDev`, `assembleDebug`, both unit-test tasks, both lint tasks) for Android changes pushed to `main`/`master`, pull requests, or a manual Actions run. `.github/workflows/backend-dashboard.yml` runs `npm test` in `backend/` and `npm run build` in `dashboard/` when those folders change. After committing, download the test APKs from **Actions → workflow run → Artifacts**. Neither workflow installs APKs on phones, deploys to Render, or updates the installation website. Both were matched to the commands that pass locally, so a green CI run means the same checks the PC already ran.

CI uses a test signing key. Android requires matching signing keys to update an installed APK. Configure a persistent signing key for stable distribution; independently generated CI debug APKs are not guaranteed in-place updates. Your existing delivered APK and installation page stay available.

In each existing Render service, check **Settings → Build & Deploy → Auto-Deploy** and the linked branch. Backend root remains `backend`, build `npm ci`, start `npm run start:render`. Dashboard keeps its production build and production VITE_SERVER_URL. Account settings were not changed or verified by this source update. Use service build filters, if available, so each service deploys only relevant folder changes. See [Render deploys](https://render.com/docs/deploys).

## Troubleshooting

- **Port busy:** stop the earlier dev window before opening another. Ports 3000/5173 deliberately stay fixed. The development backend accepts the dashboard from either `http://localhost:5173` or `http://127.0.0.1:5173`, so it does not matter which you type. If something else already owns 5173 and you must use another port, start the dev backend with a matching `DASHBOARD_ORIGIN=http://localhost:5174` (or whichever port you chose) or the backend rejects the dashboard's requests as cross-origin.
- **ADB unauthorized:** unlock the phone and accept the computer prompt. With multiple devices, choose one using ANDROID_SERIAL or disconnect the others.
- **Phone cannot connect:** keep START-DEV open, reconnect USB, run CONNECT-PHONE again, and confirm you launched System Health Dev.
- **Browser updated but phone did not:** browser HMR does not update Kotlin. Run/Apply Changes in Android Studio, restarting the activity for initialization changes.
- **Login fails:** use admin_dev and the password in `.env.dev`, rather than your Render credentials.
- **Missing dependencies/Node error:** install Node 24+ and rerun SETUP-DEV. First Android sync/build takes longer than subsequent edits.
