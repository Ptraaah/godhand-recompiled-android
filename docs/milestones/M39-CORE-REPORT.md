# M39 CORE REPORT — research-backed lag + audio fixes (core lane only)

Date: 2026-11-11. Scope: native core only. No APK was repackaged and no Android shell
source was touched in this lane; the shell lane's candidate APK carries the m38 core,
and this core drops into it as `libgodhand.so` + `libpcsx2gs.so` replacements.
Source of the fix list: `~/workspace/research_notes/godhand-ps2-audio-stutter-forum-fixes-20261110-1735/report.md`
(web-derived research, used for its ranked shortlist and warnings only).

## Deliverables

| File | Size (bytes) | md5 |
|---|---|---|
| `~/workspace/godhand-android/m39-core/libgodhand-m39.so` | 101,489,680 | `2f21b07128c4a02916995af089871ab1` |
| `~/workspace/godhand-android/m39-core/libpcsx2gs-m39.so` | 28,791,480 | `d14eedbd247e7d7a16dc5975b39c60b8` |

Work dir: `~/workspace/godhand-android/m39core-build-work/` — `orig/` snapshots (pre-edit,
with md5s), `m39core.patch` (godhand-recomp, 4 files), `m39core-bridge.patch` (path-b, 1 file),
build logs (`variant-build.log`, `stage-build.log`, `cmake-*.log`), host gate logs in `replay/`,
`STATUS.md` chronology.

Renderer safety: the core is the Path B variant build (`GODHAND_PATHB_DEFAULT_PCSX2`), so the
glue still forces `PS2X_GS_RENDERER=pcsx2` by compiled-in default — the phone's persistent
`PS2X_GS_RENDERER=native` ini cannot poison this test, same mechanism as m38 (b5/m27).

## Per research item — verdict

