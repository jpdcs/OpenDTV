#!/bin/sh
# Builds the over-the-air logic bundle: the app's logic as compiled Java (classes.dex in a .jar) that the app loads
# at run time, like Expo's JavaScript bundle. See app/src/main/java/ph/opendtv/ota/LogicBundles.java.
#
#   tools/ota/build_bundle.sh --embed
#       puts the bundle inside the app (app/src/main/assets/ota): what a fresh install starts with.
#   tools/ota/build_bundle.sh --publish KEY.pem BASE_URL ["release notes"]
#       writes build/ota/bundle-N.jar + bundle.json signed with KEY.pem, ready to upload to BASE_URL.
#
# The version comes from VERSION in bundle/src/ph/opendtv/bundle/Logic.java. Needs a JDK (javac, jar), the Android
# SDK (ANDROID_HOME) and, for --publish, openssl.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
[ -d "$SDK" ] || { echo "Set ANDROID_HOME to the Android SDK"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
SHELL_API="$(sed -n 's/.*int SHELL_API = \([0-9]*\);.*/\1/p' "$ROOT/app/src/main/java/ph/opendtv/ota/LogicBundles.java")"
VERSION="$(sed -n 's/.*int VERSION = \([0-9]*\);.*/\1/p' "$ROOT/bundle/src/ph/opendtv/bundle/Logic.java")"
OUT="$ROOT/build/ota"
rm -rf "$OUT/work" && mkdir -p "$OUT/work/api" "$OUT/work/classes"

# The shell's interface is compiled only to build against; it is not put in the bundle.
javac --release 11 -d "$OUT/work/api" "$ROOT/app/src/main/java/ph/opendtv/ota/AppLogic.java"
(cd "$OUT/work/api" && jar cf ../api.jar .)
javac --release 11 -cp "$OUT/work/api.jar" -d "$OUT/work/classes" $(find "$ROOT/bundle/src" -name '*.java')
(cd "$OUT/work/classes" && jar cf ../bundle-classes.jar .)
JAR="$OUT/bundle-$VERSION.jar"
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --classpath "$OUT/work/api.jar" \
    --output "$JAR" "$OUT/work/bundle-classes.jar"
echo "built $JAR (bundle $VERSION, shell API $SHELL_API)"

case "$1" in
--embed)
    mkdir -p "$ROOT/app/src/main/assets/ota"
    cp "$JAR" "$ROOT/app/src/main/assets/ota/bundle.jar"
    printf '{"version": %s, "minShell": %s}\n' "$VERSION" "$SHELL_API" > "$ROOT/app/src/main/assets/ota/bundle.json"
    echo "embedded in app/src/main/assets/ota"
    ;;
--publish)
    KEY="$2"; BASE="$3"; NOTES="$4"
    [ -f "$KEY" ] && [ -n "$BASE" ] || { echo "usage: $0 --publish KEY.pem BASE_URL [notes]"; exit 1; }
    SHA="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"
    SIG="$(printf 'opendtv-bundle:%s:%s:%s' "$VERSION" "$SHELL_API" "$SHA" | openssl dgst -sha256 -sign "$KEY" | base64 | tr -d '\n')"
    printf '{\n  "version": %s,\n  "minShell": %s,\n  "url": "%s/bundle-%s.jar",\n  "size": %s,\n  "sha256": "%s",\n  "signature": "%s",\n  "notes": "%s"\n}\n' \
        "$VERSION" "$SHELL_API" "$BASE" "$VERSION" "$(wc -c < "$JAR" | tr -d ' ')" "$SHA" "$SIG" "$NOTES" > "$OUT/bundle.json"
    echo "wrote $OUT/bundle.json (upload it with bundle-$VERSION.jar to $BASE)"
    ;;
esac
