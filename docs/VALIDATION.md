# Validation — handset session, 8 October 2026 evening

Driven on the owner's **TECNO Camon 20 (model CK6n, Android 14, HiOS)** over wireless ADB, running the
`dev` variant `0.5.0-dev` against the laptop's local backend through `adb reverse tcp:3000 tcp:3000`.
Every claim below was read off something that ran on the phone or answered it — a screenshot plus
`uiautomator` hierarchy, `dumpsys notification`, or the local dev database the phone itself wrote to — never
off a build log. This is the session that the "not verified — no phone attached" rows in the record underneath
were waiting for; those rows stay as written for the day they describe.

**Live behaviour confirmed.** Monitoring ran the whole session and the home screen and tools screen both
showed server-acknowledged deliveries advancing on their own — `Last confirmed delivery: 8 Oct, 20:26:54`,
then `8 Oct, 20:31:28`, then `20:36:37` — so the ~5-minute cadence and the "confirmed by the server"
wording are real, not a button echo. Eight items left over from an earlier interrupted run stayed
reported as `8 items could not be sent and are kept aside` rather than being quietly dropped or counted
as sent. After `adb install -r` killed the service, the home screen read **"Ready — monitoring stopped"**
with `Sharing is off. Nothing reaches your dashboard until you start monitoring.` — it did not keep
claiming a session it no longer had. Restarting monitoring was left to the owner's own finger.

**Twelve defects the handset showed and the build never could.** All are fixed, rebuilt and re-checked on the
phone unless a row says otherwise:

1. The primary button was invisible in dark mode: this ROM's `colorPrimary` is `#181B25`, the same colour
   as its own window background. The kit now picks the first theme accent that is actually distinguishable
   from the page (`accentColor()`), and the button was re-photographed painting solid blue with white text.
2. A `RippleDrawable` given a null mask never paints its content here — the button stayed the colour of the
   card. Filled buttons are now a `StateListDrawable` of rounded fills with a darker pressed copy.
3. Cards were indistinguishable from the page behind them, so the page read as one long column. Card fill
   is now lifted from the resolved surface and the emphasis border uses the same guarded accent.
4. `android.R.attr.colorError` on this ROM's light theme is `#FF5722`, which measures 2.4–2.8:1 as text.
   Alert words now go through `readableInk()`, which darkens that same colour until it reaches 4.5:1; the
   Stop outlines keep the loud colour and only the words change.
5. `Nothing waiting to upload` was drawn in the alert colour unconditionally — a red alarm saying nothing
   was wrong. The uploads row now goes red only when something is actually waiting, and the separate
   "Could not be sent" row carries the real alarm.
6. The search hint was ellipsized. Shortening it once fixed it at normal text and broke it again at 1.3×
   (`Search tools — try "photo" or "…`), which is a broken-looking field rather than a hint. It is now
   simply `Search tools`, and the example words live in the tool names themselves. **Re-photographed later in
   the same session** on the tools screen in light at `font_scale 1.3` — see "Appearance matrix" below.
7. **Searching emptied the whole tools screen.** A card-level flag hid everything the moment the box had
   any text, so the page appeared to have no tools at all. That flag is gone: a card leaves the page only
   when nothing inside it survives the filter, and the way out is pinned (`Back to monitoring` now carries
   `staysVisible = true`, as every Stop-weight control already did). Re-checked with `photo`, with `stop`,
   and with the box cleared.
8. The connection row could read `Not confirmed: Server accepted this phone` — quoting an older success as
   though it were the present state. It now reads `Not confirmed. Last report said: …`.
9. `8 item(s)`, `2 request(s)`, `4 frame(s)`, `3 upload(s)` were shown to the owner as `(s)` placeholders.
   One tested helper (`PlainStatus.count`) now covers all six sites.
10. **The home screen nagged about a server that was answering it.** `ConnectionDiagnostics.verified()` only
    counted a manual "Check the connection now" tap within the last two minutes, and an acknowledged upload
    wrote a different key entirely. So with samples landing at 20:26:54, 20:31:28 and 20:36:37 the phone still
    read `Connected to your dashboard → Not confirmed`, and the headline stayed "Needs your attention" with a
    `Check the connection again` button, while the dashboard was receiving data the whole time. Only the phone
    could show this, because on the PC the two minutes are a constant nothing ever changes. A delivery now
    proves the connection for `deliveryWindowMs` = three sample periods with a 15-minute floor, and a manual
    probe still counts for its own two minutes. `ConnectionDiagnosticsTest` covers the window against the
    phone's own cadence (1, 5, 10 and 240 minutes) and the inside/boundary/outside/fresh-boot cases.
    Re-checked on the phone — see "Defect 10, re-checked" below.
11. **One dashboard location request stranded the other on-screen tools.** `FeaturesActivity` keeps a single
    `requestId` slot so one consent dialog is open at a time, and every path that answers a request clears
    it — except location, because the fix is uploaded by the background loop (`FeatureBridge.queueLocationEvent`)
    and the screen never hears about it. So after the location request had already returned `completed`, the
    next on-screen request failed with `Phone busy with another request`, and a boundary request was refused
    that way on the real phone at 17:32:54 while the dashboard believed nothing was running. The screen now
    releases the slot as soon as sharing has started, since what is left to do happens in the service. Not
    coverable by a JVM test: it is `Activity` state, and it was only visible because two requests were sent
    one after the other on a handset. Re-checked on the phone — see "Defect 11, re-checked" below.
12. **The last guided-setup screen had no primary action.** Every button on step 3 was drawn
    `ScreenKit.Weight.QUIET`, including **Finish — open home**, so the way *out* of setup looked identical to the
    way *back* and the one tap that finishes was the least prominent thing on its own screen — the opposite of the
    brief's "fewer confusing steps". `PermissionSetupActivity.kt:161` now gives that control the weight its label
    already claims (`PRIMARY` when `stage == 2`) while the earlier, genuinely-abandoning "Finish later" label keeps
    QUIET. Not a JVM-testable defect: it is a drawing weight on a screen no test renders. Re-checked on the phone —
    see "Guided setup after the fix" below.

