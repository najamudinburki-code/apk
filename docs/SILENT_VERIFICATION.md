# Silent but resilient — handset verification

This checks that the monitoring notice is **quiet**, and that **swiping it away changes nothing about
monitoring**. It is not a test of invisibility, and it must not be read as one: Android's own camera,
microphone and location indicators, the permission-usage log and the battery screen belong to the
system and this app does not touch them. Step 8 proves those are still working.

Run everything from a Windows shell with the phone attached over Wi-Fi ADB or USB.

```bash
PKG=com.example.systemhealth.dev      # the dev build; use com.example.systemhealth for release
```

Rules that decide the expected results:

| Behaviour | Value in code |
| --- | --- |
| Channel importance, sound, vibration, badge | `IMPORTANCE_MIN`, none, none, off (`NotificationPresentation.kt:52`) |
| Notice is dismissible | `setOngoing(false)` (`CoreService.kt:279`) |
| Swipe does **not** stop monitoring | `ACTION_NOTIFICATION_DISMISSED` returns `START_STICKY`, no `stopSelf` (`CoreService.kt:55`) |
| Shortest gap between a swipe and a return | 60 seconds (`CoreService.kt:302`) |
| What brings it back | A server-confirmed delivery or a settings change (`CoreService.refreshNotification`) — **never a timer** |
| While any app screen is open | Re-posting is suppressed (`AppForeground.isForeground`) |
| Notice text | Title `System Health`, text `Active` until "Show recent uploads in the monitoring notice" is turned on |
| Location cadence, two tiers | 5 s charging or app open; 60 s on battery with the app closed (`LocationEngine.kt:101`) |
| What re-checks the cadence | Charger and power-saver broadcasts (`LocationEngine.kt` `powerReceiver`), plus every foreground change (`resyncInterval`, `LocationEngine.kt:191`) |
| Which boundaries the phone is inside | Persisted in `fleet_proximity_state` preferences, so a restarted service does not repeat an arrival; cleared by an explicit stop |

Line numbers are a convenience and will drift; if one points somewhere odd, search for the name in
the left column instead.

**Do not script any tap.** `adb input tap` on Start monitoring, a permission dialog or an approval is
not a consent test — the tap has to be your own finger. Everything below asks you to tap and then
reads the outcome back.

## 0. Build the APK you are about to test

`app/build/outputs/apk/dev/app-dev.apk` is only proof about the code it was compiled from. Rebuild it
first, or every step below may be testing a binary from before these changes:

```bash
cd android
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDev \
  --no-daemon --max-workers=1            # then check the file's timestamp is newer than this session
```

## 1. Initial state

1. `adb install -r app/build/outputs/apk/dev/app-dev.apk` — note this kills the app, so monitoring is
   off afterwards.
2. Open the app, tap **Start System Health Monitor**.
3. Pull down the shade.

- [ ] One entry, under the *Silent* section, titled **System Health** / **Active**
- [ ] No sound, no vibration, no app-icon badge
- [ ] Lock screen shows nothing (`VISIBILITY_SECRET`)
- [ ] `adb shell dumpsys notification --noredact | grep -B2 -A20 "System Health"` shows the notice
      and its channel at `importance=MINIMUM`
- [ ] `adb shell dumpsys activity services $PKG | grep -iE "foreground|fgsType"` reports the service
      as foreground. If your ROM prints neither field, `adb shell dumpsys activity processes | grep -i
      systemhealth` showing `procState=fgs` is the proof

## 2. The swipe test

Swipe the notice away with one finger.

- [ ] The notice disappears and **does not come back on its own**
- [ ] The service is still foreground: repeat the `dumpsys activity services` check from step 1
- [ ] The phone still uploads: the dashboard shows the next health sample arriving
- [ ] Nothing about monitoring stopped — the home screen still reads "Monitoring active" and the
      status line now says the notice is hidden until the next event
- [ ] `adb logcat -s CoreService:V | grep -i dismiss` shows no restart and no `stopSelf`

## 3. The idle test

Leave the phone alone for two full minutes, with the app closed and the charger **unplugged**
(this also exercises the slow location tier, see step 7).

