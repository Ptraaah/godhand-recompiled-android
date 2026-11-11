# M44 — automatic graphics fallback for the Mali black screen (0.45-m44-gsfallback, versionCode 52)

Built 2026-11-11. m43 (the pacing bisect) + one core fix: when the PCSX2
Vulkan GS fails to open, the game now swaps to the built-in GS in the
same session instead of drawing nothing forever. Shell is byte-for-byte
m43 (the shipped m43 classes.dex goes in verbatim); only the two native
libs the change touches were rebuilt.

## The bug

First public report (2026-11-11): YouTube commenter @uwaiskold on a
Dimensity 8050 (Mali-G610) — black screen, game audio running normally.
That is the exact signature this project already hit on 2026-11-04
(pathb2): the bridge's GSopen fails, the PCSX2 back end by design draws
nothing, and there was no fallback, while the game runs on behind empty
frames. Any GPU whose driver rejects the bridge's Vulkan setup gets a
permanent black screen on m42/m43.

## The failure chain (shipped m43 sources, file:line)

1. Selection: `GS::init` builds the raster back end via
   `GSThreadedBackend::MakeDefault()`
   (runtime/ps2xRuntime/src/lib/gs/gs_threaded_backend.cpp:703). If
   `GSPcsx2Backend::Requested()` (gs_pcsx2_backend.cpp:240) succeeds —
   library dlopens, matching `pcsx2gs_get_api`, `pcsx2gs_grab_frame`
   exported — the threaded PCSX2 back end is chosen. Load-level failures
   already fall back here with "[gs] ... using the built-in GS" lines.
2. The actual open is LAZY: `GSPcsx2Backend::EnsureOpen()`
   (gs_pcsx2_backend.cpp:480) runs on the GS worker thread at the first
   GS command. At :646, `m_open = g_api->open(&params) != 0` calls the
   bridge's GSopen (path-b/compat/BridgeVK.cpp `OpenDirect`, which
   prints "[pcsx2-gs] GSopen failed" and returns 0 at :481 when
   PCSX2's Vulkan device/pipeline creation fails — the 2026-11-04
   failure was "Failed to compile utility pipelines").
3. On failure, :662 sets `m_failed = !m_open` and :668 prints
   "[gs] PCSX2 GS failed to open; nothing will be drawn". From then on
   `EnsureOpen` short-circuits (:485), `Present()` returns {}
   (:414), VSync/RawGif/downloads all drop — while the EE and the
   (independent, async) audio keep running. Black screen + sound,
   forever, no retry, no fallback.

Why the fix cannot live at selection time: selection only learns that
the bridge library LOADED, not that GSopen will succeed — the open is
lazy and its result is private to the back end. And a swap cannot
happen below the front end: `GS::init` latches `m_rawGif` from the
back end (gs_frontend.cpp:337/349); the PCSX2 back end consumes the
GIF stream raw (`WantsRawGif()=true`, gs_pcsx2_backend.cpp:1654),
while the built-in back ends only accept decomposed draws
(`GSRasterBackend::RawGif` defaults to a no-op,
include/runtime/gs/gs_backend.h:49-50). A silent back-end swap would
leave the front end feeding raw GIF into a back end that discards it.
The swap must go through the front end — which owns exactly that seam:

`GS::setRasterBackend(std::unique_ptr<GSRasterBackend>)`
(gs_frontend.cpp:2105, declared gs_frontend.h:107) — takes both GS
mutexes, Flush+Sync-drains the old back end, snapshots its VRAM into
the front end's hand-off buffer, swaps, re-derives `m_rawGif`, and
Initializes the new back end with the preserved VRAM. It existed with
no callers; m44 is its first.

## The fallback design

- Failure signal: `GSPcsx2Backend::EnsureOpen` now also stores to a
  file-scope `std::atomic<bool>` at the exact point `m_failed` is set,
  exposed as the free function `bool ps2xPcsx2GsOpenFailed()`
  (gs_pcsx2_backend.cpp:29 declaration site / store at :682). No header
  changes: gs_frontend.h and gs_backend.h sit in the game code's PCH
  and stay untouched; gs_frontend.cpp declares the accessor itself.
- Trigger: the top of `GS::latchHostPresentationFrame()`
  (gs_frontend.cpp:800) — the front end's per-field present latch,
  called from the host loop (ps2_runtime.cpp:838 UploadFrame). If the
  flag is set (and the kill switch is off, and it has not fired yet),
  it calls `setRasterBackend(GSThreadedBackend::MakeNativeDefault())`
  and prints one line:

  `[gs] fallback: PCSX2 open failed (bridge GSopen returned failure), native renderer active (built-in GS, CPU)`

  (the parenthetical names the actual selection — "GPU (OpenGL)" if
  PS2X_GS_GPU selects it; on a stock install it is CPU).
