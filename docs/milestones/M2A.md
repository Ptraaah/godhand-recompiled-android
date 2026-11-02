# Milestone 2a: the port boots on Android — APK built, phone run is the owner's

Date: 2026-11-01. Scope: cross-compile the proven M1c tree (LasVola
godhand-recomp + the real generated game code) for arm64-v8a, wrap it in a
minimal Android shell of ours, and package an installable APK whose boot
log the owner can send back. Logs are the `m2a-*.log` files in this folder.

**Deliverable: `package/godhand-android.apk` — 96,530,956 bytes, package
`dev.ptraaa.godhand`, versionName `0.1-m2a` (versionCode 1), minSdk 26 /
targetSdk 35, signed with the same debug keystore as the JFG and
extractor builds (signer SHA-256 `9791d26c…dbc9005e`, apksigner v2+v3
verified), so it installs alongside them with nothing to uninstall.**

What is proven here is everything provable without the phone: the whole
tree — all 13,189 build steps — compiles and links for arm64, and the
APK passes signature, alignment and packaging checks. The on-device half
of the ladder (install, process start, how far the boot gets against the
real ISO) is the owner's run; section 6 is his instructions, section 7 is
the honest list of what his run will be the first to prove.

## 1. How the Android build is shaped

The vendored runtime turned out to carry real Android scaffolding from
its ran-j/LightVelox lineage, which this milestone builds on rather than
reinvents: `ps2xRuntime/CMakeLists.txt` detects `ANDROID`, forces
raylib's `PLATFORM "Android"`, links `log`/`android` into the host
backend, skips the desktop debug UI, and opens audio through raylib's
Android (OpenSLES) path. raylib 5.5's own CMake brings
`android_native_app_glue` and provides `android_main()`, which calls the
app's `main()`.

Our layer on top (all new files under `godhand-recomp/android/`, nothing
upstream rewritten):