A **thirteenth finding** came out of photographing the earlier steps and is **not** in that list because it is
deliberately not fixed: every sentence the setup screens speak — all-clear included — is painted in the error
colour. It is recorded under "The all-clear message wearing the alarm colour" below, with the reason it is waiting.

**What the phone said when the laptop stopped answering, and when it was busy.** Twice the dev tunnel died
(`adb reverse` ends with the ADB session, not with the app). Both times the phone stopped advancing
"last delivery" and said so, and the eight items already in the queue stayed reported as unsent instead of
being counted as delivered. Later, while a Gradle build had the laptop's load average at 24, a connection
probe timed out and the phone reported `Server is slow or waking up. Keep the app open and retry.` — the
truth: the server was up, it just could not answer in fifteen seconds. Nothing in this session ever showed a
fake success. The one behaviour worth knowing before relying on dashboard requests: after repeated failures
the queue loop backs off to `cadenceMs` = 20 s, 40 s, 80 s, 160 s, capped at 300 s, so a queued request can
sit for up to five minutes after the connection comes back, while the home screen correctly says monitoring
is on. Requests expire after ten minutes, so the cap is smaller than the expiry and nothing is dropped.

**Dashboard request pipeline, driven end to end.** Seventeen requests were queued into the **local dev** SQLite
only — nothing on Render was touched — and every one came back with an answer that matched what the phone
actually did. Times are UTC as stored by the server.

