# Shared instructions for AI edits

Native Android: `android/app/`, Kotlin and Android views. Backend: `backend/`, Node 24+/Express, API version 4. Dashboard: `dashboard/`, React/Vite. Read `LOCAL-DEVELOPMENT.md` for the test workflow.

## Editing contract

- Start from the current project file. Assign different files to different AIs; do not have two AIs overwrite the same file concurrently. Merge one coherent change at a time.
- For copy/paste delivery, return the complete assigned file, its exact relative path, and no placeholders or omitted sections. Preserve unrelated imports, functions and behavior.
- Inspect related callers, endpoints and data structures. If more files must change, identify them and return coordinated replacements; do not invent incompatible APIs or remove functionality to fit a one-file request.
- Preserve permission handling, visible monitoring notices, Stop controls, and phone review for sensitive requests. Camera/microphone/screen actions remain visible and consented.
- Keep credentials out of source. Do not request, copy or return `.env`, `.env.dev`, private signing keys, passwords or database connection strings in code.
- Test with the `dev` Android variant/development launchers. Preserve the original package, Render connection, and manual enrollment in normal debug/release builds.
- Leave archives/alternative implementations under `archives/` and `variants/` unchanged for active-app edits.

## Commands and interfaces

- Backend: `npm run setup:dev`, `npm run dev`, `npm test` inside `backend/`. Development uses `.env.dev` and isolated SQLite under `dev-data/`, or an explicitly configured Neon dev branch; inherited production configuration is cleared.
- Dashboard: `npm run dev:local`, `npm run build` inside `dashboard/`. The local entry point fixes the backend to localhost. Production uses its configured VITE_SERVER_URL.
- Android: open `android/`, select `dev`, and Run. Checks: `bash gradlew :app:assembleDev :app:testDevUnitTest :app:lintDev`; Windows uses `gradlew.bat`.
- Phone URL: `http://127.0.0.1:3000` through `adb reverse tcp:3000 tcp:3000`. Never replace the Render URL globally for local tests.
- Keep existing device authentication, Socket.IO acknowledgements, API paths and request states compatible unless all affected components are updated together.

Run checks appropriate to the change. Native sensors, permissions, camera lens selection and OEM notifications need phone verification in addition to compilation. Do not claim one-second Kotlin updates or real-device checks from a browser preview. Report changed files, intended behavior, checks and remaining setup. Review the diff before committing; push when ready to deploy.
