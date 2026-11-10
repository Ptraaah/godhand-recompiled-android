#!/bin/sh
# Shell packaging, CI edition: the same no-Gradle method as the local build
# (aapt2 + javac + d8 + zipalign), but UNSIGNED and without the game core.
# libgodhand.so is built locally from the owner's own disc and never passes
# through CI; signing stays local too.
# If GODHAND_LIBS_DIR points at a folder of .so files (the CI bridge job's
# output), they are staged into lib/arm64-v8a/ so the artifact shows exactly
# what CI produced. Needs ANDROID_SDK_ROOT with platform android-36 and
# build-tools 36.0.0, plus a JDK 17 on PATH.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT}}"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
WORK="$HERE/build"
OUT="$WORK/godhand-shell-unsigned.apk"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/stage/lib/arm64-v8a"

# 1. Compile the shell and dex it.
find "$HERE/src" -name '*.java' > "$WORK/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
"$BT/d8" --lib "$ANDROID_JAR" --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')
cp "$WORK/dex/classes.dex" "$WORK/stage/classes.dex"

# 2. Stage any CI-built native libraries (stored, not compressed, like the
#    local packaging: targetSdk 35 loads .so files straight from the APK).
if [ -n "${GODHAND_LIBS_DIR:-}" ]; then
    for so in "$GODHAND_LIBS_DIR"/*.so; do
        [ -f "$so" ] && cp "$so" "$WORK/stage/lib/arm64-v8a/"
    done
fi

# 3. Compile the resources (the manifest points at @mipmap/ic_launcher),
#    link the APK around the manifest, add dex + libs, page-align.
"$BT/aapt2" compile --dir "$HERE/res" -o "$WORK/compiled-res.zip"
"$BT/aapt2" link -o "$WORK/unaligned.apk" -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 35 \
    "$WORK/compiled-res.zip"
( cd "$WORK/stage" && zip -q -0 -r "$WORK/unaligned.apk" classes.dex lib )
"$BT/zipalign" -f -p 4 "$WORK/unaligned.apk" "$OUT"
"$BT/aapt2" dump badging "$OUT" | head -4
ls -la "$OUT"
echo "unsigned shell APK: $OUT (no libgodhand.so inside - the core is built locally)"
