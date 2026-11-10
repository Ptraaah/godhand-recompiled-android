# Path B, stage B3 — pipelined harvest, duplicate suppression, fxaa off

Status: **built, packaged, verified host-side. The device verdict is the
owner's run.** B3 attacks exactly the serialized present chain convicted by
the pathb3 owner run (owner-0.25d-run/ANALYSIS.md): per present ~30-32 ms,
of which gs-fetch 6.1-7.3 ms was the synchronous `pcsx2gs_grab_frame`
readback, and presents (160-165/window) exceeded fields (127-154/window),
so the same field was presented more than once. The audio path is
deliberately untouched: the choppiness is the emulation running at ~half
speed (adma starve counters were zero), not an audio bug.

## What changed

### 1. Pipelined harvest — `path-b/compat/BridgeVK.cpp`

The bridge no longer snapshots on demand. At **every vsync** (in the
bridge's `Vsync()`, right after `GSvsync` merges the field into the
device's current texture), `CaptureFrame()` records the snapshot into a
ring of **3 capture slots** and submits the command buffer immediately
with `GSDeviceVK::ExecuteCommandBuffer(false)` — `GetWaitType(false, ...)`
is `WaitType::None` unconditionally, so the kick never waits. Each slot
holds a stretch target (`CreateRenderTarget`, held across frames instead
of create/recycle per snapshot) and a `GSDownloadTexture`; the recorded
device fence counter (`GetCurrentFenceCounter()`) tags the copy.

The GPU steps are the ones `GSRenderer::SaveSnapshotToMemory` performs —
`StretchRect` with `ShaderConvert::TRANSPARENCY_FILTER`, then
`GSDownloadTexture::CopyFromTexture` — split so the wait is never taken:
the copy executes while the next field is being built. The source rect is
the whole texture; `GSConfig.Crop` is zero under the bridge's options, so
the synchronous snapshot's `CalculateDrawSrcRect` yields the full rect
too (pixel-identical output to pathb3).

`PollCaptures()` (called from grabs and from `CaptureFrame`) is poll-only:
slots whose fence is covered by `GetCompletedFenceCounter()` — a counter
the device's own command-buffer rotation (`ActivateCommandBuffer`) keeps
current — are moved into CPU pixel buffers. `Flush()` is called only on
an already-completed slot, where it takes its early return (no submit, no
wait); `Map()` then only invalidates the host cache. If a ring slot is
still in flight when its turn comes, the capture for that field is
**dropped, never waited on** (counted in the bridge as
`g_capture_dropped`; see limits). `GSreset` invalidates captured content
without freeing resources; `Close()` destroys the ring before `GSclose()`
while the device is still alive.

New export **`pcsx2gs_grab_frame2(dst, capacity, &w, &h, &counter)`**:
like the B1 grab, plus the bridge vsync counter of the frame served. A
size query (dst == nullptr) refreshes the staged serve; the copy call
that follows serves exactly that staged frame, so the counter the host
checked always names the pixels it receives. Frames lag the GS by about
one field. `pcsx2gs_grab_frame` remains as a thin wrapper (the backend's
fallback if a bridge without the new export is ever paired with it). The
API table and `PCSX2GS_API_VERSION` are untouched.

### 2. Backend presents only new frames — `gs_pcsx2_backend.{h,cpp}`

`GSPcsx2Backend::Present()` (Android path) resolves
`pcsx2gs_grab_frame2` optionally at load and dedupes by the frame
counter: when the staged frame's counter equals the last counter handed
to the host, `Present()` returns an empty frame — no pixel copy, no host
re-upload of the same field. The counter is reset in `EnsureOpen()` (a
reopened bridge restarts its counter at zero).

One supporting change makes suppression safe:
`GS::latchHostPresentationFrame()` (`gs_frontend.cpp`) previously
**cleared** the latched frame whenever a backend returned nothing, which
would have blanked the host window on every suppressed duplicate. It now
keeps the previously latched frame when the backend returns no new frame
(and still starts empty before the first frame ever, and still clears on
the no-backend teardown path). This also improves the built-in backends'
"no new frame this latch" case from a black flash to holding the last
image; in shared-present mode the latch result is unused, as before.

### 3. fxaa off by default — `gs_pcsx2_backend.cpp` (`EnsureOpen`)

On Android the options string now emits `fxaa=0` unless re-enabled:
**`PS2X_GS_FXAA=1`** re-enables it (`PS2X_PCSX2_FXAA` is honoured when
`PS2X_GS_FXAA` is unset). Windows keeps its previous default (on, via the
options table). Honest note: in the surfaceless harvest the present pass
that applies FXAA never runs (there is no swap chain), so on this build
the setting is insurance against FXAA entering the composed frame's path,
plus one full-screen post pass saved wherever the config would enable
one. The boot log proves the setting: the bridge's open line now reads
`options: fxaa=0;...`.

### 4. `[pathb]` harvest stats — `gs_pcsx2_backend.cpp` (`NoteHarvest`)

Every ~5 s of wall time, from `Present()`:

```
[pathb] harvest: grabbed=<n> suppressed=<n> readback_wait=<ms> ms (window <s> s)
```

- `grabbed` — new frames handed to the host in the window.
- `suppressed` — Present() calls skipped because the bridge frame
  counter had not advanced (the same field would have been presented
  twice).
- `readback_wait` — total milliseconds spent inside the bridge grab calls
  during the window. Before B3 this same path carried the ~6.6 ms
  synchronous download of *every* present (a 5 s window at ~32
  presents/s would have summed to roughly 1,000 ms); with pipelining it
  should be poll + memcpy only — a few ms per window.

## Log markers for the next owner run

Success looks like (in order):

```
native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)
settings: env: PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)
[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)
[pcsx2-gs] shaderc: failed to load libshaderc_shared.so.1: ...      (expected, B2)
[pcsx2-gs] shaderc: loaded libshaderc_shared.so (unversioned fallback)
[pcsx2-gs] Vulkan surfaceless, scale 1 (0x0), surface 640x448, options: fxaa=0;
[pcsx2-gs] VK features: ...
[pathb] harvest: grabbed=... suppressed=... readback_wait=... ms (window ~5 s)   (repeating)
```

And in `[frame-split]`: gs-fetch should fall from 6.1-7.3 ms toward the
texture-upload remainder (~1 ms), and [ee-work]'s present park should
shrink from ~4.0 ms/field. If `[pathb]` lines never appear, the harvest
stats never ran (Present() not being called — same failure shape as a
black screen; send the log as-is).

## APK

- APK: `~/workspace/godhand-android/godhand-0.25-m24-pathb4.apk`
  (also at `package/godhand-android.apk`) — **136,696,533 bytes**
- Zip for delivery: `~/workspace/godhand-android/godhand-0.25-m24-pathb4.zip`
  — **34,839,280 bytes** (`unzip -t` clean)
- Package `dev.ptraaa.godhand`, versionCode **28**, versionName
  **0.25-m24-pathb4**, minSdk 26, targetSdk 35 — installs over pathb3 in
  place.
- Signer: the shared ptraaa debug signer, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  (apksigner: v2 + v3 verified, 1 signer).
- Contents (`lib/arm64-v8a/`, md5-matched build tree ↔ APK):
  `libgodhand.so` **98,490,944 B** (variant; md5
  `e099e6b5a22cdfff8938a774a749d6e6`), `libpcsx2gs.so` **28,098,608 B**
  (rebuilt with the pipelined harvest; md5
  `96a5efdea52b2b8d9b4543d8ca2a0db0`), `libshaderc_shared.so` 10,007,448 B
  (unchanged), plus `classes.dex`. A copy of the variant library is kept
  at `~/workspace/godhand-android/libgodhand-0.25-m24-pathb4.so`.

## Verification done here

- Bridge rebuild (`path-b/build-b1`, target `pcsx2gs`): exit 0, link
  still under `--no-undefined`; exports verified:
  `pcsx2gs_get_api`, `pcsx2gs_set_aspect`, `pcsx2gs_grab_frame`,
  **`pcsx2gs_grab_frame2`**.
- Game variant build: configured with `-DGODHAND_PATHB_DEFAULT_PCSX2=ON`;
  the touched TUs (`gs_pcsx2_backend.cpp`, `gs_frontend.cpp`) compiled
  clean; the variant `libgodhand.so` carries the variant strings, the
  `[pathb]` format string, `pcsx2gs_grab_frame2` and `PS2X_GS_FXAA`.
- Packaging with `GODHAND_PATHB=1`: exit 0, Path B staging confirmed,
  apksigner v2+v3 verified with the signer above, version 28 /
  0.25-m24-pathb4 confirmed via aapt; APK libraries md5-matched against
  the build trees.
- Desktop compile check: `gs_frontend.cpp` and `gs_pcsx2_backend.cpp`
  (stub branch) compiled with the desktop tree's own recorded compile
  commands (`build/linux-m1a/compile_commands.json`) — both exit 0. A
  full desktop relink + boot smoke was not run (same position as B1).
- Mainline restoration, verified after packaging: option configured back
  to **OFF** and target `godhand` rebuilt (exit 0). The restored
  `build/android-m2a/src/libgodhand.so` is 98,486,752 bytes with zero
  variant strings (larger than B1's mainline by the B3 backend/frontend
  code, which ships in mainline exactly as B1's backend did; the variant
  delta remains only the glue seed). The build tree is left in its
  normal state.
- Not done here (no Android device on this VM): executing the pipelined
  harvest. The on-device proof is the owner run below; the `[pathb]`
  line exists precisely so that run convicts or clears the change.

## Honest limits

- **VU1 AOT execution cost is out of scope and now the largest single
  consumer**: 1,827-2,134 ms per 5 s window (37-43% of EE-thread time)
  in the pathb3 run. B3 removes the present chain's serialization; it
  does not make the guest cheaper. Fields/s will still be capped by EE
  work (VU1 AOT + guest) once the present stalls are gone.
- **One field of harvest latency** is the design's price (bounded by the
  3-slot ring; if the GPU ever falls a full ring behind, a capture is
  dropped rather than waited on — the drop counter is bridge-internal
  and not printed; sustained drops would show up as `grabbed` lagging
  the field rate in `[pathb]`/`[frame-split]`).