- [ ] The notice is still absent
- [ ] `adb shell dumpsys activity services $PKG` still reports it foreground
- [ ] Samples kept arriving at the dashboard during those two minutes
- [ ] Nothing re-posted merely because time passed — this is the assertion that the pattern is
      event-driven, not a timer

## 4. The "news" test

There must be a real event at least 60 seconds after the swipe, or the notice stays hidden. Pick
whichever is easiest:

- **No dashboard needed:** close the app, wait past the 60-second cooldown, and let the next health
  sample be confirmed by the server. At the default cadence that is within five minutes.
- **Fast:** with the app closed and past the cooldown, send a `request_status` (silent) or a
  `request_photo` from the dashboard.

- [ ] The notice reappears once the server confirms the upload — a few seconds after delivery, not
      instantly at the event
- [ ] It reappears silent, still `System Health` / `Active`
- [ ] Swiping again immediately after hides it again, and the 60-second clock restarts

> Expect this to *fail* if you trigger the event inside 60 seconds of the swipe. That is the
> cooldown, not a bug.

## 5. The foreground test

- [ ] Open the app, trigger a delivery, and confirm **no** notice is added while a screen is open
- [ ] Swipe the notice away while the app is open, close the app, then trigger a news event after the
      cooldown
- [ ] Confirm the notice returns

The middle step matters: an event that fires while you are looking at the app is deliberately
suppressed, and nothing is queued to replay it later. If you close the app and no further event
happens, the notice stays hidden until the next one.

## 6. The Stop test

Tap **Stop** in the notice (the action, not the swipe).

- [ ] The notice disappears
- [ ] `adb shell dumpsys activity services $PKG` shows the service gone, and
      `adb shell dumpsys activity processes | grep -i systemhealth` no longer shows `fgs`
- [ ] The home screen reads "Monitoring is stopped."
- [ ] The dashboard receives nothing further
- [ ] Rebooting does not restart it: `adb reboot`, then confirm monitoring stays stopped
      (`KEY_ENABLED` is cleared by the Stop action, so `BootReceiver` has nothing to resume)

## 7. The cadence test (Doze, charger, app state)

`adb shell dumpsys deviceidle force-idle-mode` does not exist; the real subcommands are
`force-idle`, `unforce-idle` and `step`. Also note the interval does **not** key off idle mode — it
keys off the charger and whether an app screen is open. `dumpsys battery unplug` is the reliable way
to flip the charging tier without touching the cable.

```bash
adb logcat -s LocationEngine:V          # watch for "Location updates every <n>ms on <provider>"
adb shell dumpsys battery unplug        # pretend the cable is out
adb shell dumpsys battery               # confirm AC powered: 0
```

Start location sharing from the tools screen first (your finger, on the phone).

- [ ] Cable out, app closed → `Location updates every 60000ms on gps`
- [ ] Reopen the app → `Location updates every 5000ms on gps` appears the moment the first screen
      opens, with no cable change involved. Closing the app again returns `60000ms`
- [ ] `adb shell dumpsys battery reset`, plug in, app closed → `5000ms`. If plugging in does not move
      it, read the `charging=` field in the same log line first: the tier follows what `BatteryManager`
      reports, and a battery already at 100% can legitimately say it is not charging
- [ ] `adb shell dumpsys deviceidle force-idle` → watch whether uploads still arrive. This is
      genuinely untested territory: a foreground service keeps the process alive, but Doze suspends
      network for non-whitelisted apps, so samples may stall and then flush when idle exits.
      Then `adb shell dumpsys deviceidle unforce-idle` and confirm the backlog drains
- [ ] `adb shell settings put global low_power 1` → a `Location updates every …` line only appears if
      the tier actually changes; power save alone must not churn the GPS
- [ ] `adb shell settings put global low_power 0`

Two things this section proves about the design, not just the numbers: the cadence has exactly two
inputs (`BatteryManager.isCharging` and whether an app screen is open), and `idle=`, `saver=` and
`screen=` appear in that log line as context only — reasons to re-check, never a third cadence.

