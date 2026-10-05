# System Health dashboard

Inside this folder run `npm ci`, then `npm run dev`. Open http://localhost:5173 on the backend computer and sign in with credentials printed by `npm run setup` in ../backend. See ../START-HERE.md for the complete workflow.

VITE_SERVER_URL defaults to http://localhost:3000. Use .env.example to configure another endpoint; restart Vite after changes. The dashboard origin must match backend DASHBOARD_ORIGIN. Port 5173 is fixed for local setup.

Implemented: login, device enrollment/one-time token display, roster, live health samples, latest health cards, history, refresh, and logout. Credentials stay in memory and clear on reload/logout. Server dashboard sessions expire after one hour.

Original src/components map/file/log/control prototypes remain in the source but are not exposed by App.jsx. Those feeds/actions require additional Android/backend integration. Run `npm run build` for dist/. The development server is not production hosting.