| Queued action | Answer | Evidence |
| --- | --- | --- |
| `request_status` | `completed` 24 s later, "Phone status uploaded.", `result_ref event:541` | a real `device_status` row |
| `request_settings` with `{}` | `failed` — "The rules were missing or this phone does not recognise them, so nothing changed." | nothing was rewritten |
| `request_settings` with the phone's own rules | `completed` — "Rules applied on the phone: health samples every 5 min · the dashboard may run audio, geofence, location, photo, scan, screenshot" | `result_ref event:542` |
| `request_scan` with Bluetooth off | `failed` — "Enable Bluetooth before running a nearby scan." | refused, not faked |
| `request_photo` | `completed` — "Photo uploaded to the dashboard Files." | `capture_7121722602884077780.jpg`, `image/jpeg`, **493,496 bytes** with an Exif header |
| `request_audio` | `completed` — "Recording uploaded to the dashboard Files." | two `audio/mp4` chunks, **164,100** and **77,536 bytes** |
| `request_live_view` | `declined` — "A dashboard rule keeps live view off on this phone." | zero `live_frame` rows; the camera never opened |
| `request_live_view_stop` with no stream | `failed` — "This phone is not streaming a live view." | the stop path answers instead of hanging |
| `request_teleport` (invented) | `failed` — "Phone build does not support action request_teleport." | the server's own name echoed back, no guess |
| `request_scan`, Bluetooth **on** | `completed` — "Nearby scan uploaded." | `event:548`, two real SSIDs with BSSIDs, `bluetooth: []` |
| `request_screenshot` (owner approved Android's capture dialog) | `completed` — "Screenshot uploaded to the dashboard Files." | `screenshot-1791480495838.jpg`, `image/jpeg`, **111,859 bytes** |
| `request_location` | `running` — "waiting for the first GPS fix." for 2 min, then `completed` | `event:550` accuracy 9.5 m; sharing then kept running and sent 10 fixes in that window (accuracy 7.1–9.8 m, lat 32.2155 lon 70.3878) |
| `request_geofence`, sent while the leaked location slot was still held | `failed` — "Phone busy with another request" | **defect 11**; the dialog never appeared |
| `request_location` again, after the fix was installed | `running` 17:51:16 → `completed` 17:52:17, "Location fix uploaded to the dashboard." | `event:600`, accuracy 7.1 m — the slot was released, which is what the next row depends on |
| `request_geofence` "test-area-near-me" (32.2155266, 70.387785, r=150 m), sent 88 s later | `completed` 17:54:50 — “test-area-near-me” is watched on this phone. | **defect 11 re-checked**: the Allow/Decline dialog opened on the tools screen, the owner's own tap was Allow, and `event:633` records `{"geofenceId":"test-area-near-me","transition":"ENTER"}` two seconds later |
| `request_geofence` "test-area-decline-me" | `completed` 18:08:12 | meant as the refusal test; the owner tapped Allow by mistake and said so ("sorry I clicked on allow") — recorded as `completed`, not rewritten. `event:731` ENTER |
| `request_geofence` "test-area-please-decline", re-sent | `declined` 18:09:44 — "Declined the dashboard's area on the phone." | the first refusal tonight that came from the owner's own finger rather than a rule, a missing permission or a busy phone |
| the owner's removal of both areas from *See or remove watched areas* | no rows | `fleet_location_tracker.xml` afterwards contains only `tracking_config` — the `geofences` key is gone |

Two more things this proved by accident. The photo and microphone rows are the first **real** camera and
microphone captures ever observed on a handset in this project — everything before this was unit tests. And
the live-view refusal shows the two gates are independent and both honest: the phone-side owner opt-in is on
(`live_view_allowed: true` in the status payload), while `tools_allowed` has never contained `live_view`, so
the dashboard rule wins and nothing streams.

The queue also survived the busy laptop above: samples timestamped 16:44:36 and 16:49:36 were delivered in
order at 16:56:55, once the build stopped saturating the CPU — nothing was dropped. The same thing happened
again at 18:13:02, when six fixes timestamped 18:12:29–18:12:59 arrived together as `event:775`–`780` after a
few seconds of stall. One honest wrinkle in that second burst: `event:774` (fix timestamp 18:12:34) was already
accepted at 18:12:41, so the fix timestamped 18:12:29 reached the server five seconds *after* a newer one — the
queue never loses or fabricates a sample, but under a stall it can deliver them in the order they were retried
rather than strict time order. One request of mine is still `pending` in the dev database (`3e47a02c`,
queued 16:40:19 before the reinstall) — it was never served and the app did not pretend otherwise. In an
earlier pair, requests queued on 6 October were still answered as `expired` on 7 October rather than
`completed`, and one row whose `status` column a test script of mine filled with JSON was simply never served,
which is the `pending`/`delivered` filter holding.

**Defect 10, re-checked on the phone.** After the reinstall, the last manual probe was at 16:52:33 and the
home screen was read at 16:58:01 — 328 seconds later, well outside the two-minute probe window — with an
acknowledged delivery at 16:56:58 in between. The headline read **"Connected and monitoring"**, the subhead
"Sharing is on and your server is hearing from this phone. Last confirmed delivery: 8 Oct, 21:56:58." and the
row read "Yes — confirmed by the server", with no tap from anyone. Before the fix the same state produced
"Not confirmed" and a "check again" button. The eight genuinely unsent items were still reported as unsent in
the same frame, so the fix did not turn the screen into a green light.

**Defect 11, re-checked on the phone.** The sequence that produced the bug was sent again on the rebuilt APK:
`request_location` `9af48e6d` at 17:51:16 went `running` — "Waiting for the owner to approve this on the
phone." — then `completed` at 17:52:17 with a real fix (`event:600`, accuracy 7.1 m). 88 seconds later
`request_geofence` `671a4e54` was queued, and instead of `Phone busy with another request` it reached the
**Allow/Decline dialog on the tools screen**, which the owner tapped with their own finger; the request came
back `completed` — “test-area-near-me” is watched on this phone. — and two seconds after that the phone sent
`event:633` `{"geofenceId":"test-area-near-me","transition":"ENTER",…}`. One more area was registered the same
way and then removed through *See or remove watched areas*; `fleet_location_tracker.xml` afterwards holds only
`tracking_config`, i.e. the `geofences` key is gone rather than left stale. So the slot leak, the refusal it
caused, the boundary consent dialog behind it, and the removal path are all now measured on a handset. A JVM
test could not have caught any of it: the leak is `Activity` field state plus a background-loop answer, and the
bug only appears when two dashboard requests are sent in a row on a real device.

**Notification shade, read out of the system rather than off the app's own screen.** After the microphone
capture, `dumpsys notification --noredact` showed two live records for this package. The ongoing one is on
channel `system_health_monitor`, id 1001, `flags=0x68` — `FLAG_FOREGROUND_SERVICE | FLAG_NO_CLEAR` — with one
action, titled "System Health", text "Active". "Active" is `NotificationPresentation.compactText()` doing
what it is written to do: the detailed line only appears when the owner turns on *Show details in the ongoing
notice*, or while a live view is streaming. The second record is the capture alert, channel `capture_alerts`,
title **"Recording captured for your dashboard"**, text "Saved on the phone for upload. Turn these alerts off
in Notification settings." — so a capture the owner could not see happening was announced by the phone itself,
on the handset, for the first time. The earlier photo alert had already been replaced under the same id (3040)
by this one, which is the quiet, grouped design rather than a lost notice. Both channels report
`importance=2` (low), so neither makes a sound.

**Appearance matrix actually photographed.** Every cell is now filled: Home in light and in dark, at the
phone's normal text size and at `font_scale 1.3`; and Device tools in light and dark at both sizes. The last
cell closed at 22:25–22:31 local — Device tools in light at 1.3× — where the shortened `Search tools` hint
was finally seen un-ellipsized at enlarged text (closing defect 6), the camera card's four buttons and the
live-view paragraph reflow without truncation, `Stop the recording` and `Cancel the active request` wrap
instead of clipping, and the `Allow the dashboard to start a live camera view` label was checked by node
bounds (`x` 124→956 inside a 1080-px screen) rather than by eye, so it wraps rather than runs off. Headings,
card titles, secondary labels, alert rows, the camera spinner, checkboxes, the fold, the search box and every
button weight render with readable contrast. Dark mode was forced with `cmd uimode night yes` and returned to
the phone's own auto setting afterwards.

**Guided setup, finally seen on a handset — by accident, at 11:36 PM.** The owner navigated there themselves,
which is the only way this screen can be opened (it is not exported, and no scripted tap was attempted). They
were holding the phone **landscape**, so the first render of the guided setup ever captured is also the first
rotation check: `SYSTEM HEALTH · Guided setup` / **Step 3 of 3 · Check and start** / the "You can leave at any
point and come back…" paragraph, which wrapped onto two lines at 2400 px wide with no truncation, then the
*Your progress* card listing *Connect this phone — Done*, *Choose what this phone may do — Done*,
*Check and start — You are here*. After being asked to scroll down and turn the phone upright, the bottom half
was captured in portrait (1080×2400): the six check rows — `Connected to your dashboard: Yes`,
`Last upload the server accepted: 8 Oct, 23:40:17`, `Monitoring: On — sharing with your dashboard`,
`Status notifications: Allowed`, `Screen text reader: Enabled in Android`,
`Notification access: Enabled in Android` — and all four buttons (`Review permissions`,
`Check the connection again`, `Back to the previous step`, `Finish — open home`) laid out at x 96→984, stacked,
nothing clipped. That "23:40:17" was then checked against the server rather than believed: `event:915` is a
`system_health` row the dev backend accepted at **18:40:18.517Z UTC = 23:40:18 local**, one second off the
phone's clock. So the setup screen's claim about what the *server* accepted is backed by the server's own row,
not by the button having been pressed.

**That finding is now defect 12, fixed with the owner's agreement, and the fix is photographed.** The owner was
asked directly whether to trade a reinstall — which kills `CoreService` and costs them another tap on Start
monitoring — for the button change, and chose the fix. `assembleDev` with `testDevUnitTest` came back BUILD
SUCCESSFUL in 3m8s, 129 checks over 19 classes with 0 failures (result XMLs stamped 23:53), the APK was
2,977,276 bytes, `adb install -r` reported Success and `adb reverse tcp:3000 tcp:3000` was re-issued because it
dies with the ADB session. The owner then started monitoring themselves; the dev database shows the proof rather
than the claim — `event:924` at 19:01:20.098Z, `925` at 19:06:13.760Z, `926` at 19:11:18.298Z and `927` at
19:16:21.804Z, four `system_health` rows about five minutes apart after a service that had been dead for two
minutes. At 00:17 the bottom of step 3 read `Connected to your dashboard: Yes`,
`Last upload the server accepted: 9 Oct, 00:16:21`, `Monitoring: On — sharing with your dashboard`,
`Status notifications: Allowed`, `Screen text reader: Enabled in Android`,
`Notification access: Enabled in Android`, and that second line matches `event:927` to the second
(19:16:21.804Z UTC = 00:16:21 local). Below it **Finish — open home** is now a filled blue button with white
bold text at x 96→984, y 1975→2137 — 162 px tall, against the 144 px of the three grey buttons above it
(`Review permissions`, `Check the connection again`, `Back to the previous step`). The screen now has exactly one
thing that looks like the point of it.

**Guided setup steps 1 and 2, rendered on a handset for the first time — 00:04 to 00:14.** Reached only by the
owner's own navigation; every capture here is `adb exec-out screencap` plus a `uiautomator` dump, nothing tapped
by script. Step 2 came first by accident — reopening the wizard landed there, not on step 1 — and its top half
showed `Step 2 of 3 · Choose what this phone may do`, the full "You can leave at any point and come back…"
paragraph with no truncation, and the *Your progress* card reading *Connect this phone — Done / Choose what this
phone may do — You are here / Check and start — Up next*. Its bottom half, after being asked to scroll, held the
**"What this phone allows today"** card with all seven rows inside x 96→984 (Status notifications, Camera,
Microphone, Location, Nearby Bluetooth devices all **Allowed**; Screen text reader and Notification access
**Enabled in Android**), then `Optional system access` (y 1519→1663, full width at 48→1032), `Back to the
previous step` (1735→1879) and `Finish later — return to the app` (1993→2137). Mid-scroll, the five permission
toggles are all ticked with their explanations intact and the PRIMARY button
**"Allow the permissions I chose and continue"** measures y 1032→1228 — 196 px, filled blue, its label wrapping
onto two lines without clipping. Step 1, which no one had ever seen on any device, rendered clean top to bottom:
title, intro, progress card, the **"Connecting this phone"** emphasis card,
**"Continue to permissions"** filled blue at y 1826→1988, then `Connected to your dashboard` →
**"Linked to your dashboard. You can continue."** in ordinary black ink, and with its help section opened
(`Hide this help`, y 1129→1273) both explanatory paragraphs plus **Check the connection again** at y 1752→1896.
Nothing on any of these screens was cut off, overlapped or unreadable at 1080×2400, light theme, font scale 1.0.

**The all-clear message wearing the alarm colour — a thirteenth finding, not fixed.** Below step 2's primary
button sits a card saying *"Nothing else to ask for. Missing permissions can be allowed later from this screen."*
— an all-clear, drawn in orange-red, the same ink the app uses when Android refuses something. The cause is in
the kit rather than the screen: `ScreenKit.kt:240` makes `replaceLines` — the helper that swaps the come-and-go
sentences on a card — call `setTextColor(alertInk())` for **every** line, and `PermissionSetupActivity.kt:76-80`
routes all of its feedback through it. So "Allowed. Nothing has been recorded or shared by it.",
"Start requested…", "Linked to your dashboard. You can continue." on a step that uses the fact-row path, and a
genuine refusal are painted identically, and the reader cannot tell which sentence needs them. That is the brief's
"clearer information" failing in the one direction a screenshot can show. Left unfixed for the same reason as
before: it is a colour edit, it needs a reinstall, and the owner has already spent one on the button tonight.

**One string on that screen over-promises.** The intro says *"this screen opens on the first step that still
needs you"*, and tonight it opened on **Step 2** with Step 1 already connected and every Step 2 permission
already granted — because `onCreate` (`PermissionSetupActivity.kt:89`) restores the persisted `stage`, which
`goTo` (line 252) writes on every navigation, so the wizard reopens where you left it. The skip-already-done
behaviour the sentence suggests is real but lives elsewhere — in `advance()` and `PermissionPlan.shouldSkipStep`,
which stop Android being asked again for a permission it already gave. Recorded as a wording finding, not a
behaviour one: nothing is skipped that shouldn't be.

**How many fixes the phone actually produced.** `dumpsys location` accounted the app's GPS registration as
`min/max interval = 5s/5s, … locations = 352`, while the dev database holds **344** `type: location` rows for
8 October (10 from the 17:29 run, 300 from 17:51–18:25, the rest either side). So of the fixes Android handed
this app, all but eight reached the laptop — and the eight are **unattributed**, not explained: no row that
arrived was worse than 50 m, so the `maxAcceptedAccuracyMeters` filter cannot be shown to have discarded them,
and the tracker's own buffer (`Channel(capacity = 512, onBufferOverflow = DROP_OLDEST)`) would only shed
samples after 512 unsent ones. Nobody has counted which of those two, or what else, took them. The queue
reported `pending_uploads: 1` at 18:33, so at most one was still in flight. Reported as a gap, not as a loss
claim.

**What the last minute of the session said.** The owner's own taps at 23:24 switched location sharing off —
`request_status` `probe-d097b009` came back in 22 s with `monitoring: true, sync_ready: true,
location: false, location_approved: false, pending_uploads: 1`, and `fleet_location_tracker.xml` holds no
`geofences` key. The owner could not say what they tapped, so the start path was not re-tested: it had already
been proven three minutes earlier at 17:51, when the owner's own approval produced fixes continuously for the
next 34 minutes, which is why a one-second GPS registration at 23:24:52 stays an unresolved observation rather
than being written up as a defect.

**Tool search, measured.** `photo` keeps the photo tool, the pinned state rows and the Stop controls;
`stop` keeps `Stop the recording`, `Stop the live camera view now`, `Stop sharing location`,
`Cancel the active request` and `Back to monitoring`, and also `Take one screenshot` because its own
explanation ends "one capture, then it stops" — a match on real wording, not a filter bug. Emptying the box
restores all seven cards.

**Re-measured on the build PC after these fixes:** `:app:assembleDev` and `:app:testDevUnitTest` →
BUILD SUCCESSFUL, **129 checks across 19 classes, 0 failures, 0 errors, 0 skipped** (re-counted straight from
the 19 XML files in `app/build/test-results/testDevUnitTest/`; 127 across 18 before the tenth defect was fixed,
the extra two being the new `ConnectionDiagnosticsTest`). Those reports are timestamped 22:41 local, i.e. after
the `FeaturesActivity.kt` edit at 22:35 and the APK at 22:40, so this count does cover the eleventh fix — and
the rebuilt APK is the one the phone ran the location/boundary sequence on. `:app:lintDev` was then re-run with
`--rerun-tasks` after deleting `lint-results-dev.*`, because a plain re-run had finished BUILD SUCCESSFUL while
Gradle marked `lintReportDev` **UP-TO-DATE** and left the 20:52 report untouched — a passing task that measured
nothing. The forced run executed all 27 tasks in 5m43s and rewrote the report at 23:31 local: **0 errors,
82 warnings**, in the same pre-existing categories as before (`UseKtx` 47, `InlinedApi` 17, `ObsoleteSdkInt` 8,
`StaticFieldLeak` 5, `GradleDependency` 2, `OldTargetApi` 1, `NewerVersionAvailable` 1, `SetTextI18n` 1), and
byte-identical in size (94,201 B) — so the last two fixes introduced no new lint finding and none of the old
ones went away either. A Gradle build on this laptop is heavy enough to make the phone's connection probes time
out, so builds run between handset checks, not during them.

**Still not verified.** Guided setup has now been photographed on the handset at **all three steps**, top and
bottom, in portrait and (for step 3) landscape — but **no part of it has been seen in dark mode or with the
system text size enlarged**, in any step, and neither has the "Something Android did not allow" refusal card or a
mid-wizard exit and return. Those need the owner's finger, because the screen is not exported and no scripted
tap will be attempted. TalkBack was not run — reading order, heading announcements and the polite live regions
are designed for but unmeasured, so START-HERE.md no longer claims the app "speaks properly to TalkBack". Also
unmeasured: **a live view that is actually allowed to run** — the rule gate declined it, so a fresh count of
the dev database still shows **0** `live_frame`-type rows; not one frame has ever crossed the wire on any
device. The phone-side tick is on and the dashboard's `tools_allowed` has never listed live view, and widening
that rule is the owner's call, not a test step. Still unmeasured too: an owner **cancelling the Android
screenshot dialog** — the proxy dialog was shown twice tonight and approved twice (the owner said so), so its
`declined` wording remains unread; the shared-files list and *Clear waiting uploads* behind the kept-aside
items; the microphone and screenshot buttons on the tools screen as tapped by hand (their dashboard-requested
equivalents are the measured ones); the two rebuilt screens in landscape (guided setup is the only one now seen
sideways); small screens; dark mode or enlarged text on guided setup. Reboot, long Doze idle and the
release-signed APK against Render remain as the older records describe them.

**Closed since those lines were first written.** The **geofence consent dialog** was reached on the rebuilt APK
and photographed at `font_scale 1.3`: title *"Your dashboard asks you to watch an area"*, message
*"test-area-near-me / 32.2155266, 70.387785 · 150 m radius / Allowing this makes the phone track that area in
the background. Crossings reach your dashboard only while location sharing is running, and you can remove the
area under “See or remove watched areas”."*, with `Decline` (bounds x 501→758) and `Allow` (x 758→969) both
inside the 1080-px screen and unwrapped. And an owner **declining** something is now measured for real:
`request_geofence` `ba41d23f` → `declined` 18:09:44, "Declined the dashboard's area on the phone.", from the
owner's own tap on that `Decline` button. (A file-picker cancellation had already been recorded once before
this evening — `request_files` → `declined`, "Capture or file selection cancelled." — but every refusal seen
earlier tonight still came from a rule, a missing permission or the busy-phone defect.)