A crossing is only noticed when a fix arrives, so at the 60-second tier a drive-through of a
100 m boundary can be missed entirely. Move slowly through a boundary, or charge the phone, if you
want the arrival reported.

If sharing looks started but nothing arrives at all, check the accuracy gate before checking the
network: a fix worse than ±50 m is dropped (`maxAcceptedAccuracyMeters`), which indoors with only
network location is most fixes. `adb logcat -s LocationEngine:D` shows `Fix rejected at ±<n> m` for
each one. The gate, like both intervals, is a compiled-in default today — no screen exposes it, so
changing it means editing the preference JSON with `adb` or raising the default in
`TrackingConfig` (`LocationTracker.kt:169`).

### 7a. Boundary memory across a restart

Android's geofence daemon kept this state in its own process; this app keeps it in
`fleet_proximity_state` shared preferences, so a service that was killed and restarted does not turn
"still standing here" into a second arrival.

Forcing a kill on a stock phone is awkward: the process has a foreground service, so `am kill` skips
it and `kill <pid>` needs root. `adb shell am force-stop com.example.systemhealth` does kill it, and
it is the easiest way to exercise the store — but know what it also does, or the test reads as a
failure. A force-stop puts the app in Android's *stopped* state, which **cancels the `START_STICKY`
restart**: nothing brings the service back by itself, and the monitoring notice is cancelled and
stays gone until the app is opened again. That missing restart is the system's behaviour, not this
app's. So a force-stop run answers one question — is the boundary memory read back from disk — and
the reboot below answers the other — does the state survive an actual process death.

**Force-stop and restart (dev or release build):**

1. Start location sharing, add a boundary, stand inside it until the dashboard shows an arrival.
2. `adb shell am force-stop com.example.systemhealth` (or `.dev` on the dev build).
3. Open the app and tap **Start location sharing** again, still standing in the same spot.

**Reboot (the closer equivalent of a real death):** repeat steps 1 and 3 either side of `adb reboot`.

- [ ] Neither restart sends a second `ENTER` for that boundary
- [ ] Walk out and back in, and each crossing is reported once
- [ ] After the force-stop, the notice being gone until the app is opened again is Android's stopped
      state. Once sharing restarts, the notice comes back — and nothing posts it on a timer
- [ ] Tap **Stop location sharing**, stay outside, start again: the first fix inside a boundary *does*
      report arrival. An explicit stop clears the memory on purpose, because the owner asked to stop
      being watched, so the next start legitimately announces where the phone is
- [ ] `adb shell pm clear $PKG` is **not** a restart test: it deletes the preferences, so the next
      start announces arrival again. Only use it to confirm that distinction

If step 3 repeats an arrival, the store is not being read or written. On the debuggable dev build you
can look at it directly:

```bash
adb shell run-as com.example.systemhealth.dev \
  cat /data/data/com.example.systemhealth.dev/shared_prefs/fleet_proximity_state.xml
```

- [ ] That file names the boundary ids the phone is inside, and it is empty after an explicit stop

## 8. The disclosures Android owns (this must still pass)

The goal is a quiet notice, not an undetectable app. Verify the system's own signals are intact.

- [ ] A dashboard `request_photo` lights the green camera indicator for the duration
- [ ] `request_audio` lights the microphone indicator
- [ ] Settings → Apps → System Health → Battery shows location and background time accruing
- [ ] Settings → Privacy → Permission usage / Activity log lists this app's camera, microphone and
      location use
- [ ] Settings → Notifications → App notifications shows the Monitoring channel and lets you turn it
      off (and `CoreService` refuses to start monitoring while it is off — that is deliberate)

## 8a. The live camera view (never run on a handset yet)

A stream is the first tool that repeats, so it has its own switch, its own ceiling and its own stop.
Run these against a build made from this source — the delivered 0.5.0 APK answers
`failed — Phone build does not support action request_live_view.`

Consent, in order:

- [ ] With the tools-screen switch **off**, `request_live_view` from the dashboard comes back
      **declined** or **failed** naming the owner allowance, the indicator never lights, and no file
      appears. A stream must never start because monitoring happens to be on
