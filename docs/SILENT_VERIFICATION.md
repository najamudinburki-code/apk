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

## 8. The disclosures Android owns (this must still pass)

The goal is a quiet notice, not an undetectable app. Verify the system's own signals are intact.

- [ ] A dashboard `request_photo` lights the green camera indicator for the duration
- [ ] `request_audio` lights the microphone indicator
- [ ] Settings → Apps → System Health → Battery shows location and background time accruing
- [ ] Settings → Privacy → Permission usage / Activity log lists this app's camera, microphone and
      location use
- [ ] Settings → Notifications → App notifications shows the Monitoring channel and lets you turn it
      off (and `CoreService` refuses to start monitoring while it is off — that is deliberate)

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

## What a pass does and does not prove

Passing all nine sections shows the notice is quiet, dismissible, event-driven and honest, and that
location runs on platform APIs. It does not show release readiness: reboot survival, Doze behaviour,
accessibility and notification-listener extraction, this OEM's screen capture, and the release-signed
APK against the Render backend are still unverified, and none of this is committed yet.
