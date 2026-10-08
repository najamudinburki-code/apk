# Interface before and after — 8 October 2026

Every control that existed on 7 October is listed with where it is now. Nothing was deleted, disabled,
renamed into a placeholder or hidden behind a gate that did not exist before. Backend routes, request
action names, saved preference keys and device identity are untouched, so a phone that was set up on an
earlier build keeps its enrollment, its rules and its choices.

The three screens were rebuilt out of one shared component kit (`ScreenKit.kt`) so a card, a labelled
state row, a button weight, a switch with an explanation and a foldable section look and behave the same
way everywhere. The decisions each screen shows come from three plain objects that are unit-tested
without Android: `HomeOverview` (what the home screen says and offers), `ToolCatalog` (tool names,
one-line explanations and search) and `PlainStatus` (the words for every state and refusal).

## Home screen (`MainActivity`)

| Was | Now |
| --- | --- |
| Title line with the version | Same line, as the small heading at the top: `SYSTEM HEALTH · <version>` |
| Readiness card (title plus Server / Notifications / Monitoring / Last upload / Pending text lines, dashboard rules, update notice) | Headline answers "is this phone connected and monitoring", one sentence under it answers "what should I do next", and the labelled facts are in **What is happening on this phone** — one row each for connection, monitoring, last delivery the server accepted, waiting uploads, screen text and notifications, plus dashboard requests, rules and repeating reports when they exist |
| Eight flat buttons in a row | One primary action on the first card, chosen by the state that was read; the two Stop controls stay on that same card, and the three ways forward are in **Where to go from here** |
| "Check connection" | Primary button **Check the connection again** whenever the server has not confirmed this phone; also **Check the connection now** in the details fold. Same `ConnectionDiagnostics.probe` call |
| "Start monitor" | Primary button **Start System Health Monitor**, same `requestMonitoringStart()` gate that takes the owner to notifications first if Android needs them |
| "Stop monitor" | **Stop System Health Monitor**, outlined in the alert colour, shown only while monitoring runs. Same `CoreService.stop()` |
| "Stop app-text sharing" | **Stop sharing screen text and notifications**, shown only while that sharing is on. Same `CoreService.disableAppCapture()` |
| "Device tools…" | **Open device tools** — same activity |
| "App text and notification sharing" | Same name, same dialog (`configureAppCapture` → all-supported-apps or choose-by-name, both approval dialogs unchanged) |
| "Guided setup/permissions" | **Guided setup and permissions** |
| "Advanced settings" fold — Connect automatically, Advanced connection settings, Accessibility settings, Notification access settings, Reconnect enabled readers, Refresh status, raw diagnostics text | **Show details and diagnostics** fold. All six controls are there with the same actions and the same dialogs; the raw block is still shown verbatim, in mono, selectable, so it can be read out to whoever runs the dashboard |
| Status line that was the main body text | Moved into the fold, and any line Android wrote that is not one of the four known-calm states is repeated at the top of the screen in **Needs your attention** |
| Update-available notice | **Needs your attention**: the newer version, this phone's version, and where to get it |
| Refresh every 2 seconds | Still every 2 seconds, but the screen is redrawn only when a value actually changed |

## Device tools (`FeaturesActivity`)

One search box at the top narrows the page by plain word ("photo", "stop", "location", "folder",
"history"). A search can never hide a Stop control or a state row.

| Was | Now |
| --- | --- |
| Status text refreshed every 2 seconds | **Right now** card: monitoring, microphone, location, live camera view, uploads waiting, items that could not be sent, dashboard requests, dashboard rules, repeating reports, and the request this screen is working on |
| Run pending dashboard requests | **Dashboard requests** → *Run a waiting request* (same ledger, same `request_settings` exclusion) |
| Cancel active request | **Dashboard requests** → *Cancel the active request*, in the Stop style; still reports `declined — Cancelled on phone` |
| Camera facing spinner | **Camera, microphone and screen** → *Which camera*, front by default, same `feature_options/camera_facing` key |
| Take and share photo | Same card → *Take one photo*, same consent dialog and camera choice |
| Start / Stop microphone | Same card → *Record microphone audio* and *Stop the recording* |
| Screenshot | Same card → *Take one screenshot*, still Android's own share-sheet consent |
| Live view allow switch and Stop | Same card → *Allow the dashboard to start a live camera view* keeps its "off means every request is refused" wording, plus *Stop the live camera view now* |
| Location start / stop | **Location and places to watch** → *Start sharing location* / *Stop sharing location* |
| Add / list geofences | Same card → *Watch an area* / *See or remove watched areas* |
| Nearby scan | **Nearby scan** → *Scan nearby Wi-Fi and Bluetooth* |
| Report cadence spinner and per-tool switches | **Reports that repeat** → *How often* (15/30/60/240 min) and one switch per report-only tool; the "only report tools can repeat" rule is stated above them |
| Folder picker | **Shared files** → *Choose a folder to browse* (same Android picker, same 4 MiB limit, same per-file confirmation) |
| Local files list, play, export, delete | **Shared files** → *Files saved on this phone*; each row now says **received by the server** or **waiting to upload** |
| Clear pending uploads | **Shared files** → *Clear waiting uploads*, same warning that saved and cloud files remain |
| Capture-alert and notice-detail switches | **Notices and privacy**, each naming what stays on screen either way |
| "See what this phone has sent" | **Notices and privacy**, still the deliveries this phone completed |
| "Back to monitoring" | **Notices and privacy** → *Back to monitoring* |
| Requests arriving with `auto_request_id`, per-action consent, permission asks, projection and export activity results | Unchanged, including the same three accepted auto-request actions and the same request states |

