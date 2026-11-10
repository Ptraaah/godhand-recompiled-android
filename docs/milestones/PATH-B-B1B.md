# Path B, stage B1b — 0.25-m24-pathb2: PCSX2 GS on by default, no file editing

Status: **built, packaged, verified host-side; the device verdict is the
owner's run.** This is a packaging variant of B1 (PATH-B-B1.md), not a new
renderer stage: the bridge, the backend and ARUS are byte-for-byte B1's. The
only difference is how the renderer switch gets its value.

## Why B1's owner test never tested anything

The B1 protocol asked the owner to edit `godhand.ini`. On Android that file
lives in the app's **internal** files dir: `src/main.cpp` calls
`loadSettings(exeDir / "godhand.ini", ...)` and on Android exeDir is the
internal files dir (the glue chdirs there), which Android will not let the
owner browse or edit without root. The external
`Android/data/dev.ptraaa.godhand/files/` folder holds only the exported boot
log and `vu-capture/` — no ini. The owner's log (owner-0.25-run/) proves the
outcome: every session's settings dump reads
`settings: ini: PS2X_GS_RENDERER=native` and there are zero `[pcsx2-gs]`
lines. Both sessions ran the built-in GS — which also means **step 1 of the
B1 protocol (default boot behaves exactly like 0.24) is already proven on
device** and does not need repeating.

## How settings actually resolve (read before changing anything)

1. The Android glue (`android/glue/godhand_android_main.cpp`, `main()`)
   runs first and `setenv`s the play shape with overwrite
   (`GODHAND_HEADLESS=0`, `PS2X_GS_SCALE`, ...). Those are the process's
   real environment by the time the port starts.
2. `godhand_port_main` → `loadSettings` folds the internal `godhand.ini`
   into the environment for keys **not already set there**; its notes print
   `env: X=... (wins over godhand.ini ...)` or `ini: X=...` per key. The
   ini is only *written* (from the baked `godhand/default_ini.h`, generated
   by `src/CMakeLists.txt` from the source `godhand.ini`) when missing —
   the owner's internal ini exists since long before 0.25 and says native,
   so a new baked default would never reach his device. (The source
   `godhand.ini` was therefore left untouched: it is also the desktop
   mainline's default, and changing it would have changed nothing on his
   phone anyway.)
3. The mapping that matters: ini `[Video] Renderer=` →
   `PS2X_GS_RENDERER` (`kIniSettings` in `src/main.cpp`), plus a
   passthrough: any raw `PS2X_*=` line in the ini is exported as-is (his
   internal ini carries the flat form, hence the dump's key spelling).
4. `GSPcsx2Backend::Requested()` reads `PS2X_GS_RENDERER` from the
   environment; its unset fallback is already `"pcsx2"`, and its comment
   says the fallback never decides on Android because the settings loader
   always sets the variable from the ini. Correct — which is why the fix
   had to act **above** the ini, in the glue's environment layer.

## The change (variant-gated, mainline untouched)

- `CMakeLists.txt` (top level): new option
  `GODHAND_PATHB_DEFAULT_PCSX2`, **OFF by default**. Declared before the
  `add_subdirectory` calls so `src/CMakeLists.txt` can see it.
- `src/CMakeLists.txt` (Android branch only): with the option ON, target
  `godhand` gets `GODHAND_PATHB_DEFAULT_PCSX2=1` as a private compile
  definition. Nothing else in the tree is compiled differently (the
  variant build was 20 ninja edges: the `godhand` target's own sources
  plus its link).
- `android/glue/godhand_android_main.cpp`: a `#ifdef
  GODHAND_PATHB_DEFAULT_PCSX2` block in `main()`, after the existing
  owner-settings `setenv` block and before `godhand_port_main`:
  1. It looks for `godhand.ini` in the **external** files dir
     (`<logDir>/godhand.ini`, i.e.
     `/sdcard/Android/data/dev.ptraaa.godhand/files/godhand.ini` — the
     folder the owner already sends the boot log from).
  2. If the file exists, it is parsed with `loadSettings`' own rules:
     a `PS2X_GS_RENDERER=<value>` line in any section (the passthrough
     shape) or `Renderer=<value>` inside `[Video]` (the desktop shape);
     the last matching line wins; comments/blank lines are skipped.
  3. The found value is `setenv`'d verbatim. If there is no file or no
     renderer line, it seeds `PS2X_GS_RENDERER=pcsx2`.
  4. Either way it prints one line to the boot log:
     `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; no
     renderer setting in the external .../godhand.ini)` or
     `native: path B variant: PS2X_GS_RENDERER=<value> (from the external
     .../godhand.ini)`.
- `android/shell/AndroidManifest.xml`: versionCode **26**, versionName
  **0.25-m24-pathb2**.

### Why this wins over the existing internal ini

The seed is in the real environment before `loadSettings` runs, and
`loadSettings` never overrides a variable that is already set — it logs it
as the winner instead. So on the owner's phone (whose internal ini says
native) the settings dump of a fresh pathb2 boot will read:

```
settings: env: PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)
```

— the dump itself names the winning layer, which is the proof requirement
1 asked for. `Requested()` then sees `pcsx2`, dlopens `libpcsx2gs.so`
(staged in the APK as in B1) and the `[gs] renderer: PCSX2 GS
(libpcsx2gs.so, Vulkan)` + `[pcsx2-gs] Vulkan surfaceless, ...` +
`[pcsx2-gs] VK features: ...` lines follow, exactly as specified in B1.