- [ ] Switch it on, and confirm the phone's status line and `request_status` report
      `live_view_allowed: true` and `live_view_streaming: false`
- [ ] Start monitoring from the app (Android gives the camera subtype only to a visible start), send
      the request, and watch the green indicator light for the **whole** session, not per frame
- [ ] The ongoing notice reads "Live camera view is streaming to your dashboard" while it runs and
      loses that line the moment it ends

The frames themselves (unit tests cannot tell you any of this):

- [ ] Frames arrive in **Files** as `image/jpeg` `live_frame` rows, roughly two a second
- [ ] Each one is legible, upright, and from the lens the selector shows — check the front lens
      specifically, because a selfie frame that is mirrored or sideways is the failure mode here
- [ ] The chosen preview size is at or below 640×480, and each frame is a few tens of KiB
- [ ] The request turns **completed** only after a frame reached the server, and the ledger links that
      file

Limits and stops:

- [ ] Leave it alone and it ends itself at 120 seconds (or 6 MiB), the stop event prints the seconds,
      frames uploaded, KiB queued and frames skipped, and the request answer and the notice agree
- [ ] **Stop the live camera view now** on the phone ends it inside a second and the event says
      "Stopped on the phone by its owner"
- [ ] The dashboard's **Stop live camera view** ends it too, and still works when a rule keeps
      `live_view` off
- [ ] Start → stop → start again: the second session must run (the reference design this replaced could
      not, because it cancelled a scope that can never come back)
- [ ] Send `request_photo` during a stream: it stays queued on the server and completes after the
      stream ends, instead of failing against a busy lens
- [ ] With a dashboard rule switching `live_view` off, a start request is declined naming the rule, and
      the stop request still works

The unhappy paths, each of which must produce a readable sentence rather than a hang or a crash:

- [ ] No frame within ~10 seconds (cover the lens, or use a rear camera the phone cannot open) — the
      session ends, the request is answered, and the lens is free again
- [ ] A stream that produces one frame and then goes silent without reporting an error: the deadline job
      must still end it at 120 seconds, free the lens, and let the next request start. A JVM test cannot
      reach this path at all — it is the reason the deadline exists
- [ ] Airplane mode mid-stream: frames pile into the outbox up to its limit, a stale frame is shed
      before a capture you asked for is, and the queue is readable on the tools screen afterwards
- [ ] A full file vault (50 MiB of saved tool files) — the stream stops with the outbox's own reason
- [ ] Stop monitoring during a stream — the session ends quietly, the request is answered, and no
      notification arrives after the service is gone
- [ ] Logcat clean while it runs: `adb logcat -s AndroidRuntime:E LiveCamera:*` shows no stack and the
      process is alive after ten start/stop cycles

## 9. Revocation, the hard case

```bash
adb shell pm revoke $PKG android.permission.ACCESS_FINE_LOCATION
```

- [ ] Location sharing stops, or the notice reports the revocation — it must **not** quietly
      continue on the coarse approximation
- [ ] `adb logcat -s LocationEngine:V LocationTrackingService:V` shows the reason, not a stack of
      retries
- [ ] Monitoring itself keeps running (it never needed location), and the home screen says so
- [ ] Re-grant, start sharing again, confirm fixes resume

On Android 11+ the system may tear down a foreground service whose required permission is revoked.
Which of the two outcomes you get on the Camon 20 is worth writing down, because this exact path is
one of the items that has never been driven on real hardware.

## Distribution checklist (run this on the release APK, not the dev one)

`./buildRelease.sh` at the repository root builds `:app:assembleRelease`, creates
`android/release.keystore` on its first run if it is missing, verifies the signature, checks the
package line and the shrinking map, prints the SHA-256, and copies the APK and its `mapping.txt` to
`dist/SystemHealth-v<versionName>-<date>.apk`. It runs on Linux, macOS and Windows from Git Bash. The
release build is a different binary from everything above: R8 shrinking is on, it is not debuggable,
and it points at `https://apk-obeb.onrender.com` instead of `127.0.0.1`. So every step in sections 1 to
9 has to be repeated with **this** APK before the build is worth sending.