- Why the host thread, never the GS worker: `setRasterBackend` drains
  the old back end with Flush+Sync. Calling it from the worker would
  deadlock the worker against itself. The worker only sets the atomic;
  the host thread performs the swap. The failure happens at the first
  GS command, so the swap lands within a field or two of boot.
- Why this is safe on the shipped path: when PCSX2 opens fine
  (Pablo's Adreno), the flag never sets, the latch check is one atomic
  load per field, and nothing else changes. `MakeDefault` was refactored
  so its tail IS `MakeNativeDefault()` (gs_threaded_backend.cpp) — the
  fallback builds literally the back
 end a native boot would have
  built. `NativeSelectionName()` shares the same env read so the log
  line can never name a different back end than the one installed.
- Kill switch: `PS2X_GS_FALLBACK=0` disables the swap (pre-m44
  behaviour), settable through the same ini/env layers as every other
  PS2X_ switch.
- Known limits, stated plainly:
  - GIF packets fed raw during the ~1-field window between the failed
    open and the swap are dropped by the dead back end. At boot that is
    at most the first frame or two of uploads; the game re-sends
    everything as it draws. A boot logo could glitch for a frame; no
    persistent state is lost (VRAM hand-off goes through the front
    end's snapshot, exactly what the seam was built for).
  - If a GS recording wrapper was active (PS2X_GS_RECORD — a dev tool,
    off in the shipped build), `setRasterBackend` replaces the wrapped
    back end wholesale and recording stops. Accepted: recording is not
    a player configuration.
  - The fallback target on a stock install is the built-in CPU GS:
    correct picture, reduced speed. That is the honest trade for a GPU
    the Vulkan bridge cannot open at all — a playable picture instead
    of none.
- Vulkan device line: `BridgeVK::OpenDirect`, right after its existing
  post-open feature dump, now prints
  `[pcsx2-gs] Vulkan device: <deviceName> (vendor 0x…, device 0x…, Vulkan x.y.z)`
  from `GSDeviceVK::GetDeviceProperties()` — data the device already
  holds, no extra Vulkan call. It prints on every SUCCESSFUL open (so
  future reports from other phones name their GPU in the log). On an
  open failure the device may already be torn down, so the failure
  case is covered by the bridge's own GSopen error lines plus the
  [gs] fallback line instead.

## Source changes

Runtime core (libgodhand.so) — originals snapshotted in
m44gsfallback-build-work/orig/:

- runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp: the
  `g_pcsx2GsOpenFailed` atomic + `ps2xPcsx2GsOpenFailed()` accessor
  (file scope, before the platform #if, so both build branches see
  them); one store in EnsureOpen where `m_failed` is set.
- runtime/ps2xRuntime/include/runtime/gs/gs_threaded_backend.h:
  declarations for `MakeNativeDefault()` / `NativeSelectionName()`.
  (This header is included only by .cpp files — not a PCH header.)
- runtime/ps2xRuntime/src/lib/gs/gs_threaded_backend.cpp:
  `MakeDefault()` refactored to `Requested() ? pcsx2 :
  MakeNativeDefault()`; the native selection moved verbatim into
  `MakeNativeDefault()`; shared `nativeUseGpu()` env read;
  `NativeSelectionName()`.
- runtime/ps2xRuntime/src/lib/gs/gs_frontend.cpp: file-local accessor
  declaration, once-flag, kill-switch read, and the latch hook.
  gs_frontend.h NOT touched (PCH discipline).

Bridge (libpcsx2gs.so):

- path-b/compat/BridgeVK.cpp: the Vulkan device line in OpenDirect.
  (This file is in the repo-tracked set — it and this report are the
  pieces to publish with an m44 release; the runtime-core edits live
  in the local tree only, same as the m39 core round.)

Shell: no source changes. The tree manifest carries only the standing
version bump (versionCode 51→52, versionName 0.44-m43-pacing →
0.45-m44-gsfallback).

## Builds

- Core: godhand-recomp/build/android-m2a, cache
  GODHAND_PATHB_DEFAULT_PCSX2 flipped ON for the variant build
  (the m39 discipline — Pablo's phone carries a persistent
  PS2X_GS_RENDERER=native ini, so the pcsx2 default must be compiled
  in), `ninja src/libgodhand.so`: 7 steps, clean — the three edited
  TUs, src/main.cpp (the other header consumer), re-archive, relink.
  No PCH invalidation (no PCH header touched). Cache flipped back OFF
  afterwards; the variant .so was staged into the work dir first.
  Variant proven in BOTH the shipped m43 lib and the new lib: the
  compiled-in default's boot-log marker string ("wins over
  godhand.ini") is present in each, and the new lib is only +3,048 B
  over the old — the code delta, not a configuration change.
  New symbols verified present in the shipped .so (llvm-nm):
  ps2xPcsx2GsOpenFailed, GSThreadedBackend::MakeNativeDefault,
  GSThreadedBackend::NativeSelectionName, GS::setRasterBackend.
- Bridge: path-b/build-b1, `ninja pcsx2gs`: BridgeVK.cpp + relink,
  clean. The "[pcsx2-gs] Vulkan device:" format string is present in
  the built .so (strings), as is "[gs] fallback:" in libgodhand.so.
- Packaging: m44gsfallback-build-work/package_m44.py (the m43 pattern,
  corrected where the saved m43 script disagreed with its own run's
  artifacts: resources go through `aapt2 compile` first, and the final
  APK is all-STORED entries in the shipped order — aapt2 link deflates
  the manifest and -A would bury the libs under assets/). classes.dex
  is taken VERBATIM from the delivered m43 APK: m44 changes no shell
  file, so the exact dex Pablo tested ships. (The in-tree sources are
  still compiled as a gate and compile clean; that dex is not shipped
  because build-tools 36's d8 emits DEX 038 with reshaped instructions
  against the shipped DEX 035 — toolchain noise, not a source delta.)

## Arbitration vs the delivered m43 APK (entry-for-entry)

| entry | m43 | m44 | verdict |
|---|---|---|---|
| AndroidManifest.xml | b7c0958c…, 2540 B, stored | a2a65f6f…, 2548 B, stored | DIFF — version strings only (aapt2 dump xmltree diff = exactly the versionCode 51→52 and versionName lines; +8 B is the longer name in UTF-16) |
| classes.dex | 917b4993…, 87,812 B | 917b4993…, 87,812 B | IDENTICAL (verbatim from m43) |
| lib/arm64-v8a/libgodhand.so | 2f21b071…, 101,489,680 B | d05376c8…, 101,492,728 B | DIFF — intended: the fallback (only lib the core change touches) |
| lib/arm64-v8a/libpcsx2gs.so | d14eedbd…, 28,791,480 B | ce07badc…, 28,793,912 B | DIFF — intended: the Vulkan device line (only lib the bridge change touches) |
| lib/arm64-v8a/libshaderc_shared.so | 2793f5b6…, 10,007,448 B | 2793f5b6…, 10,007,448 B | IDENTICAL |
| resources.arsc + 10 res PNGs | — | — | IDENTICAL, all |
| entry set / order / compression | all STORED | all STORED | same; .so data offsets page-aligned at the same offsets as m43 |

Gates: apksigner verify v2+v3 true; signer SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e (the
m39–m43 signer — installs over m43/m42); zipalign -c -p 4 successful;
aapt2 dump badging: dev.ptraaa.godhand, versionCode 52,
versionName 0.45-m44-gsfallback.

## Test status — honest

The fallback was NOT exercised end-to-end anywhere. It cannot be
exercised on this host: there is no Android device or emulator here,
and a host build never selects the PCSX2 back end at all
(Requested()'s library loading exists only on Android/Windows), so no
host harness can reach the failed-open state — the tree's offline
tools (GODHAND_BUILD_TOOLS: gs_replay etc.) have the same blind spot.
What IS proven: both libs compile clean in their real build trees; the
new code and log strings are present in the shipped binaries; the swap
seam is the front end's own designed hot-replacement path; the trigger
is a single latched failure bit set at the one place the shipped code
already detects and reports the failure.

The true proof is a Mali device. The m44 release should ask
@uwaiskold to install it and return the boot log. A successful retest
shows, in order: the bridge's own GSopen error lines, the historical
"[gs] PCSX2 GS failed to open; nothing will be drawn", then
"[gs] fallback: PCSX2 open failed (bridge GSopen returned failure),
native renderer active (built-in GS, CPU)" — and a picture on screen.
Pablo's POCO F5 (Adreno) should show NO fallback line and the new
"[pcsx2-gs] Vulkan device: Adreno …" line after "[pcsx2-gs] GSopen ok"
— worth one confirmation boot before release, since that is the
byte-for-byte old path this build promises to preserve.

## Deliverables

- godhand-0.45-m44-gsfallback.apk — 140,506,559 B, md5
  0e5ad5187a86bcdd73e7fbdc32feecf3
- godhand-0.45-m44-gsfallback.zip (APK alone inside) — 35,570,502 B,
  md5 635e06df86de8535de39f3a14c7dde7b
- Work dir: m44gsfallback-build-work/ (orig/ source snapshots,
  package_m44.py, core-build.log, bridge-build.log, package.log,
  staged libs)
