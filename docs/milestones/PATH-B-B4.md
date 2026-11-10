# Path B, stage B4 — VU1 off the EE thread (MTVU pipeline switched on)

Status: **built, packaged, verified host-side. The device verdict is the
owner's run.** B4 attacks exactly the wall convicted by the pathb4 owner
run (owner-0.25e-run/ANALYSIS.md): with the present chain fixed, gameplay
saturates the EE thread — recompiled EE core ~45% + VU1 AOT (vu1ee)
35-54% serialized on one thread, parked only 5-13%, GPU idle 69-89%.

The headline finding of the investigation: **the asynchronous pipeline
already existed, complete, and was never switched on.** B4 is therefore
mostly a wiring stage — a new public switch, a variant default, and the
accounting that proves or refutes the gain — not a new threading design.

## Investigation: why vu1worker always showed 0 jobs

The `[ee-prof]` counter pair is split by the thread the MSCAL/MSCNT
callbacks ran on (`ps2x::eeprof::recordVu1Callback(cont, onEeThread, ...)`
in ps2_runtime.cpp): EE-thread callbacks fill `vu1Cb*` (printed as
vu1ee), any other thread fills `vu1CbWorker*` (printed as vu1worker).

The "other thread" is the VU worker of `ps2x::asyncvif`
(runtime/ps2xRuntime/src/lib/ps2_async_vif.cpp): a dedicated
`VUThread` (highest priority) plus a `GIFThread` stage. It is gated by
`asyncvif::active(memory)`, which creates the workers lazily the first
time it is called **and** `enabled()` is true. `enabled()` read the env
var `PS2X_MTVU`, falling back to `s_defaultEnabled` — and
`setDefaultEnabled()` has **zero callers** in the tree. On Android
nothing sets PS2X_MTVU either, so `enabled()` was false at every call
site, every VIF1/GIF transfer took the inline branch, every VU1 callback
fired on the EE thread, and vu1worker stayed 0. The plumbing was never
broken; it was never powered.

## The seam (what a job is, what state it carries)

The seam is the DMA-completion boundary, `PS2Memory::
processPendingTransfers()` (plus direct VIF1_FIFO writes in write128):