- **Host-loop redraws remain**: [frame-split] counts host iterations,
  and on iterations where the host loop runs without a new field it
  still redraws the held texture (UploadFrame re-uploads the latched
  copy). B3 removes the backend copy and the readback wait from every
  duplicate; the residual present cost lives in the shared host loop,
  above the backend, and was left alone deliberately.
- The 21-22 ms [frame-split] "sync" is the host waiting for the next EE
  field tick — emulation speed, not present overhead. It shrinks only
  if fields/s rises (the EE's ~4.0 ms/field present park that B3 removes
  is part of that, the rest is guest/VU1 cost).
- The capture's extra `vkQueueSubmit` per field is the submit
  BeginPresent would have performed at the next vsync anyway, moved
  earlier; net submit count per field is unchanged.

## Owner test steps

1. Install `godhand-0.25-m24-pathb4.apk` over the current build (same
   signer, in-place update). **Do not edit any file** — if an
   escape-hatch `godhand.ini` exists in
   `Android/data/dev.ptraaa.godhand/files/`, delete it first, otherwise
   this build will (correctly) boot the built-in GS.
2. Boot the game and play for a minute or two (the title screen alone
   exercises the harvest, but gameplay loads the VU1/EE side that the
   [frame-split] comparison needs).
3. Send back the full boot log from that run. It should contain the
   marker block above, in particular `options: fxaa=0;` in the
   `[pcsx2-gs] Vulkan surfaceless` line and repeating `[pathb] harvest:`
   lines with `readback_wait` in the single-digit ms per window.
