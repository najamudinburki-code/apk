#!/usr/bin/env bash
# One-tap signed release build for System Health.  Linux, macOS, and Windows from Git Bash.
#
#   ./buildRelease.sh
#
# First run creates android/release.keystore and android/keystore.properties if they are missing,
# builds the release variant (R8 shrinking on, not debuggable, real backend URL), verifies it, and only
# then copies the APK and its shrinking map into dist/. Nothing lands in dist/ that this script could
# not verify, so the newest file in that folder is always a candidate to send.
#
# Both generated files are git-ignored and are NOT backed up anywhere automatically. The keystore is
# the only thing that can sign a future update for these installs: if it is lost, every phone must be
# uninstalled before it can take a new APK. Copy both to somewhere you trust the moment this finishes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID="$ROOT/android"
APP="$ANDROID/app"
KEYSTORE="$ANDROID/release.keystore"
KEY_PROPS="$ANDROID/keystore.properties"
ALIAS="systemhealth"
DIST="$ROOT/dist"
MANIFEST="$APP/src/main/AndroidManifest.xml"
DAY="$(date +%Y%m%d)"

have() { command -v "$1" >/dev/null 2>&1; }
looks_like_jdk() { [ -x "$1/bin/java" ] || [ -f "$1/bin/java.exe" ]; }

# Directory lists, newest last. sort -V understands 35 < 100 and macOS' sort has no -V, so the fallback
# orders by modification time, which is the order the SDK installed things in.
newest_dirs() {
    if sort -V </dev/null >/dev/null 2>&1; then ls -1d "$@" 2>/dev/null | sort -V
    else ls -1dt "$@" 2>/dev/null; fi
}

# ── Java: the shell's, else a bundled Android Studio JDK, in any of their platform homes ──────────
# A candidate counts only if it holds a java binary. $HOME/.jdks, /usr/lib/jvm and Program Files/Java
# are containers one level above the JDK, and exporting one of those as JAVA_HOME makes gradlew die
# with "JAVA_HOME is set to an invalid directory" — worse than setting nothing, because gradlew finds
# java on PATH by itself.
if [ -n "${JAVA_HOME:-}" ] && ! looks_like_jdk "$JAVA_HOME"; then
    echo "Note: JAVA_HOME=$JAVA_HOME holds no java binary, so it is ignored." >&2
    JAVA_HOME=""
fi
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in \
        "/c/Program Files/Android/Android Studio/jbr" \
        "$HOME/AppData/Local/Programs/Android Studio/jbr" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/android-studio/jbr" "/opt/android-studio/jbr" \
        "/c/Program Files/Java" "$HOME/.jdks" "/usr/lib/jvm" \
        "/Library/Java/JavaVirtualMachines"; do
        if looks_like_jdk "$candidate"; then JAVA_HOME="$candidate"; break; fi
        if [ -d "$candidate" ]; then
            # Read the list line by line: these roots contain spaces, and word splitting would cut
            # every candidate path in half.
            JAVA_HOME="$(newest_dirs "$candidate"/*/ | while IFS= read -r nested; do
                if looks_like_jdk "${nested%/}"; then printf '%s\n' "${nested%/}"; fi
            done | tail -1)"
            if [ -n "$JAVA_HOME" ]; then break; fi
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] && have java; then
    # java -XshowSettings says where it lives, which beats guessing the bin directory.
    JAVA_HOME="$(dirname "$(dirname "$(java -XshowSettings:properties -version 2>&1 \
        | sed -n 's/[[:space:]]*java\.home[[:space:]]*=[[:space:]]*//p' | tr -d '\r')")" 2>/dev/null || true)"
    if ! looks_like_jdk "${JAVA_HOME:-/nonexistent}"; then JAVA_HOME=""; fi
fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; fi

# On Windows the JDK ships keytool.exe with no extension-less twin; elsewhere it is a plain file.
KEYTOOL=""
for tool in "${JAVA_HOME:-}/bin/keytool" "${JAVA_HOME:-}/bin/keytool.exe"; do
    if [ -f "$tool" ]; then KEYTOOL="$tool"; break; fi