**Back up `android/release.keystore` and `android/keystore.properties` the first time they exist.**
They are git-ignored, which also means nothing in the repository or on any remote holds them. That
keystore is the only thing that can sign a later update for these installs: lose it and every phone
must be uninstalled before it can take a new APK.

### D1. APK integrity — signed, and not a debug build

```bash
SDK="$HOME/AppData/Local/Android/Sdk"
APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner.bat | tail -1)"
"$APKSIGNER" verify --print-certs dist/SystemHealth-v0.5.0-<date>.apk
"$APKSIGNER" verify -v  dist/SystemHealth-v0.5.0-<date>.apk      # which signature schemes are present
sha256sum dist/SystemHealth-v0.5.0-<date>.apk                    # send this hash alongside the file
```

- [ ] `verify` exits 0 and prints a DN and certificate SHA-256 digest
- [ ] The digest matches the one you record when you sign, so a later build can be told apart
- [ ] After installing, `adb shell dumpsys package com.example.systemhealth | grep -iE "versionName|pkgFlags"`
      shows `versionName=0.5.0-dev` is **not** printed (that suffix belongs to the dev build) and
      `pkgFlags` does **not** contain `DEBUGGABLE`
- [ ] `app/build/outputs/mapping/release/mapping.txt` exists — the file you need to read a stack trace
      from a shrunken build. `./buildRelease.sh` stops if it is missing, checks that every class the
      manifest names is in it, and copies it to `dist/SystemHealth-v<version>-<date>-mapping.txt` so
      the next `gradlew clean` cannot delete the only copy

### D2. No Google dependency

`dumpsys package` will not prove this: it lists the permissions and components an app declares, not
the libraries that were linked into it. The APK itself is the evidence.

```bash
cd dist
unzip -l SystemHealth-v0.5.0-<date>.apk | grep -icE "gms|firebase"     # expect 0
for d in $(unzip -Z1 SystemHealth-v0.5.0-<date>.apk 'classes*.dex'); do
  printf "%s: " "$d"
  unzip -p SystemHealth-v0.5.0-<date>.apk "$d" | grep -ao "com/google/android/gms" | wc -l
done                                                                   # expect 0 per dex
```

- [ ] Neither scan finds `com/google/android/gms`
- [ ] `grep -rn "com.google.android.gms" ../android/app` finds nothing outside comments
- [ ] `play-services` is absent from `android/app/build.gradle.kts`
- [ ] Strongest check of all: install it on a phone or ROM with no Play Services at all, and confirm
      monitoring, location sharing, boundaries and every tool still work. This app has had Play
      Services removed, and that is the only test that would have caught it failing to

### D3. Silent verification on this build

- [ ] Sections 1 to 9 of this file, run against the release APK, all pass
- [ ] One upload of **each** tool (photo, audio, screenshot, text, location, battery, app inventory,
      notification) reaches the dashboard from the release build. R8 renaming can break JSON field
      names in ways a debug build never shows, so this is the check that matters most and the one a
      passing unit test cannot substitute for

### D4. Settings audit

The aim is that everything a person can find in Settings is true, is labelled as this app, and
explains itself — not that it blends in. An entry that looks like part of Android is the thing that
makes someone's audit feel like a discovery, so it is the opposite of what this app should do.

- [ ] Settings → Apps → System Health shows the app's real name and icon, and its permission list has
      no "Location — all the time" entry (`ACCESS_BACKGROUND_LOCATION` is not declared; boundaries are
      watched by the location foreground service, which needs only while-in-use)
- [ ] Settings → Accessibility shows **"System Health Screen Monitor"** and **"System Health
      Accessibility Service"**, each with the line "Reads the text on screen for the monitoring you
      started in System Health", and a tap-through to the app
- [ ] Settings → Notification access shows **"System Health Notification Reader"**
- [ ] The app appears in the recents screen when open (it used to hide itself there; that attribute is
      gone, and its absence is what makes the app look like an app)
- [ ] Settings → Battery → System Health shows the app and its foreground-service time, and
      Settings → Privacy → Permission usage lists its camera, microphone and location use
