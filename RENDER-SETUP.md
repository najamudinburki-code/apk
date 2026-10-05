# Put System Health online with Render — beginner steps

Prepared 5 October 2026. This package is ready for account setup; it is not already deployed.
The APK stays on your Android phone. Render hosts the backend and browser dashboard.
Neon stores enrollment and telemetry in a persistent PostgreSQL database.
Your computer can be turned off after deployment. Your phone and browser need internet.

## 1. Upload the source to GitHub

1. Download Render-deployment.zip and extract it on your computer.
2. Sign up or sign in at https://github.com/.
3. Click **New repository**, choose a name such as system-health-cloud, select **Private**, enable **Add a README file**, then create it.
4. Click **Add file → Upload files**. Drag the extracted **backend** and **dashboard** folders, **render.yaml**, **RENDER-SETUP.md**, **.gitignore**, and **.node-version** into the page. Commit the upload. If hidden dotfiles are not visible, the deployment settings below still pin Node; add a Node .gitignore on GitHub before uploading future local work.
5. Check that backend/package.json and dashboard/package.json appear directly under those two folders. Do not add another Render-deployment folder around them. Upload extracted files, not the ZIP itself.

This deployment package contains no real password, database or device token. Keep future .env files, node_modules, SQLite files and signing keys out of GitHub. You can alternatively upload the full APK-integrated source with backend/dashboard directly at repository root; Android is not deployed by Render.

## 2. Create the free persistent database

1. Sign up/sign in at https://console.neon.tech/ and create a **Free** project.
2. Use a name such as system-health-db. Choose a region near the Render region you will use. Keep the default PostgreSQL/database/role choices unless you already know you need something else.
3. Open **Connect** / connection details. Choose the project database and role; enable **Connection pooling** if offered.
4. Copy the PostgreSQL connection string, which begins with postgresql:// or postgres://. It is a secret. Paste it only into Render's backend DATABASE_URL field in step 4. Do not paste it into the dashboard, phone, GitHub, or a chat.
5. You do not need to run schema.sql manually. The backend creates its PostgreSQL tables before accepting connections. Do not import the SQLite schema into Neon.

Keep this database/project when updating or redeploying the app. Old records from a local SQLite database are not automatically copied to Neon; enroll a new cloud device in step 6. Keep your local project/database if you still need its old records.

## 3. Create the dashboard on Render first

1. Sign up/sign in at https://dashboard.render.com/. Use the free/Hobby workspace option.
2. Click **New → Static Site**. Connect your GitHub account and select the repository you uploaded. Grant access to this private repository.
3. Use these settings:

| Setting | Value |
| --- | --- |
| Name | Choose a unique name, such as system-health-dashboard-yourname |
| Branch | main, or the branch that actually contains your files |
| Root Directory | Leave empty |
| Build Command | npm --prefix dashboard ci && npm --prefix dashboard run build |
| Publish Directory | dashboard/dist |

4. Add these environment variables:

| Key | Value |
| --- | --- |
| NODE_VERSION | 24 |
| SKIP_INSTALL_DEPS | true |
| VITE_SERVER_URL | https://placeholder.invalid |

The placeholder allows the static site to build before you know the backend URL. It is not your server and login will not work yet.
5. Create the static site and wait for deployment. Copy the actual HTTPS dashboard URL shown by Render. It will end in .onrender.com unless you configured your own domain. Save it as **DASHBOARD URL**. Do not assume the name determines the exact URL.

## 4. Create the backend web service

1. In Render, click **New → Web Service**, connect the same repository, and choose **Node**.
2. Use these settings:

| Setting | Value |
| --- | --- |
| Name | A unique name, such as system-health-backend-yourname |
| Branch | Same branch as the dashboard |
| Region | Near your Neon database |
| Root Directory | backend |
| Build Command | npm ci |
| Start Command | npm run start:render |
| Instance Type | Free |
| Health Check Path | /health |

3. Add these environment variables:

| Key | Value |
| --- | --- |
| NODE_VERSION | 24 |
| TRUST_PROXY_HOPS | 1 |
| DATABASE_URL | The secret Neon connection string from step 2 |
| DASHBOARD_USERNAME | Your chosen login name, e.g. admin |
| DASHBOARD_PASSWORD | A unique private password of at least 16 characters |
| JWT_SECRET | A private random secret of at least 32 characters; generate it as below |
| DASHBOARD_ORIGIN | The exact DASHBOARD URL from step 3, including https://, with no page path |

To generate JWT_SECRET, run this once in a local terminal with Node.js 24 installed, then copy the result privately into Render:

```sh
node -e "console.log(require('node:crypto').randomBytes(48).toString('hex'))"
```

Keep the same JWT_SECRET when redeploying. Do not reuse the dashboard password as this secret.
Do not run npm run setup on Render. Do not upload a .env file. Do not add TLS certificate paths or disable database TLS. Render supplies the HTTPS certificate and PORT automatically; the backend listens on that port and 0.0.0.0.
4. Create the service. Wait for deployment, then copy its actual HTTPS URL as **BACKEND URL**.
5. Open BACKEND URL + /health in a browser, e.g. https://your-actual-backend.onrender.com/health. A ready process returns {"ok":true}. The backend root / may say Cannot GET /; the dashboard is the separate static-site URL.