4. Revert (only if needed): create
   `Android/data/dev.ptraaa.godhand/files/godhand.ini` containing the
   single line `PS2X_GS_RENDERER=native` and reboot the game — the
   built-in GS is back, no reinstall. Delete that file to return to
   PCSX2.

## Files changed

- `path-b/compat/BridgeVK.cpp` — pipelined capture ring (3 fence-tracked
  staging slots, initiated per vsync, submitted without waiting),
  poll-only grab serve, `pcsx2gs_grab_frame2` export with the frame
  counter; `pcsx2gs_grab_frame` kept as a wrapper. Capture teardown on
  Close, invalidation on Reset/Open.
- `godhand-recomp/runtime/ps2xRuntime/include/runtime/gs/gs_pcsx2_backend.h`
  — harvest stats members + `NoteHarvest` declaration.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp` —
  `g_grabFrame2` resolution; `Present()` dedupe by frame counter +
  stats; `NoteHarvest()` printing `[pathb]`; fxaa off by default on
  Android (`PS2X_GS_FXAA=1` re-enables); counter/stats reset on open.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_frontend.cpp` —
  `latchHostPresentationFrame()` keeps the previous latched frame when
  the backend returns no new frame, instead of clearing it.
- `godhand-recomp/android/shell/AndroidManifest.xml` — 28 /
  0.25-m24-pathb4.
- Not changed: the audio path, ARUS, the variant glue/CMake, the API
  table, and every normal-build renderer default (the built-in GS stays
  the mainline default; fxaa's Windows default is unchanged).
- Logs: `path-b/build-b3.log`, `m24d-configure.log`, `m24d-build.log`,
  `m24d-package.log`, `m24d-restore-configure.log`,
  `m24d-restore-build.log`.