done
if [ -z "$KEYTOOL" ] && have keytool; then KEYTOOL="$(command -v keytool)"; fi
if [ -z "$KEYTOOL" ]; then
    echo "STOP: no keytool found. Install a JDK, or set JAVA_HOME to a directory containing bin/java." >&2
    exit 1
fi

JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ]; then
    if [ -f "$JAVA_HOME/bin/java.exe" ]; then JAVA_BIN="$JAVA_HOME/bin/java.exe"
    elif [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
fi

# ── The SDK's build-tools: newest of whichever root the machine actually uses ─────────────────────
SDK=""
for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
            "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$root" ] && [ -d "$root/build-tools" ]; then SDK="$root"; break; fi
done
TOOLS=""
if [ -n "$SDK" ]; then TOOLS="$(newest_dirs "$SDK"/build-tools/*/ | tail -1)"; fi

# apksigner and aapt2 are what the gates read. The jar form of apksigner is preferred because on
# Windows this machine's paths contain spaces, which cmd mis-parses when bash hands the .bat over.
# Every branch merges stderr into stdout: apksigner writes "DOES NOT VERIFY" to stderr and nothing to
# stdout, so a gate that looked only at stdout would wave through an unsigned APK.
APKSIGNER=""
if [ -n "$TOOLS" ] && [ -f "${TOOLS}lib/apksigner.jar" ]; then APKSIGNER="jar"
elif have apksigner; then APKSIGNER="bin"
elif [ -n "$TOOLS" ] && [ -f "${TOOLS}apksigner.bat" ]; then APKSIGNER="bat"
fi
signer() {
    {
        case "$APKSIGNER" in
            jar) "$JAVA_BIN" -jar "${TOOLS}lib/apksigner.jar" "$@" ;;
            bin) apksigner "$@" ;;
            bat) cmd //c "$(cygpath -w "${TOOLS}apksigner.bat")" "$@" ;;
        esac
    } 2>&1 | { grep -v '^WARNING' || true; }
}

AAPT2=""
for cand in "${TOOLS}aapt2.exe" "${TOOLS}aapt2" "${TOOLS}aapt.exe" "${TOOLS}aapt"; do
    if [ -f "$cand" ]; then AAPT2="$cand"; break; fi
done

checksum() {
    if have sha256sum; then sha256sum "$1"
    elif have shasum; then shasum -a 256 "$1"
    elif have openssl; then openssl dgst -sha256 "$1"
    else echo "(no sha256 tool found - hash the file another way before you send it)"; fi
}

# ── The keystore, generated once with a password nobody has to invent ─────────────────────────────
if [ ! -f "$KEYSTORE" ]; then
    echo "Creating $KEYSTORE"
    PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)"
    # The password is passed through the environment so it never lands in the process list or a shell
    # history file. 10950 days = 30 years, longer than this app is likely to be installed anywhere.
    SH_KEYPASS="$PASSWORD" "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass:env SH_KEYPASS -keypass:env SH_KEYPASS \
        -dname "CN=System Health, OU=Release, O=System Health, C=US"
    # app/build.gradle.kts resolves storeFile relative to the app module, hence ../
    (umask 077 && cat > "$KEY_PROPS" <<EOF
storeFile=../release.keystore
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
    )
    echo "Wrote the password to $KEY_PROPS (git-ignored, readable only by you)."
    echo "BACK BOTH FILES UP NOW: $KEYSTORE and $KEY_PROPS"
else
    echo "Using the existing $KEYSTORE"
fi
# umask only shapes files this shell creates. A key or properties file from an earlier run, or from a
# tool that ignored umask, is tightened here. Neither file's contents are ever printed.
chmod 600 "$KEYSTORE" 2>/dev/null || true
chmod 600 "$KEY_PROPS" 2>/dev/null || true