The health endpoint confirms initialization and a running process. It does not query Neon on every health check, avoiding unnecessary database wake-ups. Database outages still cause data requests to fail; uploads are acknowledged only after persistence succeeds.

## 5. Connect the dashboard to the backend

1. Open your Render **static site → Environment**.
2. Change VITE_SERVER_URL from https://placeholder.invalid to the actual BACKEND URL.
3. Save and rebuild/redeploy the static site. This is a build-time variable: a server restart alone does not change the compiled dashboard.
4. Open the DASHBOARD URL and log in with the backend DASHBOARD_USERNAME / DASHBOARD_PASSWORD.
5. Confirm the backend DASHBOARD_ORIGIN matches the exact dashboard origin you are opening. Changing to a custom domain requires updating this value and redeploying the backend.

## 6. Connect the Android app

1. In the online dashboard, enroll a device such as phone-cloud-01 and copy its one-time device token privately.
2. On the phone, open **System Health → Configure enrolled device**.
3. Enter the **BACKEND URL**, enrolled device ID, and issued token. Use https:// with no /health or /api path. The dashboard URL is for your browser; the backend URL goes in the app.
4. Save and start monitoring. Check that a system_health event appears on the dashboard. Later health samples are approximately five minutes apart while Android permits execution.
5. Test using mobile data with your computer turned off. The phone and browser no longer need the same Wi-Fi.
6. Optional screen/notification sharing still requires the phone user's approval, selected package list, and Android Settings grants. The cloud setup does not bypass those controls.

After basic delivery works, redeploy the backend once and confirm the same enrollment still connects and past events remain. The Android source and supplied debug APK did not change for this hosting update.

## 7. Understand the free limits

- Render's free backend sleeps after 15 minutes without incoming traffic. The next request can take about a minute to wake it. Wait and retry login if needed. Do not expect instant, uninterrupted 24-hour service from this plan.
- Render's free filesystem is temporary. DATABASE_URL selects persistent Neon PostgreSQL; without it this backend refuses to start on Render. SQLite remains available locally.
- Render's own Free Postgres currently expires after 30 days. This guide uses Neon instead.
- Free Render services share monthly runtime, bandwidth and build limits. Static sites also use bandwidth/build allowances.
- Neon's current Free plan includes 1 GB storage and 100 CU-hours per project each month. Continuous monitoring can exceed the compute allowance even with a small database. Data persists when compute sleeps, but service can be restricted at quota limits. Check usage in both dashboards.
- Choose Free tiers and do not enable paid upgrades unless you intend to pay. Free hosting is suitable for learning and limited testing; it does not guarantee all-day monitoring within every quota.

## Troubleshooting

| What you see | Check |
| --- | --- |
| package.json not found | Repository folders and Root Directory/build commands |
| Backend startup failed | Required env vars, secret lengths, correct Neon connection string, and database availability; never post the connection string publicly |
| Login still uses localhost or placeholder | VITE_SERVER_URL on the static site, then rebuild it |
| Origin not allowed | Backend DASHBOARD_ORIGIN must match your actual browser dashboard origin |
| Invalid credentials | The backend's dashboard username/password; these differ from device tokens |
| Device unauthorized | Device must be enrolled in the cloud dashboard with the exact ID and one-time token |
| Login is slow after inactivity | Allow roughly a minute for free Render to wake, then retry |
| No health event | Correct BACKEND URL in app, Start monitoring, phone internet, notification/service status and Render logs |
| Rate limit response | Too many login attempts; wait for the 15-minute window instead of repeatedly submitting |

## Optional Blueprint

render.yaml describes the same free backend + static site. Use either manual creation above OR New → Blueprint, not both (which creates duplicates). A Blueprint prompts for DATABASE_URL, DASHBOARD_PASSWORD, DASHBOARD_ORIGIN and VITE_SERVER_URL, and generates JWT_SECRET. If assigned URLs are not known initially, use https://placeholder.invalid for the two public URL fields, then replace DASHBOARD_ORIGIN with the actual dashboard URL and VITE_SERVER_URL with the actual backend URL and rebuild. Do not enter real credentials into render.yaml. Review that the backend plan is free and there is no paid disk/database before creating resources.

## Verification performed

- 14 backend checks pass with SQLite.
- The same 14 checks pass through node-postgres against PGlite's PostgreSQL engine/socket server: enrollment, authentication, saved telemetry, live delivery, command dispatch and persistence after backend restart.
- Dashboard production build passes.
- No live Render/Neon account deployment or real phone test was performed. The PostgreSQL checks used a local PostgreSQL engine; production TLS and provider connectivity are verified by your first deployment.

## Official documentation (checked 5 October 2026)

- https://render.com/docs/free
- https://render.com/docs/deploy-node-express-app
- https://render.com/docs/static-sites
- https://render.com/docs/blueprint-spec
- https://neon.com/blog/neon-free-plan-1-gb-per-project
- https://node-postgres.com/features/ssl