- **The game target becomes the NativeActivity library.** On Android,
  `src/CMakeLists.txt` builds `godhand` as a SHARED library
  (`libgodhand.so`) instead of an executable, links
  `-Wl,--undefined=ANativeActivity_onCreate` (the same trick the
  runtime's own Android runner target uses) and
  `-Wl,-z,max-page-size=16384`. The manifest's GameActivity is a
  framework `NativeActivity` with `android.app.lib_name="godhand"`.
- **`src/main.cpp` is compiled with `-Dmain=godhand_port_main`** for
  that one TU. The real entry is ours,
  `android/glue/godhand_android_main.cpp`, whose `main()` prepares the
  desktop world the port expects before calling the port's main:
  - `chdir()` to the app's internal files dir — the port resolves
    `godhand.ini`, `controls.ini`, `game_data/` and `memcards/` next to
    the "exe", which off Windows means the current directory.
  - stdout/stderr are dup2'd onto **`godhand-boot.log`** in the app's
    external files dir (`Android/data/dev.ptraaa.godhand/files/`). The
    port's log macros `fprintf`+`fflush` per line, so the file shows
    exactly how far the boot got even if the process dies. This is the
    JFG startup-diary pattern.
  - Boot flags `--headless --time-limit 120` (the port's own shipped
    ini defaults), and two env defaults: `PS2X_GS_GPU=0` (first boot on
    the CPU rasteriser — the GPU back end's shaders are desktop
    `#version 460` GLSL; porting them is a later milestone) and
    `GODHAND_ISO_PREFETCH=0` (headless skips prefetch anyway; belt and
    braces against a 1.57 GB background read on a phone).
- **The disc image arrives as `/proc/self/fd/<n>`.** The launcher's SAF
  picker (the extractor app's proven flow) takes a persistable read
  permission for the owner's ISO; `GameActivity.onCreate` opens a
  `ParcelFileDescriptor` for it *before* `super.onCreate()` starts the
  native thread and parks it in `IsoHolder` (the static reference keeps
  the fd open for the whole run). The glue reads the path through JNI
  and passes it as `--iso`. The port's disc layer only ever opens a
  path and reads/seeks, which a `/proc/self/fd` path satisfies.
- **The shell** (`android/shell/`, packaged by `build-apk.sh` with the
  same no-Gradle aapt2/javac/d8 pipeline as the extractor): a launcher
  activity with Pick ISO / Start game / Share boot log / Reload, and a
  monospace log view fed by `godhand-boot.log`. The port's `main()`
  ends in `std::_Exit` by design, so a finished boot kills the process;
  the owner reopens the app and the log of the run is on screen. No
  permissions are requested by the app.

One deferral, stated plainly: **touch input is not wired at all in
2a** — not even the "any touch = start" stub the brief allowed. The
host-gamepad layer reports no devices on Android (patch 0006); feeding
touch into the runtime's pad path is its own piece of work and the boot
proof does not need it. It is first on the 2b list.

## 2. The SIMD problem, and the sse2neon answer

The runtime headers (`ps2_runtime.h`, `ps2_memory.h`,
`ps2_runtime_macros.h`) and about a dozen sources include the x86
intrinsics headers (`<immintrin.h>`, `<smmintrin.h>`, `<xmmintrin.h>`,
…) *unconditionally*, and the SSE4.1 intrinsics behind them are used
throughout the EE/VU/GS code. There is no ARM path anywhere in the
tree (the Vita support is a separate, stale arrangement).

Rather than editing ~15 files, `android/simd/` vendors **sse2neon**
(DLTcollab/sse2neon, MIT, fetched 2026-11-01; see `SSE2NEON-LICENSE`
there) plus seven one-line wrapper headers (`xmmintrin.h`,
`emmintrin.h`, `pmmintrin.h`, `tmmintrin.h`, `smmintrin.h`,
`nmmintrin.h`, `immintrin.h`) that include it. Patch 0005 puts that
directory on the include path for Android builds only, so every x86
intrinsics include resolves to the NEON translation. A pre-build grep
confirmed no `_mm256`/AVX usage anywhere in the compiled path (SSE
through SSE4.1 only — sse2neon's coverage), and the full build
confirmed it in practice: **not one SIMD-related compile error in
13,189 steps.**

## 3. Compiled as-is vs patched

Nearly everything compiled exactly as M1c left it — the generated game
code (10,600 boot sources + 2,460 module sources) needed **zero**
changes for clang/arm64. The complete list of tree modifications is
two patch files next to the existing four:

- `patches/0005-android-build-support.patch` (CMake only):
  - Top level: on `ANDROID`, force `GODHAND_DEBUG_UI` off and add
    `android/simd` to the include path.
  - `src/CMakeLists.txt`: the Android SHARED-library branch for the
    `godhand` target (main rename, glue source, the two link options
    above), and one fix that is not Android-specific at all: the game
    layer includes `ps2_host_input.h` from the runtime's `src/lib`,
    a directory previously added to the include path only by the
    debug-UI block — so *any* build with the debugger off (Android is
    just the first) failed on `button_prompts.cpp`. The directory is
    now added unconditionally.
- `patches/0006-clang-libcxx-android-fixes.patch` (two runtime files):
  - `ps2_pnach.cpp`: `std::to_string(time.time_since_epoch().count())`
    is ambiguous under libc++ — its filesystem clock counts in
    `__int128`, which converts equally to every `to_string` overload
    (libstdc++ counts in `int64_t`, which is why GCC never saw it).
    Fixed with a `static_cast<long long>`, correct on both libraries.
  - `ps2_host_gamepads.cpp`: the non-SDL fallback calls raw GLFW
    functions that raylib does not have on Android. The GLFW
    declarations and call sites are now `#if !defined(__ANDROID__)`,
    and on Android the backend honestly reports no gamepads (plus the
    startup log line no longer claims GLFW there).

A runtime-only probe build (the runtime's own `ps2EntryRunner` Android
SHARED target, plus raylib and the IOP) was built first and passed
with only the shim and these two fixes — that de-risked the host side
before the 13k-step build started.

Build configuration: NDK r27c (27.2.12479018) toolchain file,
`arm64-v8a`, `android-26`, `ANDROID_STL=c++_static`,
`RelWithDebInfo` with `-O2 -g0 -DNDEBUG` (no DWARF — the M1c archives
were ~21 GB with debug sections; these objects total ~170 MB and the
link never came close to the VM's RAM), raylib from the pre-seeded
`deps-src/` checkout (no network fetch), `-j4`.

## 4. The register_functions note (patch 0004's lesson, clang edition)

`generated/register_functions.cpp` (the 12.6 MB table initializer)
kept patch 0004's non-MSVC `-O0` + no-PCH rule, and the compile line
verifiably ends with `-O0` after the config's `-O2`. It still took
clang on the order of tens of minutes of wall clock on this throttled
VM — where GCC `-O0` needed ~2.5 minutes in M1c — because the cost is
in clang's front end over the ~100k-entry initializer list, not in
the optimizer. It completed (object: 3,849,752 bytes) and is a
one-time cost per clean build; incremental rebuilds never revisit it.
Recorded so the next person does not mistake it for a hang: check for
CPU burn, not for output.

## 5. Verification (off-device, all direct checks)

- Full build: `cmake --build build/android-m2a --target godhand`
  (logs `m2a-build.log`, `m2a-build2.log` — the second is the resume
  after the `button_prompts` include failure that patch 0005 now
  fixes). Final state: all targets built, `libgodhand.so` linked.
- `libgodhand.so`: 96,507,800 bytes, ELF64 AArch64 DYN. **Every PT_LOAD
  segment has alignment `0x4000`** — the 16 KB page-size requirement
  from the JFG lessons holds. Exports include `ANativeActivity_onCreate`
  and `main` (both `T`). `g_ps2RecompiledFunctionTable`,
  `…TableBase`, `…TableEnd` are present as data symbols; the symbol
  table holds 53,266 symbols, 28,192 of them `func_*`-family — the
  whole generated game is in, not a subset (for scale, the M1c Linux
  executable's table has 27,813 symbols).
- APK: `zipalign -c -p` passes (`lib/arm64-v8a/libgodhand.so` stored
  uncompressed at a 16 KB-aligned offset, as targetSdk 35 requires);
  contents are exactly `AndroidManifest.xml`, `resources.arsc`,
  `classes.dex` (8,376 bytes — the three shell classes) and the one
  native library; apksigner verifies v2+v3, one signer, the JFG
  keystore.

## 6. Owner test instructions (plain words)

1. Install `godhand-android.apk` (it sits next to the other builds;
   same signer, nothing to uninstall first). The app is called
   **God Hand (recompiled)**.
2. Open it. Tap **1. Pick your God Hand (USA) ISO** and pick the ISO
   from the Download folder — the same file the extractor used.
   (Skipping this is also a valid first run: the game boots, logs its
   "no disc image found" line and exits — that alone proves the
   process runs on the phone.)
3. Tap **2. Start the game**. The screen goes to the (hidden-window)
   game activity. Leave it for up to ~2 minutes: the run has a
   120-second limit built in, and when the boot ends — however far it
   got — the app closes itself. That closing is expected, not a crash
   of the shell.
4. Reopen **God Hand (recompiled)**. The boot log is on screen. Tap
   **Share boot log** and send it back in chat. (The file also lives
   at `Android/data/dev.ptraaa.godhand/files/godhand-boot.log` if a
   file manager is easier.)
5. If Android refuses the install or the game will not open at all,
   send a screenshot of exactly what it says — that is the report too.

## 7. Proven vs unproven

Proven by this milestone:

- The complete LasVola tree + the real generated game compiles for
  arm64-v8a with two small patches and a vendored SIMD shim, and links
  into a single native library with 16 KB-aligned segments.
- The APK packages, aligns and signs correctly under the established
  keystore, with the shell, the NativeActivity glue and the boot-log
  path all in place.

Unproven until the owner's phone run (in ladder order):

- **Install** on the POCO F5's Android 15 (off-device checks all pass;
  the install itself is his).
- **Process start**: raylib's Android glue in this exact vendored
  combination has never run anywhere — window creation, the OpenSLES
  audio device and raylib's hidden-window flag on Android are all
  first-contact. A failure there lands in the boot log (or in a
  tombstone, whose top frames the owner can screenshot).
- **The disc path end-to-end**: reading a 1.57 GB ISO through a
  `/proc/self/fd` path, `verifyDiscAndPrepare` extracting into the
  files dir (the full image carries `MODULES/IRX.IMS`, so the gate
  that stopped the M1c synthetic disc should pass), and the EE boot
  itself — IOP module load, first GS frames on the CPU rasteriser.
  Wherever it stops is the M2b starting point.
- Touch input (deferred by design, see section 1), the GPU GS back
  end (desktop GLSL; the M3 decision from the spike), saves, and any
  gameplay. Upstream's caveat stands: stages 1–3 verified on Windows,
  stages 4–8 unverified anywhere.
