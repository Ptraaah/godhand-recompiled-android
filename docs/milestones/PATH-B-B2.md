# Path B, stage B2 — GSopen failure fixed: shaderc never loaded on Android

Status: **convicted in source, fixed, rebuilt, packaged, verified
host-side. The device verdict is the owner's run.** B1b's switch, bridge
plumbing and GSopen call all worked on the POCO F5; `GSDeviceVK::Create()`
then died at its first shader compile because **the shader compiler
library itself was never loaded** — and every diagnostic that would have
said so was routed to a log channel the device owner cannot see.

## The failure chain, traced in source

Owner log (owner-0.25b-run, session 2026-11-04 11:03):

```
[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)
[pcsx2-gs] GS: Failed to compile utility pipelines
[pcsx2-gs] Error: Failed to create render device. ...
[pcsx2-gs] GSopen failed
```

The line comes from `GSDeviceVK::Create()`
(`path-b/src/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp`, the combined
check at line 2259 pre-fix):

```cpp
if (!CompileConvertPipelines() || !CompilePresentPipelines() || !CompileInterlacePipelines() ||
    !CompileMergePipelines() || !CompilePostProcessingPipelines() || !InitSpinResources())
```

Everything before that check in `Create()` had already succeeded on
device — device + surfaceless swap-chain setup, `CheckFeatures()`,
null texture/framebuffer, `ReadShaderSource("shaders/vulkan/tfx.glsl")`,
pipeline layouts, render passes, buffers — because each of those steps
has its own distinct `ReportErrorAsync` line and none of them printed.
The first step inside the combined check is `CompileConvertPipelines()`
(GSDeviceVK.cpp:4192), whose first action after reading
`shaders/vulkan/convert.glsl` (no "Failed to read" line either, so the
baked shader sources are fine) is:

```
CompileConvertPipelines
  -> GSDeviceVK::GetUtilityVertexShader (GSDeviceVK.cpp:3941)
  -> VKShaderCache::GetVertexShader (VKShaderCache.cpp:665)
  -> VKShaderCache::GetShaderModule -> CompileShaderToSPV (VKShaderCache.cpp:228)
  -> dyn_shaderc::Open() (VKShaderCache.cpp:135)
```

`dyn_shaderc::Open()` is the gate for **every** shader compile in the
renderer, and on non-Windows it loads the library by this name:

```cpp
const std::string libname = DynamicLibrary::GetVersionedFilename("shaderc_shared", 1);
```

`DynamicLibrary::GetVersionedFilename` (`path-b/src/common/DynamicLibrary.cpp`)
formats that as **`libshaderc_shared.so.1`**, and
`DynamicLibrary::Open()` is a bare `dlopen(filename, RTLD_NOW)`.

That file does not exist on the device. The shipped APK's
`lib/arm64-v8a/` contains exactly one shaderc file,
**`libshaderc_shared.so`** (10,007,448 B) — Android APK native library
directories hold the unversioned name; the `.so.1` symlink only exists
in desktop distro installs (upstream's comment in the code says as
much: "bundle post-processing adds it"). Android does not ship
libshaderc as a system library either. So the dlopen fails, `Open()`
returns false, `CompileShaderToSPV` returns an empty optional for every
shader, the first utility vertex shader comes back `VK_NULL_HANDLE`,
and `Create()` reports the generic line. The failure was invisible
because `dyn_shaderc::Open()` reports through `ERROR_LOG` (the PCSX2
console), which the bridge never routes to the host boot log;
`DumpBadShader` likewise writes into the app's private Logs folder.

Corroboration that the unversioned name resolves on this device: the
game backend itself dlopens **`libpcsx2gs.so` by bare soname** and it
resolved from the same app library directory in the same run (the
`[gs] renderer:` line printed and GSopen was entered). The B1-prep
vktest also proved the bundled `libshaderc_shared.so` itself works on
the Adreno 725 when opened — its symbols resolved and the harness
passed. Only the versioned *name* was unreachable.

### Candidates checked and refuted

- **Feedback-loop / tile-image utility shaders compiled unconditionally:**
  refuted as the cause. The first compile in `Create()` is the convert
  *vertex* shader, whose generated header (`AddShaderHeader`,
  GSDeviceVK.cpp:3895) is `#version 460 core` +
  `GL_EXT_samplerless_texture_functions : require` plus feature defines;
  it uses no tile-image or feedback-loop functionality, and it is gated
  by the same `dyn_shaderc::Open()` as everything else. A
  feature-specific shader failure could only occur *after* shaderc
  loads; shaderc never loaded.
