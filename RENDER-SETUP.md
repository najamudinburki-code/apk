# Update your existing Render services — System Health 0.3.1

Your backend address is **https://apk-obeb.onrender.com**. It was reachable during verification, but returned the older health response. These new files have not been deployed to your account. Use your existing GitHub repository, Render Web Service, Render Static Site and Neon database.

## 1. Update GitHub

Extract Render-deployment.zip. Open its inner folder and upload the complete backend, dashboard, docs and root files to your existing repository, replacing the same paths. Keep backend and dashboard directly at the repository root. Do not upload the ZIP itself or create an extra wrapper folder.

Include the new **backend/installation.cjs** and all updated server/dashboard files. This deployment ZIP has the invitation hash, not its raw value. The Android source/APK includes the enrollment invitation. Anyone given that APK can register a phone, without dashboard access.

Keep private .env files, database credentials, generated databases, node_modules and signing keys out of GitHub. The deployment ZIP excludes those files.

## 2. Redeploy the existing backend

| Setting | Value |
| --- | --- |
| Runtime | Node |
| Root Directory | backend |
| Build Command | npm ci |
| Start Command | npm run start:render |
| Health Check Path | /health |
| NODE_VERSION | 24 |
| TRUST_PROXY_HOPS | 1 |

Retain the currently working **DATABASE_URL**, **DASHBOARD_USERNAME**, **DASHBOARD_PASSWORD**, **JWT_SECRET** and **DASHBOARD_ORIGIN** from Render's Environment page. Do not replace your Neon project or delete its records. The app uses the existing database and creates new tables automatically if needed.

**No new environment variable is required for automatic joining.** The matching invitation hash is already in installation.cjs. Leave AUTO_ENROLLMENT_KEY_HASH absent to use it. An optional `AUTO_ENROLLMENT_KEY_HASH=disabled` stops new automatic joins while existing devices continue working; remove this override and redeploy to use the bundled invitation again.

If auto-deploy did not start, choose **Manual Deploy → Deploy latest commit**, then wait for Live. Open:

https://apk-obeb.onrender.com/health

Expected response:

```json
{"ok":true,"api_version":4,"automatic_enrollment":true}
```

The health endpoint confirms process initialization, not a fresh database query on each check. Data requests still require working database connectivity.

## 3. Redeploy the existing dashboard

Keep its current build settings. For a Static Site built from repository root, use:

| Setting | Value |
| --- | --- |
| Root Directory | Empty |
| Build Command | npm --prefix dashboard ci && npm --prefix dashboard run build |
| Publish Directory | dashboard/dist |
| NODE_VERSION | 24 |
| SKIP_INSTALL_DEPS | true |
| VITE_SERVER_URL | https://apk-obeb.onrender.com |

If your already working Static Site instead uses Root Directory dashboard, keep that layout with Build Command npm ci && npm run build and Publish Directory dist. Do not mix these two layouts.

VITE_SERVER_URL is compiled into the dashboard: choose **Save, rebuild, and deploy** after changing it. Open your existing dashboard URL and sign in using your existing backend dashboard credentials. The updated page shows **Automatic phone connection**, **Connected phones** and all the tool panels. Optional manual enrollment and legacy pending-request controls remain.

The backend's DASHBOARD_ORIGIN must equal the exact HTTPS origin of that dashboard (no path or trailing slash). The phone uses the backend URL; the dashboard's public address is only for your browser.

## 4. Install and open the current APK

Install SystemHealth-debug.apk and **open it**. It generates a unique phone ID and device token, stores them encrypted, and connects automatically. Allow Android notifications; health monitoring starts automatically. No URL, phone ID, token or dashboard approval is needed for this APK. The phone appears in your dashboard without an approval click.

A working previous enrollment is retained during a compatible upgrade. A pending 0.3.0 installation is automatically activated with the same identity after upgrading. A phone explicitly disabled or declined remains blocked. Advanced connection settings and manual enrollment remain available for recovery/custom servers.

Screen/notification sharing still requires the phone user's sharing decision and Android Accessibility/Notification access grants. All supported apps is the default scope; no package names need to be typed. Camera, audio, screenshots, location and files use their existing controls. Stop stays effective.

## How future installations connect

The app's AutomaticEnrollment.SERVER_URL is fixed to https://apk-obeb.onrender.com. Its invitation matches backend/installation.cjs. After the one-time deployment, future phones using this APK join the same backend with separate credentials and appear in the same dashboard. The backend reads/writes your existing Neon database using DATABASE_URL. The dashboard reads authenticated events from the backend, not directly from Neon.

Changing the dashboard URL alone requires updating DASHBOARD_ORIGIN in the backend. Changing the backend URL requires updating the app's fixed URL and rebuilding for new installations, plus changing VITE_SERVER_URL and rebuilding the dashboard. Existing phones can use Advanced connection settings if their identity/token is valid at the new server. Keeping the same database preserves records and phone credentials.

## Troubleshooting

| What you see | Action |
| --- | --- |
| Old {"ok":true} health response | Deploy the complete updated backend; check the repository branch and Root Directory |
| automatic_enrollment:false | Remove an unintended AUTO_ENROLLMENT_KEY_HASH=disabled override and redeploy |
| Invitation does not match | Use this APK with its matching backend/installation.cjs; remove an unintended invitation-hash override |
| Automatic enrollment route missing | Deploy the updated backend/enrollment.cjs, installation.cjs and server files |
| Backend startup failed | Check existing required environment variables and database availability; keep secrets private |
| Login reaches localhost/another server | Set VITE_SERVER_URL on the Static Site and rebuild |
| Origin not allowed | Set backend DASHBOARD_ORIGIN to your exact dashboard origin |
| Connection is slow | Keep the app open with internet while Render wakes; it retries |
| Previously stopped monitoring | Tap Start; Stop deliberately prevents automatic restarting |
| App not installed/signature conflict | Build with the previous signing key for an update, or uninstall/reinstall after preserving needed local data |
| New phone not appearing | Verify health API version 4, Android connection status and the dashboard's backend URL |

No live account deployment or physical-phone test was performed. APK compilation/lint, six Android unit tests, 28 backend checks per storage setup, dashboard build and browser checks passed. See START-HERE.md for all tool usage and docs/VALIDATION.md for limits.

Official setup references checked 5 October 2026:

- https://render.com/docs/deploy-node-express-app
- https://render.com/docs/configure-environment-variables