**This does not make the app release-ready.** It is a `dev`-variant debug build pointed at a laptop, and the
release artifact has still never been installed on a phone.

---

# Validation — interface rebuild (source), 2026-10-08

Measured on the build PC right after the last UI edit. No phone or emulator was attached, so nothing here
is evidence about appearance, screen readers or a sensor. The live camera view record below is that
feature's own run and stays accurate for it; the check counts changed because this pass added 34 checks.

- Android: `:app:testDevUnitTest` → BUILD SUCCESSFUL, **127 unit checks across 18 test classes, 0 failures,
  0 errors, 0 skipped**, counted from `app/build/test-results/testDevUnitTest/` rather than a console line.
  93 were passing before this pass.
- New Android coverage, all Android-free logic so it runs on the JVM: `HomeOverviewTest` (13 — each blocker
  picks its own primary action, a running session with nothing to fix offers no button, an unacknowledged
  first sample is never reported as delivered, the four calm service lines stay out of the attention card
  while any other line is promoted to it, a live stream / unsent items / a stalled queue and an available
  update each come with the way out, Stop rows appear only with something to stop, and rules, reports and
  waiting requests are listed only when they exist); `ToolCatalogTest` (11 — every id tagged on the tools
  screen exists in the catalog and ids are unique, every category has a tool, every control has a name and
  an explanation, blank search shows everything, "stop" keeps every way to stop something, extra words
  narrow instead of widening, an unmatched word hides the card rather than showing all of it, and an unknown
  dashboard action keeps the server's own name instead of a guess); `PlainStatusTest` (10 — all eight ledger
  states and an unknown one, permission names and the Settings route after a refusal, a reason for every
  setup step, on/off pairs, the three live-view states, singular and plural counts, absent rules and
  schedules saying so, and a saved local file never described as uploaded).