- **shaderc target-env / GLSL configuration in the bridge compile path:**
  refuted. `CompileShaderToSPV` sets `shaderc_target_env_vulkan, 0` and
  performance optimization — upstream's exact desktop configuration for
  these same shader sources, which compile daily on Linux/Windows
  builds of this snapshot. Nothing in the option set is Android- or
  bridge-specific; the bridge never touches shader options.
- **A utility pipeline requiring a feature the surfaceless device did
  not enable:** refuted for this failure. `vkCreate*Pipelines` is only
  reached with valid shader modules in hand; the run died at module
  creation (null module from the first `GetUtilityVertexShader`), before
  any pipeline object was built. The one hard device-extension
  requirement, `VK_KHR_push_descriptor`, is present in the owner's
  census (owner-vktest-run/log.txt), and `CheckFeatures()` — which
  hard-fails on missing format bits with its own log line — had already
  passed.

## The fix

All changes are downstream patches in the extracted tree, commented as
such; mainline (non-variant) game defaults are untouched, and the
desktop behaviour of the shader cache is unchanged (the versioned name
is still tried first everywhere).

### `path-b/src/pcsx2/GS/Renderers/Vulkan/VKShaderCache.cpp`

1. **`dyn_shaderc::Open()` — the actual fix.** On load failure, after
   the existing `ERROR_LOG`, the error is mirrored to stderr (the
   bridge's `[pcsx2-gs]` log channel). On Android only, it then retries
   with the unversioned name
   (`DynamicLibrary::GetVersionedFilename("shaderc_shared")` →
   `libshaderc_shared.so`) before giving up; both the retry's success
   and its failure are logged to stderr with the dlopen error text.
2. `LOAD_FUNC` symbol failures and `shaderc_compiler_initialize()`
   failure are mirrored to stderr the same way.
3. **`CompileShaderToSPV()`** — on a failed compile, stderr now gets
   `[pcsx2-gs] shaderc: compile failed (shader kind N, status <status>): <shaderc's full error text>`,
   in addition to the existing `ERROR_LOG` and bad-shader dump.
4. **`VKShaderCache::GetShaderModule()`** — a `vkCreateShaderModule`
   failure is mirrored to stderr with its `VkResult`, in addition to
   `LOG_VULKAN_ERROR`.

### `path-b/src/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp`

5. **`GSDeviceVK::Create()`** — the six-way combined check is split
   into six per-step checks (same calls, same order), each reporting
   through the same Host channel with its step named:
   `Failed to compile utility pipelines (convert)`, `(present)`,
   `(interlace)`, `(merge)`, `(post-processing)`, `(spin resources)`.

### `godhand-recomp/android/shell/AndroidManifest.xml`

6. versionCode **27**, versionName **0.25-m24-pathb3**.

## What the next device log will show

On a healthy boot, two new lines appear before the bridge's feature
line:

```
[pcsx2-gs] shaderc: failed to load libshaderc_shared.so.1: dlopen failed: library "libshaderc_shared.so.1" not found
[pcsx2-gs] shaderc: loaded libshaderc_shared.so (unversioned fallback)
```

(the first line is the previously-swallowed error, now visible — it is
expected on Android and is not a failure of the fix). If anything still
fails afterwards, the log names it exactly: either the
`[pcsx2-gs] shaderc: compile failed (shader kind N, status ...): ...`
line with shaderc's own message, a `vkCreateShaderModule failed:
VkResult N` line, and/or `Failed to compile utility pipelines (<step>)`
saying which of the six steps died. No silent generic failure remains
on this path.

## APK

- APK: `~/workspace/godhand-android/godhand-0.25-m24-pathb3.apk`
  (also at `package/godhand-android.apk`) — **136,647,381 bytes**
- Zip for delivery: `~/workspace/godhand-android/godhand-0.25-m24-pathb3.zip`
  — **34,818,688 bytes** (`unzip -t` clean)
- Package `dev.ptraaa.godhand`, versionCode **27**, versionName
  **0.25-m24-pathb3**, minSdk 26, targetSdk 35 — installs over pathb2
  in place.
- Signer: the shared ptraaa debug signer, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  (apksigner: v2 + v3 verified, 1 signer).
- Contents (`lib/arm64-v8a/`): `libgodhand.so` **98,488,760 B** (the
  B1b variant, md5-matched build tree ↔ APK — the game side did not
  change in B2), `libpcsx2gs.so` **28,050,056 B** (rebuilt with the
  fix; md5 `f00e3416a78752a0b0045cda83f73919`, md5-matched build tree ↔
  APK), `libshaderc_shared.so` 10,007,448 B (unchanged), plus
  `classes.dex`. A copy of the variant library is kept at
  `~/workspace/godhand-android/libgodhand-0.25-m24-pathb3.so`.

## Verification done here

- Bridge rebuild (`path-b/build-b1`, target `pcsx2gs`): exit 0; only
  the two touched TUs recompiled; link still under `--no-undefined`.
  The shipped `.so` contains all five `[pcsx2-gs] shaderc:` format
  strings and all six per-step `Failed to compile utility pipelines
  (...)` strings; exports `pcsx2gs_get_api` / `pcsx2gs_set_aspect` /
  `pcsx2gs_grab_frame` intact.
- Game variant build: configured with
  `-DGODHAND_PATHB_DEFAULT_PCSX2=ON` (20 ninja edges, define confirmed
  in `compile_commands.json`); `libgodhand.so` is byte-size-identical
  to B1b's variant and carries the variant strings.
- Packaging with `GODHAND_PATHB=1`: exit 0, Path B staging confirmed,
  apksigner v2+v3 verified with the signer above, version 27 /
  0.25-m24-pathb3 confirmed via aapt.
- Mainline restoration, verified after packaging: option configured
  back to **OFF** and target `godhand` rebuilt (exit 0). The restored
  `build/android-m2a/src/libgodhand.so` is **98,485,064 bytes — exactly
  B1's mainline size** — with zero variant strings. The build tree is
  left in its normal state.
- Not done here (no Android device on this VM): executing the fixed
  library. The on-device proof is the owner run below; the new logging
  guarantees that run convicts whatever remains, if anything.

Logs: `path-b/build-b2.log`, `m24c-configure.log`, `m24c-build.log`,
`m24c-package.log`, `m24c-restore-configure.log`,
`m24c-restore-build.log`.

## Owner test steps

1. Install `godhand-0.25-m24-pathb3.apk` over the current build (same
   signer, in-place update). **Do not edit any file** — if you created
   the escape-hatch `godhand.ini` after the black screen, delete it
   first, otherwise this build will (correctly) boot the built-in GS.
2. Boot the game and let it reach the **title screen**. PCSX2's GS is
   active from this first boot.
3. Send back:
   - a screenshot of the title screen (or of whatever appears instead —
     black screen, freeze, or crash are all verdicts), and
   - the full boot log from that run. Success looks like:
     `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)`,
     `settings: env: PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`,
     `[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)`,
     the two `[pcsx2-gs] shaderc:` lines (versioned-load miss +
     unversioned fallback loaded),
     `[pcsx2-gs] Vulkan surfaceless, ...`, and
     `[pcsx2-gs] VK features: ...`.
     If it still fails, the log will now contain the precise reason
     (a `[pcsx2-gs] shaderc: compile failed ...` line and/or
     `Failed to compile utility pipelines (<step>)`) — send it as-is.
4. Revert (only if needed): create
   `Android/data/dev.ptraaa.godhand/files/godhand.ini` containing the
   single line `PS2X_GS_RENDERER=native` and reboot the game — the
   built-in GS is back, no reinstall. Delete that file to return to
   PCSX2.

Known caveat carried over unchanged: the present harvest does a
synchronous snapshot download per presented frame. If the title screen
renders but slowly, pipelining that download is the next job.

## Files changed

- `path-b/src/pcsx2/GS/Renderers/Vulkan/VKShaderCache.cpp` — shaderc
  load fallback (Android unversioned name) + stderr mirroring of load,
  symbol, initialize, compile and shader-module errors.
- `path-b/src/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp` — per-step
  utility-pipeline failure reports in `Create()`.
- `godhand-recomp/android/shell/AndroidManifest.xml` — 27 /
  0.25-m24-pathb3.
- Not changed: the bridge, the backend, the variant glue/CMake, ARUS,
  and every normal-build default.