## Guided setup (`PermissionSetupActivity`)

Still the same three resumable steps with the same saved keys, and it still opens on the first step that
needs the owner.

| Was | Now |
| --- | --- |
| "1 of 3 · Connect" heading and a plain list line | Same heading, plus a **Your progress** card that names all three steps and marks Done / You are here / Up next |
| Connection note, "Continue to permissions" (greyed out until connected), "Retry connection" | One card: the connection state, a primary button that reads **Check the connection again** while it is waiting and **Continue to permissions** once the server has answered, and a fold explaining what the step does and what a long wait means. The 10-second retry loop is the same |
| Checkbox list of permission steps with a title and one detail line | One switch per step, each followed by why Android needs it and what stays off without it (`PlainStatus.whyPermissionIsNeeded`), plus **What this phone allows today** saying Allowed / Allowed, approximate only / Not allowed for each, and whether the readers are enabled |
| "Allow selected permissions and continue" | Primary button, relabelled **Android is asking…** while the system dialog is up; the switches are disabled for that moment instead of being redrawn |
| Special-access buttons under an "Optional" heading | **Optional system access** fold with the same three buttons and the same warning that screen-text sharing is approved separately from the home screen |
| A technical line after a refusal | An alert card naming each refused permission, what it means for that tool, and how to change it later, with a button to this app's Android settings. It clears itself once the permission is allowed |
| "Everything this phone needs is already in place. One tap starts monitoring." + "Enable monitoring" | **Ready to start** card, primary **Start System Health Monitor**. After the tap the screen reports what Android and the server confirm rather than claiming success; the primary becomes **Open the home screen** |
| "Check connection again" + "Review permissions" when something still blocks | **What is still missing** card, the blocking item named in plain words, primary **Check the connection again**, and the same review route |
| "Back" and "Finish later / return to app" | Quiet buttons at the foot of every step, same behaviour and the same `completed` flag |

## What became easier

- The home screen answers the five questions in order and puts one button forward, instead of eight
  buttons of equal size.
- Every tool has a plain name and one line saying what it does, grouped by purpose, and can be found by
  typing a word.
- Setup says why before Android asks, and says what to do when the owner says no.
- Nothing is called done because a button was pressed: delivery, monitoring and capture states are read
  back from the service, the queue, or the server's own acknowledgement.
- Light and dark follow the phone's system theme, spacing widens with the owner's font size, controls are
  at least 48 dp tall, card titles are announced as headings, state rows announce updates politely, and
  every switch, spinner, search box and fold carries a spoken label.

## Verified on the build PC, 8 October 2026

- `:app:testDevUnitTest` → BUILD SUCCESSFUL: **127 unit checks across 18 test classes, 0 failures, 0
  errors, 0 skipped** (counted from `app/build/test-results/testDevUnitTest/`). 93 were passing before
  this pass; the 34 new checks are `HomeOverviewTest` (13), `ToolCatalogTest` (11) and
  `PlainStatusTest` (10). One check asserts that every id the tools screen tags a control with exists in
  `ToolCatalog`, which is what keeps the search words and the buttons from drifting apart.
- `:app:assembleDev` → BUILD SUCCESSFUL, `app/build/outputs/apk/dev/app-dev.apk` produced.
- `:app:lintDev` → BUILD SUCCESSFUL, **0 errors**, 82 warnings, all in the categories this app already
  had (`UseKtx`, `InlinedApi`, `ObsoleteSdkInt`, `StaticFieldLeak`, dependency-version notices).

## Verified on the phone, 8 October 2026

The same evening, on the owner's TECNO Camon 20 (Android 14) running this `dev` build against the laptop's
local backend. `docs/VALIDATION.md` holds the full record, including the nine interface defects only a
handset could show — an invisible primary button, a ripple that never painted, alert text under 4.5:1, a
red "nothing to do" alarm, two ellipsized strings, a stale-success connection line, and a search box that
emptied the whole page. Confirmed by photograph:

- Home in **light and dark**, at normal text and at **1.3× enlarged text**; Device tools in dark at both
  sizes. Readable contrast everywhere, nothing truncated or overlapping, Stop buttons wrapping instead of
  clipping. The tools screen in light at enlarged text is the one cell not yet photographed.
- **Search filters without hiding a way out**: `photo` and `stop` each keep every Stop control, the state
  rows and **Back to monitoring**; clearing the box restores all seven cards.
- **States are read, not echoed.** Deliveries advanced on their own with server-confirmed timestamps, the
  eight genuinely unsent items stayed reported as unsent, and after the reinstall the home screen said
  **Ready — monitoring stopped** instead of claiming a session it no longer had.
- The camera chooser, both live-view controls, the repeating-report switches, the notice switches and every
  card heading render as laid out; the tools screen was walked top to bottom in both themes.

## Not verified

- **Guided setup has still never been rendered on a handset.** It is not exported, so it could not be opened
  from a computer and no scripted tap was attempted — that screen needs the owner's own finger. That also
  leaves the refusal card unproven as drawn, and setup resuming from a mid-wizard exit and after rotation.
- **TalkBack was not run.** Reading order, heading announcements, polite live-region updates and the spoken
  labels are designed for and are not measured. Nothing in this document claims otherwise.
- The geofence and enrolment dialogs' hint text at enlarged font, small screens, rotation, and the fold
  animations.
- Camera, microphone, screenshot, live view, location, geofence, scan, sharing and upload behavior inside
  the rebuilt screens: unchanged code paths, and beyond the telemetry and delivery states above still
  unproven on hardware in this interface.

This is a `dev`-variant debug build pointed at a local server. It is not a signed release artifact and must
not be described as release-ready: the release APK has still never been installed on a phone.
