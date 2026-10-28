#!/usr/bin/env bash
# Builds both Couchglass apps without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
#   ./build.sh            build home and care into out/
#   ./build.sh home       build one app
#
# Both apps must be signed with the same key: Couchglass Care only accepts
# commands (clean, sleep timer, power off) from apps signed like itself.
# Environment:
#   ANDROID_HOME   Android SDK (default ~/Android/Sdk); needs build-tools and platforms/android-34
#   JAVAC          a javac from JDK 11 or newer (default: $JAVA_HOME/bin/javac, then Android Studio's)
#   KEYSTORE       signing keystore (default ~/.android/couchglass.jks; created on first build)
#   KS_PASS        keystore and key password (default "couchglass"; set your own for releases)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/couchglass.jks}"
KS_PASS="${KS_PASS:-couchglass}"

if [ -z "${JAVAC:-}" ]; then
    # First javac new enough to understand --release (JDK 9+); Java 8 is common and too old.
    for c in "${JAVA_HOME:-}/bin/javac" /opt/android-studio/jbr/bin/javac "$(command -v javac || true)"; do
        if [ -x "$c" ] && "$c" --help 2>&1 | grep -q -- "--release"; then JAVAC="$c"; break; fi
    done
fi
if [ -z "${JAVAC:-}" ]; then
    echo "Need javac from JDK 11 or newer: set JAVAC or JAVA_HOME." >&2
    exit 1
fi
KEYTOOL="$(dirname "$JAVAC")/keytool"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias couchglass \
        -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Couchglass" >/dev/null 2>&1
    echo "Created signing key $KEYSTORE"
fi

build() {
    local app="$1" out="out/$1"
    rm -rf "$out" && mkdir -p "$out/compiled" "$out/gen" "$out/classes" "$out/dex"
    "$BT/aapt2" compile --dir "$app/res" -o "$out/compiled/res.zip"
    local assets=()
    [ -d "$app/assets" ] && assets=(-A "$app/assets")
    "$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$app/AndroidManifest.xml" \
        -o "$out/unsigned.apk" --java "$out/gen" "$out/compiled/res.zip" --auto-add-overlay "${assets[@]}"
    "$JAVAC" --release 11 -nowarn -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$out/classes" \
        $(find "$app/src" "$out/gen" -name '*.java')
    "$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$out/dex" $(find "$out/classes" -name '*.class')
    (cd "$out/dex" && zip -q -j ../unsigned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --out "out/couchglass-$app.apk" "$out/aligned.apk" 2>/dev/null
    echo "Built out/couchglass-$app.apk ($(du -k "out/couchglass-$app.apk" | cut -f1) KB)"
}

for app in "${@:-home care}"; do
    for a in $app; do build "$a"; done
done