- Android: `:app:assembleDev` → BUILD SUCCESSFUL, `app/build/outputs/apk/dev/app-dev.apk`.
- Android: `:app:lintDev` → BUILD SUCCESSFUL, **0 errors**, 82 non-blocking warnings in the pre-existing
  categories (`UseKtx` 47, `InlinedApi` 17, `ObsoleteSdkInt` 8, `StaticFieldLeak` 5, dependency notices).
  Nothing new in kind: the kit resolves colours from the theme and holds no static context.
- Files: 4 new in `app/src/main/java/com/example/systemhealth/` (`ScreenKit.kt`, `HomeOverview.kt`,
  `ToolCatalog.kt`, `PlainStatus.kt`), 2 new resources (`res/values/themes.xml`, `res/values-night/themes.xml`),
  3 rewritten screens (`MainActivity.kt`, `FeaturesActivity.kt`, `PermissionSetupActivity.kt`), 1 manifest
  line (the app theme), 3 new test files. No service, receiver, permission, `build.gradle.kts` dependency,
  backend or dashboard file was touched.
- Preserved by inspection: enrollment (automatic and manual), every consent dialog and its wording, the
  two Stop controls, all tool actions and their request states, the dashboard-rule semantics, the single
  file outbox, all preference keys (`permission_setup`, `feature_options`, `remote_policy`,
  `system_health_settings`, the screen-monitor prefs) and the device identity. `docs/UI-BEFORE-AFTER.md`
  maps each old control to where it is now.