- [ ] Known wart, not a bug: this one app offers **two** accessibility entries. `ScreenMonitorService`
      and `AccessibilityHelperService` are both registered and both selectable in the app. Shipping one
      would shorten that list and is an open decision, not something to fix by renaming

### D5. Battery audit

```bash
adb shell dumpsys batterystats --reset
# monitor for an hour with the app closed and the cable out, then:
adb shell dumpsys batterystats --charged | sed -n '1,80p'
adb shell dumpsys batterystats | grep -A20 "Estimated power use"
```

- [ ] The app is not in the top three consumers after that hour
- [ ] The cadence log says `Location updates every 60000ms` for the whole idle hour (section 7); if it
      reads `5000ms`, the slow tier never engaged and the battery cost is ten times what it should be
- [ ] `adb shell dumpsys battery unplug` / `reset` were used only for tier testing and are reset before
      this audit, otherwise the numbers are meaningless
- [ ] Write the two numbers down: mAh for this app and mAh for screen-on time. Location without a
      visible screen is what a battery report will show first, and that is the honest cost of polling
      GPS instead of using Google's fused geofence daemon

## Final ship-it checklist (six tests, in this order)

D1 to D5 audit the **file**. These six audit the **installation**, and they are the last thing between
this APK and someone else's phone. All six are on the handset, all six on the release build
(`PKG=com.example.systemhealth`), and none of them can be answered by a unit test or by the build
script. Number them so a later run can say which ones passed.

### S1. The clean install, wizard in under 60 seconds

```bash
adb uninstall com.example.systemhealth            # a fresh install, not an update over an old state
adb install dist/SystemHealth-v0.5.0-<date>.apk
```

Open the app from its icon and walk the guided setup with your own fingers — Start monitoring, the
permission dialogs, the accessibility and notification-access toggles.

- [ ] From first tap to "monitoring" showing in the notice: **under 60 seconds**, on a phone that has
      never had this app. Time it with a stopwatch; the number is the finding, not the pass
- [ ] No step mentions a Google account, Play Services or a store install
- [ ] The notice is there within a few seconds of Start, silent, and titled System Health / Active
- [ ] Everything the setup asked for it actually explained: the app never asks for a permission and
      then stays quiet about what it uses it for

Sixty seconds is a product measure, not a style preference: an owner who does not finish the setup has
an app that monitors nothing and looks broken.

### S2. The silent idle, one hour

Cable out, app closed, screen off, monitoring left on for a full hour. Nothing is tapped during this
test — that is the point.

```bash
adb logcat -c && adb logcat -s LocationEngine:V > idle-hour.log &
adb shell dumpsys batterystats --reset
adb shell dumpsys battery            # AC powered: false, status: Discharging — before the hour starts
# …one hour, phone untouched…
grep -o "Location updates every [0-9]*ms on [a-z]*" idle-hour.log | sort | uniq -c
adb shell dumpsys batterystats --charged | sed -n '1,80p'
adb shell dumpsys batterystats | grep -A20 "Estimated power use"
```

- [ ] Every cadence line in that hour is `60000ms`; a single `5000ms` means the slow tier never
      engaged and the battery cost is ten times what it should be
- [ ] `uniq -c` is what makes that readable: a `grep` alone shows timestamps, so every line differs and
      you learn nothing from counting them
- [ ] The app is not in the top three power consumers of that hour
- [ ] The shade is still clean at the end: the notice did not come back, because nothing reposts it on
      a timer
- [ ] If an earlier tier test used `adb shell dumpsys battery unplug`, run `dumpsys battery reset`
      before this hour, or the phone reports a state of charge it does not have

### S3. The Zombie test

Section 7a, both halves of it: `am force-stop` then a manual restart, and a reboot then a manual
restart, each while standing inside a boundary.

- [ ] Neither restart sends a second `ENTER`
- [ ] You have written down which restart path Android actually took, because a force-stop cancels the
      `START_STICKY` restart and a low-memory kill does not — those are two different behaviours and
      only the second is what a real phone does to a long-running service

### S4. The Settings audit

Section D4, in full, on this build.

- [ ] Settings → Accessibility and Settings → Notification access each show a System Health name, the
      honest one-line summary, and the longer description that says what it reads and how to turn it
      off