# ── Build ─────────────────────────────────────────────────────────────────────────────────────────
"$ANDROID/gradlew" -p "$ANDROID" :app:assembleRelease --no-daemon --max-workers=2 --console=plain

APK="$APP/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "STOP: expected $APK and it is not there." >&2; exit 1; }

VERSION="$(sed -n 's/^versionName=//p' "$ANDROID/version.properties" | tr -d '[:space:]')"
CODE="$(sed -n 's/^versionCode=//p' "$ANDROID/version.properties" | tr -d '[:space:]')"
if [ -z "$VERSION" ] || [ -z "$CODE" ]; then
    echo "STOP: $ANDROID/version.properties needs both versionName and versionCode." >&2
    exit 1
fi
OUT="$DIST/SystemHealth-v$VERSION-$DAY.apk"
MAPDIST="$DIST/SystemHealth-v$VERSION-$DAY-mapping.txt"

# ── Gate 1: the release build, not the dev one wearing a new filename ─────────────────────────────
# package=, versionCode= and versionName= come from the APK itself, so this catches a mistaken
# buildType that would otherwise ship an app pointed at 127.0.0.1 with a debug flag on it. An APK whose
# package aapt2 cannot read fails every later gate too, so getting nothing back is fatal, not a shrug.
if [ -z "$AAPT2" ]; then
    echo "STOP: no aapt2 under ${SDK:-no SDK root detected}. Install build-tools or set ANDROID_HOME." >&2
    exit 1
fi
PKG_LINE="$("$AAPT2" dump badging "$APK" 2>/dev/null | grep -E '^package: ' | head -1 || true)"
if [ -z "$PKG_LINE" ]; then
    echo "STOP: aapt2 read no package line from $APK, so nothing about this build is known." >&2
    exit 1
fi
echo "--- package identity as the APK declares it"
echo "  $PKG_LINE"
if printf '%s\n' "$PKG_LINE" | grep -q "name='com\.example\.systemhealth\.dev'"; then
    echo "STOP: this APK is the DEV build (.dev applicationId, 127.0.0.1 backend)." >&2
    exit 1
fi
if ! printf '%s\n' "$PKG_LINE" | grep -q "name='com\.example\.systemhealth'"; then
    echo "STOP: unexpected applicationId — not the package this project ships." >&2
    exit 1
fi
if printf '%s\n' "$PKG_LINE" | grep -q "versionName='$VERSION'"; then
    echo "  versionName matches version.properties ($VERSION)"
else
    echo "STOP: version.properties says $VERSION but the APK says something else (line above)." >&2
    exit 1
fi
if ! printf '%s\n' "$PKG_LINE" | grep -q "versionCode='$CODE'"; then
    echo "STOP: the APK's versionCode is not $CODE, so the update order is not what the file claims." >&2
    exit 1
fi

# ── Gate 2: the shrinking map, the only way to read a future crash ─────────────────────────────────
# Not a "did R8 obfuscate things" check — obfuscating is the point, and grepping for a short renamed
# class proves nothing about correctness. What matters is that every class Android must instantiate by
# name survived shrinking, and that the map decoding a stack trace is kept with the APK rather than in
# build/, where the next ./gradlew clean deletes it.
MAP="$APP/build/outputs/mapping/release/mapping.txt"
if [ ! -s "$MAP" ]; then
    echo "STOP: no $MAP. isMinifyEnabled is off or R8 did not run, so a crash from this build is" >&2
    echo "unreadable and this APK should not be sent." >&2
    exit 1
fi