- Caught by the compiler and fixed: a data class nested inside an inner class, `DisplayMetrics.fontScale`
  (the scale is on `Configuration`), `android.R.attr.colorSurface` (not a framework attribute — the card
  fill now resolves `colorBackground` with a measured fallback), and
  `NotificationManagerCompat.getEnabledListenerPackages()`, which takes a `Context` and was being handed a
  package name.
- Still unverified without a handset: light and dark appearance and the resolved contrast, large system
  font layout, small screens, long device names, empty lists and keyboard behaviour over the search box,
  TalkBack order, heading announcements and the change-gated redraw, the refused-permission guidance
  rendering after a real decline, setup resuming from a mid-wizard exit and after rotation, and every
  capture, sharing and upload path. A green build says nothing about those.
- This is a `dev`-variant debug build against a local server. It is not signed for release and must not be
  called release-ready on the strength of these checks.

## Previous validation record: live camera view

Measured on the build PC the same day the feature was written. No phone was attached, so nothing below
is evidence about a camera, a sensor light, or a notification.

- Android: `:app:testDevUnitTest` → BUILD SUCCESSFUL, **93 unit checks across 15 test classes, 0 failures,
  0 errors, 0 skipped** (read from `app/build/test-results/testDevUnitTest/`, not from a console line).
  Only the `dev` variant was re-run after this feature; `testDebugUnitTest`, `assembleDebug` and
  `assembleRelease` have not been re-run since, so the 0.5.0 rows below still describe those.
- Android: `:app:compileDevKotlin`, `:app:assembleDev` and `:app:lintDev` → BUILD SUCCESSFUL, **0 lint
  errors**, 99 non-blocking warnings in the same pre-existing categories (the new `StaticFieldLeak` row
  for `LiveStreamBridge.session` matches the three the verified `HeadlessCapture` already has: it holds
  an application context, and the session reference is cleared when the stream ends).
- A review pass over the new code found three defects, all now fixed and rebuilt: the session's time and
  byte limits were only checked **when a frame arrived**, so a camera that delivered one frame and then
  went quiet without reporting an error would have held the lens and blocked every later live view until
  monitoring stopped (a deadline job now ends it, reusing the same `limitReached` wording); the started/
  ended alerts and the notice refresh ran outside the throw-safe queue, so a failing notification could
  strand an open camera; and the flag that decided which frame answers the request was a read-then-write
  shared with the stop path, which could answer one request twice. `LiveViewPolicy.overBudget` was
  removed as dead once `limitReached` proved to be the only limit anyone consults.
- New Android coverage, all pure logic: `LiveViewPolicyTest` (10 — cadence gate, the 2 fps the owner is
  told, preview size chosen inside VGA with a smallest-usable fallback, zero sizes refused, byte and
  time limits and which reason is stated first); `CameraOwnerTest` (6 — first job wins, a live view
  cannot take the lens from a photo, only the holder frees it, a late teardown cannot cut another job
  off, and 8 threads starting at once produce exactly one winner); 5 rotation checks added to
  `CameraSelectionTest` (Android's 0..3 rotation codes, front turns with the display and rear against
  it, always a quarter turn); 2 added to `DeviceCommandRouterTest` (a live view is a headless capture,
  its stop command is silent so it can never queue behind the lens it must free); 1 added to
  `QueuePolicyTest` (a stale frame may be shed to clear a full queue, a photo or document may not).
- Backend: `npm test` → **40 checks pass, 0 fail**. The new one covers the whole server-side shape of a
  stream: arguments refused with 400 (the phone owns the rate and limits), the start request's wording
  naming the owner allowance and the 120-second ceiling, `live_view` accepted as a rule, a `live_frame`
  upload stored and linked as the proof that answers the request, the stop request accepted, both rows
  ending `completed`, and an unknown `video` kind still refused. This run also caught a test I had
  broken: my new upload made the later retention check count two files instead of one, so the live-view
  check now deletes its own frame the way the dashboard would.
- Dashboard: `npm run build` passes (387.02 kB JS bundle).
- Not verified: whether a real camera opens, whether a frame is legible or upright, front versus rear on
  this handset, the indicator and notice lines, every refusal message, the 10-second no-frame watchdog,
  the deadline job that ends a stalled session at 120 s, the 6 MiB stop, behaviour when the vault or the
  queue is full, airplane mode mid-stream, and whether the phone can start a second live view immediately
  after stopping one. `START-HERE.md` and `docs/SILENT_VERIFICATION.md` carry those as phone checks to run.
- No commit, push, deployment or production/Render data touched. Not in the delivered 0.5.0 APK.

# Validation — Android update 0.5.0