- **Snapshot**: with the pipeline active, each pending GIF-path3 and
  VIF1 transfer is *copied* into an `asyncvif::Item` (chain payloads are
  moved, memory payloads are byte-copied out of RDRAM/scratchpad at
  enqueue time, reusing pooled buffers). One `enqueue()` call bundles
  the batch into one `Job`, stamped with the caller's MXCSR and an FBRST
  snapshot (via the runtime's fbrst provider). The VIF1 command stream
  is itself the state carrier: MPG uploads rewrite VU1 microcode
  memory, UNPACK writes VU1 data memory, STCYCL/STROW/BASE/OFFSET/ITOP
  mutate the interpreter's registers, and MSCAL/MSCNT snapshot
  tops/itops from those registers at decode time — all in stream order,
  replayed by exactly one consumer.
- **Execution**: the VU worker replays the items in order
  (`processVIF1Data` / path3 `submitGifPacket` + arbiter drain). The
  MSCAL/MSCNT callbacks therefore fire on the worker, and the single
  `VU1Interpreter` instance (its register file, pipelines, decoded-code
  cache and AOT dispatch inside `runLoopWith`) is touched only from
  there — the same instance that used to be driven from the EE thread,
  moved wholesale, not shared. VU0 is a different interpreter reached
  through a different seam (macro mode) and is untouched.
- **Outputs**: XGKICK (GIF path 1) and DIRECT (path 2) leave the VU
  through `submitGifPacket` → `GifArbiter`, which serializes the three
  GIF paths into one ordered stream; path-1 payloads are chunked to the
  GIF stage thread, which is the thread that runs the GS front end
  (`m_gs.processGIFPacket`, hence the PCSX2 backend) for them. Path 3
  travels inside the same job and drains through the same arbiter.
- **Consumption / joins**: the guest can only observe VU/VIF results
  through points that already call `asyncvif::sync()` — VIF register
  writes (`writeIORegister` syncs on the async-VIF register set), the
  sync sites throughout ps2_memory.cpp (incl. the TRXDIR host-read
  path), and save-state save/load. On top of that sit the field-level
  joins: the EE scheduler's vblank waits for the pipeline to go idle
  (MTVU timing model, `PS2X_MTVU_VBLANK_WAIT` still defaults on), and
  `vu1Busy()` keeps the guest's view of VU-busy honest against the
  worker's published completed-cycle counter. FLUSHE/FLUSH/FLUSHA need
  no in-stream wait under this design: ordering is the queue's.
- **Bounds**: the job queue is capped at **64 jobs**; `enqueue()`
  blocks the producer on a condition variable at the cap (that
  backpressure time is counted as join wait, see below). GIF staging is
  capped at 8 MiB the same way. A slow VU therefore throttles the EE
  instead of growing memory, and — via the vblank join — slows the game
  instead of dropping fields, exactly the PCSX2 MTVU contract.

## What B4 changed

1. **The switch — `asyncvif::enabled()`** (ps2_async_vif.cpp): consults
   **`PS2X_VU1_ASYNC`** first, then the legacy `PS2X_MTVU`, then the
   compiled-in default (off). It flows through the standard settings
   machinery unchanged: godhand.ini reaches it via loadSettings'
   PS2X_* passthrough (an ini key becomes the env var), and a real
   environment value wins over the ini, as with every PS2X_* switch.
2. **Variant default — Android glue** (godhand_android_main.cpp, inside
   the existing `#ifdef GODHAND_PATHB_DEFAULT_PCSX2` block): the same
   external-ini scan that seeds PS2X_GS_RENDERER now also captures
   `PS2X_VU1_ASYNC`; absent a line there, the glue seeds `1` and logs
   `native: path B variant: PS2X_VU1_ASYNC=1 (variant default; ...)`.
   The owner's A/B switch is one line in the external godhand.ini:
   `PS2X_VU1_ASYNC=0` restores the pre-B4 behaviour exactly (the inline
   branches in processPendingTransfers/write128 were not modified).
   Mainline builds do not seed anything and are unchanged unless a user
   sets PS2X_VU1_ASYNC/PS2X_MTVU themselves.
3. **`[vu1]` window line** (EeScheduler.cpp, same ~5 s window as
   [ee-work]/[ee-prof], printed only when the pipeline is enabled or
   has ever queued — with the switch off the log is unchanged):

   ```
   [vu1] async on: queued=<n> run=<n> join_wait=<ms> ms (<n> waits) queue_hw=<n> (window ~5 s)
   ```

   queued/run are VU-worker **jobs** (one job = one
   processPendingTransfers batch, not one MSCAL — the per-callback
   split is still in [ee-prof], where vu1ee should collapse toward 0
   and vu1worker should carry the callbacks). join_wait is EE-side wall
   time blocked on the pipeline inside the window — sync() joins,
   syncVu() joins, waitPipelineIdle() (vblank/pacing) waits and
   enqueue() backpressure — counted by an RAII timer in
   ps2_async_vif.cpp, waits under 1 µs excluded so an already-idle
   pipeline costs nothing. queue_hw is the all-time queue depth
   high-water (bounded by the 64 cap). Counters are exposed as
   `asyncvif::queueStats()`; like every counter here they feed no
   execution, rendering or scheduling decision.

Nothing else moved: the bridge (libpcsx2gs.so is byte-identical to
B3's), the audio path, ARUS, the VU1 AOT codegen, and every synchronous
code path are untouched.

## Correctness risks, and how they are bounded

- **The pipeline is pre-existing, not new** — it carries the port's own
  MTVU design (docs/PROGRESS §50 lineage: timing model, vblank wait,
  SIGNAL-stall replay on the GIF thread, per-job MXCSR/FBRST
  snapshots). It was built and tuned against the built-in GS; B4 is
  its first run against the PCSX2 backend. The interaction surface is
  the GS front end's packet entry (`processGIFPacket` now called from
  the GIF stage thread instead of the EE thread for path 1; the arbiter
  still serializes all paths into it, and B3's harvest already ran the
  backend across two threads — packet producer vs host presenter).
- **VU1 state races**: nil by construction while the switch is on —
  the interpreter state, the VU1 code/data buffers' stream-driven
  writes, and `vif1_regs` are only touched by the single stream
  consumer. The exceptions are pre-existing and unchanged: the EE
  thread can write VU memory through the mapped VU window (write128's
  mapVuMemory path — the guest does not do this to VU1 mid-frame in
  practice, and it behaves the same as it did when the EE also ran the
  stream), and the timing hooks read `m_vu1.state().cycles` from the EE
  thread (a benign relaxed read the design already relied on; the
  worker additionally publishes completed cycles after every job).
- **Guest-visible timing**: the VU now finishes work later in wall
  time than the inline model did. The vblank join and the vu1Busy
  timing model are the compensations; if a title polls VIF1_STAT in a
  way the model mishandles, the symptom would be a stall or a wrong
  busy read, and the A/B switch isolates it in one boot.
- **Backpressure deadlock shape**: enqueue() blocks at 64 jobs while
  the worker is the only consumer; the worker never enqueues (its
  submitGif path is separately capped and drained by the GIF thread),
  and sync() from the EE thread never holds the worker mutex while
  waiting. This is the design's original shape, not a B4 addition.
- **What B4 does not promise**: fields/s in heavy fights may still be
  capped when VU1 work per field alone exceeds the field budget — the
  vblank join turns that into slowdown, not drops. The win B4 buys is
  removing the EE+VU *serialization*: the EE core's ~45% and the VU's
  35-54% now overlap, so the ceiling becomes max(EE, VU+GIF) per field
  instead of their sum. If the owner log shows vu1worker carrying the
  callbacks and fields/s unchanged, the VU alone is the wall and the
  next stage is VU1 AOT codegen (B5 territory), not more threading.

## Log markers for the owner run

Success looks like (in order):

```
native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)
native: path B variant: PS2X_VU1_ASYNC=1 (variant default; ...)
[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)
[pcsx2-gs] Vulkan surfaceless, scale 1 (0x0), surface 640x448, options: fxaa=0;
[vu1] async on: queued=... run=... join_wait=... ms (... waits) queue_hw=... (window ~5 s)   (repeating)
```

And in `[ee-prof]`: **vu1ee collapses toward ~0 ms** while
**vu1worker carries the callback count/time**; in `[ee-work]` the VU
column (worker busy %, jobs) becomes non-zero. queued and run should
track each other within a job or two; a persistent queued >> run gap
with queue_hw pinned at 64 means the VU worker is the bottleneck and
the EE is spending its gain in backpressure (visible in join_wait).

A/B proof, same build: add `PS2X_VU1_ASYNC=0` to the external
godhand.ini, reboot — the glue line reads
`PS2X_VU1_ASYNC=0 (from the external ...)`, no `[vu1]` lines appear,
and vu1ee/vu1worker return to the pathb4 split.

## APK

- APK: `~/workspace/godhand-android/godhand-0.25-m24-pathb5.apk`
  (also at `package/godhand-android.apk`) — **136,696,533 bytes**
  (byte-total identical to pathb4 by zipalign padding quantization;
  contents differ, md5-verified below)
- Zip for delivery: `~/workspace/godhand-android/godhand-0.25-m24-pathb5.zip`
  — **34,836,256 bytes** (`unzip -t` clean)
- Package `dev.ptraaa.godhand`, versionCode **29**, versionName
  **0.25-m24-pathb5**, minSdk 26, targetSdk 35 — installs over pathb4
  in place.
- Signer: the shared ptraaa debug signer, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  (apksigner: v2 + v3 verified, 1 signer).
- Contents (`lib/arm64-v8a/`, md5-matched build tree ↔ APK):
  `libgodhand.so` **98,493,672 B** (variant; md5
  `155df71be5157b86af1d7f2730962c4c`), `libpcsx2gs.so` (md5
  `96a5efdea52b2b8d9b4543d8ca2a0db0` — **byte-identical to B3's**, the
  bridge is untouched), `libshaderc_shared.so` (unchanged). A copy of
  the variant library is kept at
  `~/workspace/godhand-android/libgodhand-0.25-m24-pathb5.so`.

## Verification done here

- Variant build (`build/android-m2a`, `-DGODHAND_PATHB_DEFAULT_PCSX2=ON`):
  exit 0; the touched TUs (ps2_async_vif.cpp, EeScheduler.cpp, the glue)
  compiled clean; the variant libgodhand.so carries PS2X_VU1_ASYNC, the
  variant seed log line and the `[vu1]` format string alongside the B1B
  renderer seed.
- Desktop compile check: ps2_async_vif.cpp and EeScheduler.cpp compiled
  with the desktop tree's own recorded compile commands
  (`build/linux-m1a/compile_commands.json`) — both exit 0. A full
  desktop relink + boot smoke was not run (same position as B1/B3);
  note the desktop default is unchanged anyway (no seed, env unset →
  pipeline off).
- Packaging with `GODHAND_PATHB=1`: exit 0, Path B staging confirmed,
  apksigner v2+v3 verified with the signer above, version 29 /
  0.25-m24-pathb5 confirmed via aapt; APK libraries md5-matched against
  the build trees (above).
- Mainline restoration, verified after packaging: option configured
  back to **OFF** and target `godhand` rebuilt (exit 0). The restored
  `build/android-m2a/src/libgodhand.so` is 98,488,752 bytes with zero
  variant-seed strings (the B4 runtime code — asyncvif accounting,
  [vu1] line, the switch's env name — ships in mainline as well, gated
  off by default there; the variant delta remains only the glue seeds).
  The build tree is left in its normal state.
- Not done here (no Android device on this VM): executing the async
  pipeline against the PCSX2 backend. The owner run below is the proof;
  the markers exist so it convicts or clears the change in one log.

## Owner test steps

1. Install `godhand-0.25-m24-pathb5.apk` over the current build (same
   signer, in-place update). **Do not edit any file** — an existing
   escape-hatch `godhand.ini` in
   `Android/data/dev.ptraaa.godhand/files/` would be honoured verbatim
   (that is its purpose); for the clean B4 verdict it should contain no
   PS2X_GS_RENDERER and no PS2X_VU1_ASYNC lines.
2. Boot and play a minute or two of real gameplay (village + a fight —
   menus already run full speed and barely queue VU work).
3. Send back the full boot log. It should show the marker block above,
   repeating `[vu1] async on:` lines, and the [ee-prof] split flipped
   from vu1ee to vu1worker.
4. A/B (optional, same install): add the single line
   `PS2X_VU1_ASYNC=0` to that external godhand.ini and reboot — that is
   pathb4's exact behaviour; delete the line to return to B4. Add
   `PS2X_GS_RENDERER=native` instead if you ever need the built-in GS.

## Files changed

- `godhand-recomp/runtime/ps2xRuntime/include/runtime/ps2_async_vif.h`
  — `QueueStats` + `queueStats()` declaration.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/ps2_async_vif.cpp` —
  `enabled()` consults PS2X_VU1_ASYNC before PS2X_MTVU; queue
  accounting (queued counter, queue high-water in Worker, join-wait
  RAII timer on sync()/syncVu()/waitPipelineIdle()/enqueue()
  backpressure); `queueStats()`.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/Kernel/EeScheduler.cpp`
  — `snapQueue` in the window state + arm; the `[vu1]` window line
  after the [ee-prof] block.
- `godhand-recomp/android/glue/godhand_android_main.cpp` — the variant
  block's external-ini scan also captures PS2X_VU1_ASYNC and seeds it
  (default 1 in the variant) with the same log-line pattern as the
  renderer; header note.
- `godhand-recomp/android/shell/AndroidManifest.xml` — 29 /
  0.25-m24-pathb5.
- Not changed: the bridge (libpcsx2gs.so byte-identical), the
  synchronous VIF paths, VU0, the VU1 interpreter/AOT, the audio path,
  ARUS, and every mainline default.
- Logs: `m24e-configure.log`, `m24e-build.log`, `m24e-package.log`,
  `m24e-restore-configure.log`, `m24e-restore-build.log`.