| # | Research item | Verdict | Evidence |
|---|---|---|---|
| 1 | Async non-blocking 48 kHz audio, ~50 ms buffer | **IMPLEMENTED** | `PS2X_AUDIO_ASYNC` (default on): WSOLA time-stretch bypassed, callback resampler holds the queue at 2,400 frames = 50.0 ms. See change 1. |
| 2 | Kill synchronous GS readbacks (HW Download Mode = Disable Readbacks) | **IMPLEMENTED** | The control existed in the PCSX2 config (`GSHardwareDownloadMode`) but was unreachable from the port — the bridge never parsed a key for it and the config kept its accurate `Enabled` default. Bridge key `hw_download` added; Android default + variant seed = 2 (NoReadbacks). Guest local↔host transfers (`read_fifo`/`read_local` in the bridge) are NOT gated by this mode, so guest-visible behaviour is unchanged. See changes 3+4. |
| 3 | GS/IOP work off the main thread | **Already present** | GS runs on the bridge's own renderer thread; IOP is driven in EE-sync batches with its own subsystem (threading stats exist under `PS2X_IOP_THREAD_STATS`); VU1 async worker since pathb5. No change made. |
| 4 | Minimum/Basic blending + full texture preloading | **IMPLEMENTED (blending) / already present (preloading)** | Blending: bridge default was Basic (1); now Android default + variant seed = 0 (Minimum). Preloading: `GSOptions.TexturePreloading` already defaults to Full (2) in this tree; a `texture_preloading` bridge key was added so the value is explicit in the open line. See changes 3+4. |
| 5 | MTVU equivalent | **Already present** | Async VU1 (`PS2X_VU1_ASYNC=1` seeded by the variant glue since pathb5), unchanged. |
| 6 | VU1 compiled, not interpreted | **Already present** | AOT generated VU1 programs (`registerGeneratedVu1Programs`), unchanged. |
| 7 | Resolution 1–1.5× | **Already present** | `PS2X_GS_SCALE` default is already 1×; nothing to lower. (The recipe's 2× was not adopted — the research itself ranks 1–1.5× for this GPU class.) |
| 8 | Cheap SPU2 (Nearest interpolation, reverb off, ≤ ~3%) | **IMPLEMENTED** | `PS2X_SPU2_INTERP=nearest` skips the 4-tap Gaussian-style interpolation table in `runVoice`; reverb off via the pre-existing `PS2X_SPU2_REVERB` switch. Both seeded on by the variant glue. See changes 2+5. |
| 9 | EE underclock (50% cycle rate) | **Not present — not implemented** | No EE cycle-rate knob exists in the static recomp scheduler. The nearest mechanisms (`PS2X_EE_LOOP_CYCLES` cycle accounting, `EeScheduler`) are global guest timing; inventing a scale on top would be an untested timing hack. Per the task condition (ship only if it exists) and the research's own warning (last resort, risky, single-source for God Hand), it was left out entirely — there is no hidden toggle. |

Snake-oil list respected: no EE cycle skipping, no frame skip, no VU cycle stealing, no
unsafe presets, sample rate stays 48 kHz, no tiny audio buffers (the device stream period
went UP, 512 → 1024 frames), no Catmull-Rom change.

## What changed, file by file

1. **`godhand-recomp/runtime/ps2xRuntime/src/lib/ps2_audio.cpp`** — audio rework.
   - The path end to end (investigated): SPU2 (`iop_spu2.cpp`) mixes voices in `advanceTo`,
     accumulates 512-frame (10.7 ms) chunks, flushes via the IOP host sink into
     `PS2AudioBackend::submitPcm`. The old backend fed a 16,384-sample ring (170.7 ms
     capacity) under a mutex shared with the device callback, and below ~97% speed engaged a
     WSOLA TimeStretch (documented in-tree failure: doubled sound attacks) while holding that
     mutex; queue target was 8,192 samples = 85.3 ms; device period was 512 frames = 10.7 ms.
     Nothing blocked the EE thread directly, but the stretch's CPU cost landed on the IOP/audio
     feed path exactly when the scene was already heavy.
   - New `PS2X_AUDIO_ASYNC` (default on; `=0` restores the exact m38 path): `submitPcm` skips
     TimeStretch entirely; the existing callback resampler becomes the sync mechanism — in
     async mode its correction widens from ±0.5% to −15%/+5% (gain 0.10) around a queue target
     of 4,800 samples = 2,400 frames = **50.0 ms at 48 kHz** (non-async keeps 8,192 = 85.3 ms).
     Below full speed the sound now runs slightly slow instead of crackling or doubling.
     Ring-full drop floor follows the same target member.
   - `PS2X_AUDIO_STREAM_BUFFER` (default 1024 frames = 21.3 ms; 512 was the m38 default):
     the device-side stream period passed to `SetAudioStreamBufferSizeDefault`.
   - Boot proof, printed once at audio init:
     `[audio] m39 feed: PS2X_AUDIO_ASYNC=1 (async resample feed, time-stretch bypassed), stream buffer 1024 frames (21.3 ms), queue target 50.0 ms, prime 42.7 ms, ring capacity 170.7 ms at 48000 Hz`
2. **`godhand-recomp/runtime/ps2xIOP/src/emulator/devices/iop_spu2.cpp`** — cheap SPU2.
   - SPU2 interpolation was a fixed 4-tap sinc/Gaussian-style weight table in `runVoice` with
     no switch; reverb was already switchable (`PS2X_SPU2_REVERB`, default on, in `mixCore`).
   - New `PS2X_SPU2_INTERP`: `nearest` (or `0`) returns the newest decoded sample and skips
     the table; unset/anything else keeps the exact m38 path. One-time boot proof in `reset()`:
     `[spu2] m39 mode: PS2X_SPU2_INTERP=nearest, PS2X_SPU2_REVERB=0`
3. **`godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp`** — GS option defaults.
   - `kOptions` gains `PS2X_PCSX2_HW_DOWNLOAD → hw_download` and
     `PS2X_PCSX2_TEXTURE_PRELOADING → texture_preloading` (non-Android parity).
   - Android block (same pattern as the existing FXAA default): emits `hw_download=` from
     `PS2X_GS_HW_DOWNLOAD`, else `PS2X_PCSX2_HW_DOWNLOAD`, else **2**; emits `blending=` from
     `PS2X_PCSX2_BLENDING`, else **0**. Both keys are skipped in the generic loop on Android
     to avoid duplicates. Boot proof at GS open:
     `[gs] m39 options: hw_download=2 (PS2X_PCSX2_HW_DOWNLOAD), blending=0 (PS2X_PCSX2_BLENDING)`
     and the bridge's own open line echoes the full resolved options string.
4. **`path-b/compat/BridgeVK.cpp`** (`ApplyOptions`) — the bridge learns two keys:
   `hw_download` → `options.HWDownloadMode` (clamped 0..4), `texture_preloading` →
   `options.TexturePreloading` (clamped 0..2). This is why `libpcsx2gs-m39.so` ships: the GS
   library did change. (The shipped m38 APK's `libpcsx2gs.so` was verified byte-identical to
   this tree's pre-edit build, md5 `0fc367db8b63cd733d1bccd8c19b8574`, so the delta is exactly
   this patch.)
5. **`godhand-recomp/android/glue/godhand_android_main.cpp`** — variant seeds, existing
   pattern (external `godhand.ini` value wins and passes through; otherwise the variant
   default is `setenv`'d; either way a `native: path B variant: KEY=value (...)` proof line
   is printed). New seeds: `PS2X_AUDIO_ASYNC=1`, `PS2X_SPU2_INTERP=nearest`,
   `PS2X_SPU2_REVERB=0`, `PS2X_PCSX2_HW_DOWNLOAD=2`, `PS2X_PCSX2_BLENDING=0`. The owner's
   kill switch for each is the same-named line in the external godhand.ini — e.g.
   `ps2x_audio_async=0` restores the m38 audio feed wholesale, `ps2x_pcsx2_hw_download=0`
   and `ps2x_pcsx2_blending=1` restore the m38 GS config, `ps2x_spu2_interp=gauss` +
   `ps2x_spu2_reverb=1` restore the m38 sound.

## Switch summary (shipped defaults = research fast config)

| Switch | Shipped default | Off / m38 value | Set by |
|---|---|---|---|
| `PS2X_AUDIO_ASYNC` | 1 (on) | 0 | glue seed |
| `PS2X_AUDIO_STREAM_BUFFER` | 1024 frames | 512 | code default |
| `PS2X_SPU2_INTERP` | nearest | gauss (any other value) | glue seed |
| `PS2X_SPU2_REVERB` | 0 | 1 | glue seed (switch pre-existed) |
| `PS2X_PCSX2_HW_DOWNLOAD` | 2 (NoReadbacks) | 0 (Enabled) | glue seed + backend Android default |
| `PS2X_PCSX2_BLENDING` | 0 (Minimum) | 1 (Basic) | glue seed + backend Android default |

## Gates run

- **Full Android variant build**: clean, rc=0 (`variant-build.log`); no compiler warnings in
  any edited file. The build also compiled 13,229 generated game-code objects from scratch
  in this tree state (the stage pass was interrupted by the runtime at 9,912/13,229 with no
  compile error — "interrupted by user", objects kept — and the variant build resumed and
  completed the remaining 3,317 steps plus the glue TU and the link).
- **GS bridge build** (`path-b/build-b1`, target `pcsx2gs`): clean; `hw_download` /
  `texture_preloading` literals verified in the object file (in the linked .so all
  ApplyOptions key literals are compile-time-optimized into immediate compares — true of the
  pre-existing keys in the m38 library as well).
- **Host syntax gates**: `ps2_audio.cpp` and `iop_spu2.cpp` pass `c++ -fsyntax-only`
  (host toolchain, tree include set) — logs in `m39core-build-work/replay/host-syntax-*.log`.
- **Binary content gate**: the delivered `libgodhand-m39.so` contains all five new
  `path B variant` seed literals, the renderer seed, and the `[audio] m39 feed:` /
  `[gs] m39 options:` / `[spu2] m39 mode:` format strings (22 `path B variant` literals in
  total).
- **Behaviour arbitration note**: no VU/EE/generated-game code was touched (the two patches
  cover exactly the 5 files above), so m38's VU fixture arbitration carries over unchanged.
  Every behaviour change is env-gated, and in host contexts (no seeds set) the defaults are:
  audio async ON in code (this is the one deliberate code-default change — the backend is
  shared with the desktop port, where `PS2X_AUDIO_ASYNC=0` restores m38 behaviour),
  SPU2 interpolation unchanged unless `nearest` is set, GS keys inert on non-Android.
  No host bench exercises the Android audio output path, so the audio verdict rests on the
  build + code-path review; the device test below is the real proof, as with m38.

## Device-test markers (what Pablo's boot log should show)

```
native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)
native: path B variant: PS2X_AUDIO_ASYNC=1 (variant default; ...)
native: path B variant: PS2X_SPU2_INTERP=nearest (variant default; ...)
native: path B variant: PS2X_SPU2_REVERB=0 (variant default; ...)
native: path B variant: PS2X_PCSX2_HW_DOWNLOAD=2 (variant default; ...)
native: path B variant: PS2X_PCSX2_BLENDING=0 (variant default; ...)
[gs] m39 options: hw_download=2 (PS2X_PCSX2_HW_DOWNLOAD), blending=0 (PS2X_PCSX2_BLENDING)
[pcsx2-gs] ... options: ...;hw_download=2;blending=0;...        (bridge open line echo)
[spu2] m39 mode: PS2X_SPU2_INTERP=nearest, PS2X_SPU2_REVERB=0
[audio] m39 feed: PS2X_AUDIO_ASYNC=1 (async resample feed, time-stretch bypassed), stream buffer 1024 frames (21.3 ms), queue target 50.0 ms, prime 42.7 ms, ring capacity 170.7 ms at 48000 Hz
```

Honest expectation note for the parent: these are the research's top-ranked configuration
changes, but the report's own framing stands — audio stutter is mostly a symptom of speed
below 100%, and no single change here is a guaranteed cure for the m38 "still very laggy"
verdict. The two structural wins are the removed synchronous GS readbacks and an audio path
that can no longer stretch-stall or crackle when the scene gets heavy; both are now in with
per-switch kill switches if the device verdict goes the other way.
