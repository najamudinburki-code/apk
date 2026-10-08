# Building and installing the phone app

Everything below runs from `android/`. Gradle needs a JDK 17+: on this machine use the one that ships
with Android Studio.

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
```

`gradle.properties` already caps the build at one worker and a 1152 MB heap, so a 4 GB laptop does not
thrash. Do not raise those numbers unless the machine has more memory; a build that swaps for an hour is
slower than a small one.

## Versions

`version.properties` in this folder is the only place the version is written. `app/build.gradle.kts` reads
it into `versionCode` and `versionName`, and the app shows the same value through `BuildConfig`. Bump both
fields in one commit; the dashboard and the docs quote that number.

## Variants

| Variant | Command | Server URL | Package | Installs beside the other |
| --- | --- | --- | --- | --- |
| dev | `./gradlew :app:assembleDev` | `http://127.0.0.1:3000` | `…systemhealth.dev` | yes |
| debug | `./gradlew :app:assembleDebug` | Render URL | `…systemhealth` | no |
| release | `./gradlew :app:assembleRelease` | Render URL | `…systemhealth` | no |

Release runs R8 shrinking; the current APK is about 0.5 MB against 3.6 MB for a debug build. Clear-text HTTP
is allowed only to `127.0.0.1`, `localhost` and the emulator gateway `10.0.2.2`, in dev and debug alike, so a
test phone talks to the laptop through `adb reverse tcp:3000 tcp:3000`. A release build allows no clear text
at all. A build that must reach a plain-HTTP address on your network is a configuration mistake, not a
missing switch.

```bash
adb install -r app/build/outputs/apk/dev/app-dev.apk
adb shell am start -n com.example.systemhealth.dev/com.example.systemhealth.MainActivity
```

## Signing a release

The short answer is `./buildRelease.sh` at the repository root: it creates the key if none exists,
builds, verifies the signature, checks `mapping.txt`, and copies the APK and its map into `dist/`. The
rest of this section is for when you want to do it by hand, or want to know what the script owes you.

An unsigned release APK installs nowhere, so `assembleRelease` without a key produces
`app-release-unsigned.apk` on purpose. Keep the key out of the repository: `*.jks`, `*.keystore` and
`keystore.properties` are already ignored, and `pre-commit.sh` refuses them if `git add -f` is used
(see `CONTRIBUTING.md`).

Create a key once and store it somewhere safe — losing it means every phone must be reinstalled, because
Android refuses an update signed by a different key.

```bash
cd android            # the store file below is written relative to the app module, so put the key here
keytool -genkeypair -v -keystore release.keystore -storetype PKCS12 -alias systemhealth \
  -keyalg RSA -keysize 2048 -validity 10950
```

Then either write `android/keystore.properties`. `storeFile` is resolved **relative to
`android/app/`**, not to `android/`, which is why the path starts with `../`:

```
storeFile=../release.keystore
storePassword=<from the keytool prompt>
keyAlias=systemhealth
keyPassword=<from the keytool prompt>
```

or provide the same four values as `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` in CI — there, `RELEASE_STORE_FILE` is usually an absolute path or a checked-out
secret file. All four must be present or none is used; a half-configured key fails loudly rather than
shipping a subtly wrong build. Re-run `:app:assembleRelease` and the output becomes `app-release.apk`.

`mapping.txt` lands in `app/build/outputs/mapping/release/` for every release build. Keep it with the
release: a crash from a shrunken APK is unreadable without it, and `build/` is where the next
`gradlew clean` deletes it. `buildRelease.sh` copies it to `dist/` beside the APK it belongs to, and
stops the build if it is missing or if any class named in `AndroidManifest.xml` is absent from it.

## Checks before calling a build good

Commit conventions and the secret rules are in `CONTRIBUTING.md`. What has to pass on a handset before
an APK is sent to anyone is `docs/SILENT_VERIFICATION.md` — sections 1 to 9, then D1 to D5 on the file
and S1 to S6 on the installation.

```bash
./gradlew :app:testDevUnitTest :app:lintDev
```

Both pass on the current tree. They do not prove the app works on a phone: camera, microphone, geofence
prompts, notification access and OEM reboot behaviour need a real device, and a debug build exercises
neither R8 nor release signing.