# Component names from the manifest: <activity|service|receiver|provider|application> only, because
# uses-permission, action, meta-data and property reuse android:name for things that are not classes.
NAMESPACE="$(sed -n 's/.*namespace *= *"\([^"]*\)".*/\1/p' "$APP/build.gradle.kts" | head -1)"
if [ -z "$NAMESPACE" ]; then
    echo "STOP: no namespace in $APP/build.gradle.kts, so relative class names cannot be resolved." >&2
    exit 1
fi
COMPONENTS="$(tr -d '\n' < "$MANIFEST" | sed 's/</\n</g' \
    | grep -E '^<(activity|service|receiver|provider|application)[ >]' \
    | sed -n 's/.*android:name="\([^"]*\)".*/\1/p' \
    | while IFS= read -r n; do
        case "$n" in
            .*)   printf '%s%s\n' "$NAMESPACE" "$n" ;;
            *\.*) printf '%s\n' "$n" ;;
            *)    printf '%s.%s\n' "$NAMESPACE" "$n" ;;
        esac
      done)"
COUNT="$(printf '%s\n' "$COMPONENTS" | grep -c . || true)"
if [ "${COUNT:-0}" -lt 8 ]; then
    echo "STOP: only $COUNT manifest components were parsed, and this manifest declares more." >&2
    echo "The parser or the manifest changed; an empty list would pass every check below vacuously." >&2
    exit 1
fi
missing=""
while IFS= read -r cls; do
    if [ -n "$cls" ] && ! grep -q "^$cls ->" "$MAP"; then missing+="  $cls"$'\n'; fi
done <<< "$COMPONENTS"
echo "--- shrinking map: $(wc -l < "$MAP" | tr -d ' ') lines, $COUNT manifest components checked"
if [ -n "$missing" ]; then
    echo "STOP: these classes Android names in the manifest are absent from mapping.txt, so R8" >&2
    echo "removed or renamed them and the component will fail to start on the phone:" >&2
    printf '%s' "$missing" >&2
    exit 1
fi
echo "  every one is present in the map, so each can be deobfuscated from a stack trace"

# ── Gate 3: signed, by a key that validates, with a scheme Android accepts ─────────────────────────
# apksigner's exit status is not read, because signer() swallows it to filter the jar's WARNING lines.
# The verdict is taken from what it printed, which is also what a person would check by eye.
if [ -z "$APKSIGNER" ]; then
    echo "STOP: no apksigner under ${SDK:-no SDK root detected}, so this signature cannot be checked." >&2
    echo "Install build-tools or set ANDROID_HOME and re-run. D1/S5 in the doc explains the check." >&2
    exit 1
fi
echo "--- signature and certificate"
VERIFY="$(signer verify --print-certs "$APK")"
printf '%s\n' "$VERIFY"
if printf '%s' "$VERIFY" | grep -q "DOES NOT VERIFY"; then
    echo "STOP: this APK does not verify. Do not send it." >&2
    exit 1
fi
if ! printf '%s' "$VERIFY" | grep -q "certificate SHA-256 digest:"; then
    echo "STOP: apksigner printed no certificate digest, so nothing here proves who signed it." >&2
    exit 1
fi
echo "--- signature schemes present (v2 is what Android 11+ installs check)"
SCHEMES="$(signer verify -v "$APK")"
printf '%s\n' "$SCHEMES"
if ! printf '%s' "$SCHEMES" | grep -qE 'Verified using v[0-9](\.[0-9])? scheme[^:]*: true'; then
    echo "STOP: no signature scheme verifies on this APK; it installs nowhere." >&2
    exit 1
fi

# ── Every gate passed, so the artefacts become visible ────────────────────────────────────────────
mkdir -p "$DIST"
cp -f "$APK" "$OUT"
cp -f "$MAP" "$MAPDIST"

echo "--- sha256 (give this to whoever installs it, so they can check the file was not changed)"
checksum "$OUT"

echo
echo "Built  : $OUT"
echo "Map    : $MAPDIST   (keep it; it is how you read a crash from this build)"
echo "Version: $VERSION (versionCode $CODE)"
echo "Signer : $KEYSTORE (alias $ALIAS)"
echo
echo "Still unverified by this script: anything that needs the phone. The release build is a"
echo "different binary from the dev one - R8 obfuscation, no debug flag, real backend URL - so run"
echo "the Ship-It checklist in docs/SILENT_VERIFICATION.md before you send it to anyone."