Checked on 2026-10-07 on the build PC. The Android rows below were first measured on the PC, then a physical-phone session was run the same evening; see "Physical phone session" for what a real handset confirmed and what still has no evidence.

- Android: `:app:testDevUnitTest`, `:app:testDebugUnitTest`, `:app:assembleDev`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDev`, `:app:lintDebug` — BUILD SUCCESSFUL. **49 unit checks pass in each variant, 0 failures and 0 errors** across 11 test classes. Lint reports **0 errors**; 100 non-blocking style/target-SDK warnings remain (three fewer than before the unused-code pass).
- New coverage in this version: `QueuePolicyTest` (outbox shedding, batch size, retry retirement), `RemotePolicyTest` (a rule list that was never sent leaves the phone's own choices in charge, an omitted tool stays available, an empty list stops every governed tool, unknown names are dropped, cadence bounds), `ReportScheduleTest` (cadence boundaries, a never-run report becomes due, only report-only tools can repeat), `DeviceCommandRouterTest` (the rules channel stays silent and routable), `UpdateAwarenessTest` (only a strictly newer version is mentioned, `-dev` suffixes do not compare as upgrades).
- Package `com.example.systemhealth`, versionCode 8, versionName 0.5.0, minSdk 26, targetSdk 35. The version now comes only from `android/version.properties`.
- Release build runs R8 and produces `app-release-unsigned.apk` — unsigned, because no `android/keystore.properties` and no CI signing environment exist here. Its deobfuscation map is written once, to `app/build/outputs/mapping/release/mapping.txt`; the duplicate `-printmapping` line that used to drop a copy into `android/app/` is removed. An unsigned release installs nowhere, which is intended until you add your own key.
- Backend: `npm test` → **39 passing checks** on SQLite, covering the previous enrolment/telemetry/file/request surface plus the rules payload validation, the full request lifecycle, dashboard session revocation surviving a restart, and the advertised APK release. The PostgreSQL branch of the suite runs only with a live `TEST_DATABASE_URL`, which was not available; no real database was queried.
- Dashboard: `npm run build` passes (387.58 kB JS bundle). A build without `VITE_SERVER_URL` falls back to `http://localhost:3000` and cannot reach a hosted backend.
- Rules flow was exercised end to end in a real browser against a **throwaway** backend on a spare port with a temporary SQLite file and self-generated test credentials: send rules, the phone's `device_status` echo, and a switched-off tool returning `declined` with the rule named. This found and fixed a genuine dashboard bug where unchecking a tool sent only that tool. No `.env`, `.env.dev`, production database or Render setting was read or changed, and the scratch process, database and files were deleted afterwards.
- Consent surface retained: monitoring still starts from the visible app, camera/microphone capture still runs only on the foreground-service subtype that visible start declared, screenshot/file/location/geofence actions still ask on screen, Stop still cancels, required foreground notices stay visible, and dashboard rules can only remove capability — never grant a permission or start monitoring. The visible-start rule, the ongoing notice, the decline-with-rule-named answer and the rules echo were all confirmed on the handset below; the rest stays inspection-only.
- Not verified on hardware: reboot and boot-service start, accessibility and notification-listener behaviour on the Camon 20, scheduled reports surviving a long Doze idle, the microphone indicator's exact timing, whether the captured JPEGs show the expected framing, and the two refusal paths added after the phone session (the notification-switch message and the "start monitoring first" answer, which cannot be provoked on demand while the poll loop is dead). `START-HERE.md` lists the phone checks for each.
- No commit or push was made. The working tree holds the changes.

## Physical phone session — TECNO Camon 20, 2026-10-07 evening

A real handset (TECNO CK6n, Android 14 / SDK 34, HiOS) ran `app-dev.apk` versionName `0.5.0-dev`, package
`com.example.systemhealth.dev`, against the local development backend over wireless ADB with a
`tcp:3000` reverse tunnel. Every claim below is read back from the server's SQLite ledger or the phone's
own files, not from the screen. Nothing was done against Render or production, and the phone enrolled
itself into the throwaway development database only.

- Installed as an update three times with `adb install -r` over the same app data. Enrolment, permission
  choices and the saved connection survived every reinstall; the phone never had to be paired again.
- Automatic enrolment worked without an approval click: one device row, `enabled`, created 15:54Z.
- Telemetry: 18 `system_health` and 8 `device_status` uploads. A dashboard status request answered in
  about 6 seconds at best, and the phone polls every 10 seconds (5 with a backlog).
- Headless camera: 16 photos captured with the app in the background, each around 480 KB, uploaded to
  Files. Android's own sensor light stayed on during each capture.
- Headless microphone: one 15-second recording, request `16:48:23Z` answered `completed` at `16:48:54Z`
  with a 79,846-byte `.m4a` in Files.
- Capture queueing, measured after the fix: three photos sent inside 1.4 seconds (`16:57:48.370`,
  `:49.082`, `:49.725`) all reached `completed` at `17:05:01`, `:06` and `:12` — one per poll, about six
  seconds apart, none refused. A later burst of four behaved the same way.
- Other tools on hardware: location fix (3), security audit report, settings backup (2), nearby scan, and
  a file pick the owner cancelled, which answered `declined — Capture or file selection cancelled.` Those
  three tools were removed from the app later the same day at the owner's request (see `docs/CHANGES.md`),
  so this is the only record of them running on a real handset.
- Honest refusals, not silent ones: a scan with Bluetooth switched off answered
  `Enable Bluetooth before running a nearby scan.` instead of uploading an empty list.
- Rules: 6 `request_settings` completed, each echoing the applied rules back as a `settings_applied`
  event; a photo sent while the rule kept photo off answered
  `declined — A dashboard rule keeps photo off on this phone.`
- Scheduled reports: ticking status, scan, audit and backup on the phone produced three uploads inside the
  same second and the backup 19 seconds later, with no dashboard request behind any of them; the phone's
  `report_schedule` preferences carry matching `last_run` stamps.
