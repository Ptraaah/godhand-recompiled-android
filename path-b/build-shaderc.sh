#!/bin/sh
# Build libshaderc_shared.so the way the local B0 stage did it (PATH-B-B0):
# ndk-build the shaderc sources bundled with the NDK, then link the nine
# resulting archives into one shared library and strip it.
# The NDK copy is made fresh here every run: shaderc-src/ is a build
# byproduct, never committed. Needs ANDROID_NDK_HOME (NDK r27c).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:?set ANDROID_NDK_HOME to the NDK path}}"
SRC="$HERE/shaderc-src"
OUT="$HERE/shaderc-out"

rm -rf "$SRC" "$OUT"
cp -r "$NDK/sources/third_party/shaderc" "$SRC"
chmod -R u+w "$SRC"

# The shaderc tree keeps Android.mk at its root, not under jni/, so
# ndk-build needs the project layout spelled out or it aborts with
# "Could not find application project directory".
"$NDK/ndk-build" NDK_PROJECT_PATH="$SRC" APP_BUILD_SCRIPT="$SRC/Android.mk" \
    NDK_APPLICATION_MK="$SRC/Application.mk" -j"$(nproc)"

OBJ="$SRC/obj/local/arm64-v8a"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$OUT"
"$LLVM/clang++" --target=aarch64-linux-android26 -shared \
    -Wl,-soname,libshaderc_shared.so \
    -Wl,--whole-archive \
    "$OBJ/libshaderc.a" "$OBJ/libshaderc_util.a" "$OBJ/libglslang.a" \
    "$OBJ/libSPIRV.a" "$OBJ/libSPIRV-Tools.a" "$OBJ/libSPIRV-Tools-opt.a" \
    "$OBJ/libHLSL.a" "$OBJ/libOGLCompiler.a" "$OBJ/libOSDependent.a" \
    -Wl,--no-whole-archive -static-libstdc++ \
    -o "$OUT/libshaderc_shared.so"
"$LLVM/llvm-strip" "$OUT/libshaderc_shared.so"
ls -la "$OUT"