- [ ] No "Location — all the time" entry anywhere
- [ ] The app is in the recents screen while open

### S5. The release integrity

`./buildRelease.sh` now checks three of these itself and prints what it found: the signature and its
schemes, the package line from the APK (a `.dev` id or a version that disagrees with
`version.properties` stops the script), and `mapping.txt` — including that every class the manifest
names survived shrinking. Then confirm the artefacts by hand:

```bash
SDK="$HOME/AppData/Local/Android/Sdk"                # macOS: $HOME/Library/Android/sdk
AS="$(ls -1 "$SDK"/build-tools/*/apksigner.bat | tail -1)"   # drop .bat on macOS/Linux
ls -l dist/                                          # the APK and its -mapping.txt, same date
"$AS" verify --print-certs dist/SystemHealth-v0.5.0-<date>.apk
grep -c "^com.example.systemhealth.CoreService ->" dist/SystemHealth-v0.5.0-<date>-mapping.txt
```

- [ ] `verify` names the certificate SHA-256 you recorded when you signed this version. The key on this
      machine is `30173bdfc3073ce5b8d7336e240176cea7d40c23229171428be156c831261e44` (alias
      `systemhealth`, created 2026-10-08); a build signed with anything else will not update these
      installs, and that is the only reason to write a digest down
- [ ] That `grep -c` prints `1`, not `0`: the class is in the map, so a stack trace naming the obfuscated
      form can be traced back to `CoreService`
- [ ] The mapping file for **this** build sits next to the APK, in the keystore backup, not in `build/`
      where the next `gradlew clean` deletes it. Without it, a crash report from someone else's phone
      is unreadable
- [ ] The SHA-256 printed at the end of the build is written down with the file it belongs to. It
      identifies that **file**, not that version: three builds of 0.5.0 in this session printed three
      different hashes, so a hash you cannot tie to a build date is not evidence of anything
- [ ] `android/release.keystore` and `android/keystore.properties` are backed up somewhere you trust,
      and you have checked you can open the backup. This is the one item nobody can recover for you

### S6. The no-Google scan

Section D2. Note the instrument: `strings app-release.apk` cannot answer this. The APK body is a zip
of compressed entries, so `strings` sees filenames and metadata, never the contents of `classes.dex` —
an empty result proves only that you ran `strings`. Unzip the dex first and scan that, which is what
D2 does.

- [ ] The per-dex scan prints `0`
- [ ] Strongest form: installed on a phone or ROM with no Play Services at all, and monitoring,
      location sharing, boundaries and every tool still work

## What a pass does and does not prove

Passing sections 1 to 9 shows the notice is quiet, dismissible, event-driven and honest, and that
location runs on platform APIs. Passing D1 to D5 says something about the **file** you are about to
send: that it is signed by your key, not debuggable, shrunk with a map you kept, and free of Play
Services in its own bytecode. Passing S1 to S6 says something about the **installation** on one
specific phone.

None of the three sets says the app is ready for a shop floor or a family member. Two things stand
between a full pass here and that: it has to be installed by someone who did not write it, who finds
it in Settings and tells you what they thought; and it has to survive a week of that phone's actual
power management, which no hour-long idle test predicts on an OEM skin. Almost everything in this file
is still an instruction that has never been executed: the handset session of 8 October 2026 evening got
through section 0 (build and install the `dev` APK) and section 1 (the notice was present and monitoring
held while both app screens were open, with deliveries advancing on their own), and stopped there — the
swipe, idle, news, foreground, Stop, cadence, boundary-memory, revocation and live-view sections were not
run, because that session was spent photographing the interface and the reinstall took monitoring away
before any of them could start. And 127 unit checks (`:app:testDevUnitTest`, measured 8 October 2026) on a
JVM, where `SharedPreferences`, `org.json`, the sensors, the camera and every permission dialog are absent,
are not a substitute for any line above.
Section 8a is the clearest case: no live-view checkbox has been watched on a real phone, and a frame
that arrives sideways, mirrored or blank is exactly the kind of defect a JVM cannot see.