- Two real defects found only because the handset was attached, both fixed and rebuilt the same evening:
  `HeadlessCapture` never cleared its busy flag after a **successful** capture, so one delivered photo
  made every later camera and microphone request fail with "Phone is already finishing another capture."
  until monitoring stopped (this is the source of all 7 `request_photo` failures above), and a second
  capture arriving in the same poll was answered as a failure instead of waiting for the lens.
- Also corrected the same evening: the development launcher now passes the release-announcement keys
  through, and the backend accepts a comma-separated origin list so a local dashboard reached as
  `localhost` or `127.0.0.1` no longer shows a misleading "Cannot reach the server".
- Reproduced a real setup trap: with the app's notification switch off, `CoreService` refuses to run at
  all — correct, because monitoring must keep a visible notice — but the phone still toasted "Starting
  monitoring. Check the ongoing notification." Dashboard requests then sat at `delivered` forever with no
  explanation. `requestMonitoringStart()` now checks `areNotificationsEnabled()` first, says what is
  missing and opens that settings screen. **This last change is built and installed but its message was
  never displayed on the phone**: the ROM rejects `cmd appops set … POST_NOTIFICATION deny`, so the
  blocked path could not be re-created on demand.

## Validation — Android update 0.4.0

- Android assembleDebug, lintDebug and testDebugUnitTest passed. Lint has zero errors; nonblocking SDK/style warnings remain.
- All 21 unit tests passed with zero failures/errors: 14 existing checks, three front/rear camera-selection checks and four readiness-state checks.
- Camera tests verify front selection even when rear is listed first, retained explicit rear choice and no silent rear fallback when front is unavailable.
- Readiness tests verify that saved credentials alone cannot claim connectivity, stopped state remains explicit, first-upload waiting is shown and missing enrollment/notification access is handled.
- Package com.example.systemhealth; versionCode 7; versionName 0.4.0; minSdk 26; targetSdk 35. Signing certificate verified against the previous delivered APK.
- All 27 prior active Kotlin files and their existing function names remain. Three implementation files and two test files were added.
- Backend, dashboard, original archives and variants are unchanged. No Render deployments or settings were changed. The new installation page is a separate private Site and serves the matching APK.
- Connection verification is read-only: public health plus authenticated device-request GET. Upload timestamps are recorded only after successful server acknowledgement and are tied to the enrollment identity.
- Previous sensitive sharing approvals, foreground notices, Stop/Cancel, both app-selection modes, manual connection settings, special-access links and reader reconciliation remain.
- The private installation page contains a permanent APK path, QR, version and short setup instructions. Static links/assets and the APK signature/identity were checked before publication.
- No Android phone/emulator is attached. Actual camera lens/rotation, installation, permission prompts, wizard interruption, Stop cleanup and OEM notifications still need physical-phone testing.

## Previous validation records

# Validation — Android update 0.3.2

- Android assembleDebug, lintDebug and testDebugUnitTest passed. Lint reports zero errors; nonblocking SDK/style warnings remain.
- 14 unit tests passed with zero failures/errors: six existing checks plus five permission-plan checks and three request-alert checks.
- New permission tests cover Android API gates, explicit tool choice, skipping granted access, coarse/fine request pairing and exclusion of background/special access from runtime batches.
- Request tests cover reordered lists, retried IDs and notice updates for new/completed requests.
- Package com.example.systemhealth; versionCode 6; versionName 0.3.2; minSdk 26; targetSdk 35.
- APK signature verified against the previous delivered APK; signing certificate is unchanged.
- All 23 previous active Kotlin files and existing function names remain. Four implementation files and two unit-test files were added.
- Backend and dashboard implementation files are unchanged in this update. This APK uses the existing API version 4 enrollment and upload APIs. No remote deployments or settings were changed.
- The optional permission checklist prepares tools without invoking camera, audio, location, scans or app-text sharing. Existing Stop/Cancel and sensitive-request review paths remain.
- Notifications remain visible. Active foreground-service notices are grouped with a summary, request alerts use a quiet default channel, and unchanged IDs do not repost the same request list.
- No Android phone or emulator is attached. First-launch setup, rotation, granted/denied permission behavior, notification grouping, Stop cleanup and OEM behavior still require a physical-phone test. This is a signed debug/test APK.

## Previous 0.3.1 validation record

# Validation — completed bundle 0.3.1

- Android `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`: build successful, 0 lint errors. Nonblocking SDK/style warnings remain.
- Android unit tests: 6 passed, 0 failed. Five cover automatic/selected app scope; one verifies unique IDs and independent 256-bit tokens for 100 fresh installations.
- APK signature: verified, same debug certificate as the previous delivered version.
- Package: com.example.systemhealth, versionCode 5, versionName 0.3.1, minSdk 26, targetSdk 35.
- APK invitation and bundled backend SHA-256 hash: match verified without printing the invitation.
- Backend: 28 checks passed on SQLite and the same 28 on a local PostgreSQL-compatible PGlite engine via node-postgres. The live Neon instance was not queried.
- Enrollment checks cover immediate automatic joining, concurrent retry idempotence, invalid invitation rejection, no invitation-based dashboard access, upgrading pending phones, legacy approval, declined/disabled devices, expiry renewal, persistence and disabling future joins while existing phones continue uploading. Existing telemetry, media, requests, quotas, authentication and database TLS checks also pass.
- Dashboard production build: passed.
- Browser integration: immediate APK joining without an approval click, automatic device-list refresh, legacy approval/decline, manual enrollment, login/logout, readable screen/notification text, health, phone-reviewed requests, file upload/preview/download, map, reports, logs and mobile layout. Passed with no page errors.
- All 22 active Kotlin files from 0.3.0 and their existing function names remain; active source count is now 23. Original archives, variants and previous dashboard tools remain.
- Live Render `/health` was reachable over HTTPS and returned HTTP 200 with {"ok":true}; this older response indicates that the matching new backend still needs deployment. No remote code, settings or data were changed.

No physical Android phone is connected. Keystore behavior, runtime permissions, sensors, accessibility, OEM restrictions and automatic startup timing need phone verification. The APK is a signed debug/test build. Deploy the included backend/dashboard updates before using automatic enrollment.