## Escape hatch (owner-reachable, no root, no reinstall)

Create the file:

```
/sdcard/Android/data/dev.ptraaa.godhand/files/godhand.ini
```
(shown as `Android/data/dev.ptraaa.godhand/files/godhand.ini` in a file
manager — the same folder as `godhand-boot.log`)

with either line:

```
PS2X_GS_RENDERER=native
```

or

```
[Video]
Renderer=native
```

Next boot runs the built-in GS again (the glue logs the value as coming
from the external file, and the settings dump still shows the env layer
winning — now with `native`). Deleting the file restores the pcsx2
default. In the **normal** (non-variant) build none of this exists: the
glue block is compiled out, the option defaults OFF, and the renderer is
selected by the internal ini exactly as before.

## APK

- APK: `~/workspace/godhand-android/godhand-0.25-m24-pathb2.apk`
  (also at `package/godhand-android.apk`) — **136,630,997 bytes**
- Zip for delivery: `~/workspace/godhand-android/godhand-0.25-m24-pathb2.zip`
  — **34,816,910 bytes** (the APK's stored .so entries compress well;
  `unzip -t` clean)
- Package `dev.ptraaa.godhand`, versionCode **26**, versionName
  **0.25-m24-pathb2**, minSdk 26, targetSdk 35 — installs over 0.25 in
  place.
- Signer: the shared ptraaa debug signer, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  (apksigner: v2 + v3 verified, 1 signer).
- Contents (`lib/arm64-v8a/`): `libgodhand.so` **98,488,760 B** (variant;
  md5-matched between the build tree and the APK), `libpcsx2gs.so`
  28,041,184 B and `libshaderc_shared.so` 10,007,448 B (both unchanged
  from B1), plus `classes.dex`. A copy of the variant library is kept at
  `~/workspace/godhand-android/libgodhand-0.25-m24-pathb2.so`.

## Verification done here

- Configure with `-DGODHAND_PATHB_DEFAULT_PCSX2=ON`: 20 ninja edges
  (define scoped to the `godhand` target; the 12.6k-edge game-code tree
  untouched). `compile_commands.json` confirms the glue TU's command
  carries `-DGODHAND_PATHB_DEFAULT_PCSX2=1`.
- The variant `libgodhand.so` contains both `native: path B variant:`
  log format strings; the string/logic chain above (glue seed →
  loadSettings env-wins note → `Requested()` → bridge open) was traced
  in source end to end.
- One process note: the first build's supervising shell was reaped just
  after the link edge completed (the log stops at `[19/20]`); the
  `.ninja_log` records the `src/libgodhand.so` edge as finished, a
  subsequent `ninja -n godhand` showed no pending compile/link edges,
  and the artifact carries the variant strings — the library is the
  completed variant build, not a partial one.
- Mainline restoration, verified after packaging: the option was
  configured back to **OFF** and target `godhand` rebuilt (exit 0). The
  restored `build/android-m2a/src/libgodhand.so` is **98,485,064
  bytes — exactly B1's mainline size** — and contains zero occurrences
  of the variant strings. The build tree is left in its normal state;
  the variant exists only in the packaged APK (and the stashed .so).
- Desktop: the glue file is Android-only, the CMake change sits inside
  `if(ANDROID)`, and the option defaults OFF — the desktop build is
  unaffected by construction.

## Owner test steps (the B1 verdict, minus the file editing)

1. Install `godhand-0.25-m24-pathb2.apk` over the current build (same
   signer, in-place update). **Do not edit any file.**
2. Boot the game and let it reach the **title screen**. PCSX2's GS is
   active from this first boot.
3. Send back:
   - a screenshot of the title screen (or of whatever appears instead —
     black screen, freeze, or crash are all verdicts), and
   - the full boot log from that run. It must contain
     `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)`,
     `settings: env: PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`,
     `[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)`,
     `[pcsx2-gs] Vulkan surfaceless, ...`, and
     `[pcsx2-gs] VK features: ...`. If instead it shows
     `[pcsx2-gs] GSopen failed` or "Required format ... is missing bits",
     send the log as-is — that is the precise failure B2 needs to know.
4. Revert (only if needed): create
   `Android/data/dev.ptraaa.godhand/files/godhand.ini` containing the
   single line `PS2X_GS_RENDERER=native` and reboot the game — the
   built-in GS is back, no reinstall. Delete that file to return to
   PCSX2.

Known B1 caveat carried over unchanged: the present harvest does a
synchronous snapshot download per presented frame. If the title screen
renders but slowly, pipelining that download is B2's first job.

## Files changed

- `godhand-recomp/CMakeLists.txt` — the `GODHAND_PATHB_DEFAULT_PCSX2`
  option (OFF by default).
- `godhand-recomp/src/CMakeLists.txt` — Android branch: the variant
  compile definition on target `godhand`.
- `godhand-recomp/android/glue/godhand_android_main.cpp` — header note,
  `<cctype>` include, and the `#ifdef` seed block in `main()`.
- `godhand-recomp/android/shell/AndroidManifest.xml` — 26 /
  0.25-m24-pathb2.
- Logs: `m24b-build.log`, `m24b-package.log`, `m24b-restore-build.log`.
- Not changed: the source `godhand.ini` / baked `default_ini.h`, the
  bridge, the backend, ARUS, and every normal-build default.
