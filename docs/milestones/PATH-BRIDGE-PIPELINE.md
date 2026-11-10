# Path B, stage B8 — "native-in PCSX2": the bridge pipeline

The owner's order, in his words: "Lu native in pcsx2" — keep PCSX2's GS,
but take out the waiting room between the recompiled core and it. This
stage is analysis + implementation only: a patch to the bridge source
plus this report. **No APK is packaged and no performance outcome is
claimed**; the device log of a future build arbitrates, against the
exact lines named at the end.

The patch lives entirely in one file, `path-b/compat/BridgeVK.cpp`
(654 → 1003 lines). It has two parts:

- **Part 0 — base reconstruction.** The tree no longer held b5's bridge
  source: the staged `libpcsx2gs.so` was b8's (md5 `e86c7652…`, with the
  stage-B6 canvas composition and the stage-B7 open-time warm-up). The
  brief's base is the b5 bridge (md5 `96a5efde…`, "byte-identical to
  B3's" per PATH-B5REVIVAL.md). No b5-era source file survives, so the
  base was reconstructed by removing the stage-marked B6/B7 blocks from
  the current file: `CaptureCanvasSize` and the canvas composition in
  `CaptureFrame` (the harvest again serves the raw merged texture, sized
  from the texture itself, as B3–B5 did) and `WarmUpCaptures` plus its
  call in `Open`. The cross-check is the B3/B4/B7 reports' description
  of that bridge (pipelined ring of 3 fence-tracked captures, raw-texture
  serve, ring created lazily at the first vsyncs). The reconstruction is
  behavioural: the resulting binary is **not** byte-identical to b5's
  bridge and is not claimed to be.
- **Part 1 — the pipeline.** A bridge-owned GS thread and a bounded
  item queue inside `libpcsx2gs.so` — PCSX2's own MTGS arrangement,
  which this extraction bypassed by calling the GS directly from the
  core's worker thread. Details per sync point below.

## The wait structure, mapped (baseline: the owner's pathb8 log)

Thread topology today: EE/GIF producer threads → the core's
`GSThreadedBackend` chunk queue (64 KB chunks, at most 64 pending) → one
core GS worker thread → the bridge API, executed synchronously on that
worker → PCSX2's GS + GSDeviceVK. A host present thread latches frames
through the same backend. Every hard sync point between the core and
the GS, with the log line that incriminates it:

**S1 — the per-field double drain on the present path (the whale).**
`GS::latchHostPresentationFrame` (core `gs/gs_frontend.cpp`) runs, per
field: `Flush()` (a no-op), `Sync(GSSyncReason::Presentation)`, then
`Present(request)`. In `GSThreadedBackend`, `Sync` for any non-Finish
reason is `RunOnWorker(...)` = enqueue a call **and drain the whole
queue** (`Drain` waits for every previously published chunk to have
executed); `Present` does the same again (`RunOnWorker`,
`DrainKind::Present`). And the drain buys nothing at its end:
`GSPcsx2Backend::Sync` is an empty override
(`include/runtime/gs/gs_pcsx2_backend.h:52`) — the wait is pure
serialization behind whatever GS execution is queued. Incriminating
lines from the b8 log's light windows (they scale up in fights):

```
[ee-work] fields=287 (57.4/s) ... | parked: sync 282/416.3 ms present 282/318.7 ms consume 0/0.0 ms ...
[ee-work] fields=300 (59.8/s) ... | parked: sync 300/315.0 ms present 300/266.9 ms ...
```

≈1 Sync park and ≈1 Present park **per field**, ~1.1–1.5 ms each even
when the scene is light; in the fight windows of the same log the sync
bucket alone reaches 719.0 ms per ~5 s window (180 parks) while the
guest asks for the least work. `[ee-work]` is printed by
`Kernel/EeScheduler.cpp` from the coupling counters in
`gs/gs_coupling_stats.h`.

**S2 — the coupling queue's publish bound.** Producers block in
`GSThreadedBackend::PublishLocked` when 64 chunks are pending; the
worker's pickup latency is the `[ee-work]` `queueWait` figure (avg
0.5–2.5 ms in b8's fight windows). Not the binding constraint in
fights, but it is the same disease one level up: it fires exactly when
the worker — executing the GS synchronously, S3 — falls behind.

**S3 — the bridge executes the whole GS on the core's worker.**
`BridgeVK`'s `Gif` → `GSgifTransfer` and `Vsync` → `GSvsync`
(`path-b/src/pcsx2/GS/GS.cpp`: `Transfer<3>`; `Flush(VSYNC)` +
`GSRenderer::VSync` merge/compose) plus `CaptureFrame`'s snapshot
recording — all inline on the core worker thread. This is *why* S1's
drains cost milliseconds: every drain target includes full GS
execution of the backlog ahead of it. The host-side symptom is
`[frame-split]`'s `sync`: the present loop's present-sync wait
(`ps2_runtime.cpp`: spin until `currentVSyncTick()` advances, i.e.
until the whole EE→queue→GS chain produces the next field) — 12.7–26.5
ms of every present, 73–88% of frame time, in the b8 log:

```
[frame-split] presents=227 avg 22.2 ms fields +201 | sync 18.17 ms (82%) gs-fetch 2.66 ms (12%) ...
[frame-split] presents=281 avg 17.8 ms fields +275 | sync 14.03 ms (79%) gs-fetch 2.42 ms (14%) ...
```

**S4 — true readbacks (must stay synchronous).**
`GSThreadedBackend::ConsumeLocalToHostBytes` → `RunOnWorker`
(`DrainKind::Consume`) → the backend's `read_fifo` → the bridge's
`GSInitAndReadFIFO` (`GS.cpp`) → renderer flush + `ReadImageX` →
`GSDeviceVK::ExecuteCommandBufferForReadback` (a fence wait). The
guest consumes these bytes, so the wait itself is legitimate; what is
not legitimate is that it is queued behind the core worker's entire
chunk backlog (S2/S3) before the bridge even sees it. The core's async
readback cache (ARUS pillar 3, modes 3/4) already serves the ≤64 px
reads one transfer late; in the b8 log `consume` reads 0/0.0 in light
windows precisely because the cache absorbs them, and rises in fights
where larger reads stay exact.

**S5 — worker-side snapshot reads.** The async cache's refresh jobs
(`drainReadbackRefreshes` in `gs_threaded_backend.cpp`) call the
backend's `ReadTransfer` → the bridge's `ReadLocal` (renderer
`Flush(DOWNLOADFIFO)` + `ReadImageX`), and save states call
`SnapshotVram` → the bridge's `SaveVram` (`Flush(SAVESTATE)` +
`ReadbackTextureCache`), both on the core worker, interleaved with the
draw stream they delay. They park the EE only via `DrainKind::Other`
(snapshots), but they occupy the one worker everything else waits
behind.

**S6 — in-device GPU backpressure.** `GSDeviceVK::ExecuteCommandBuffer`
rotating into a frame resource whose fence has not completed calls
`WaitForCommandBufferCompletion` — an unbounded `vkWaitForFences`.
Real, and correctly placed (it is the GPU's own pacing), but today it
stalls the core's worker and therefore, through S1/S2, the game. Note
the b8 log's signature: `gsIdle 66–87%` *while* everything waits — the
stall is serialization, not GPU saturation.

**S7 — the host present-sync wait itself** (`[frame-split] sync`,
above) is a pacing wait, not a bridge sync point; it is listed because
it is the number the owner feels. It shrinks only if field production
rises.

Out of scope by design, and stated plainly: the B7 forensics' other
binding constraints — `[vu1]` `join_wait` (0.76–2.34 s/window) and
native EE time — are core/VU territory this stage must not touch.
If they bind once the coupling waits fall, fields/s plateaus below
the ceiling and the next stage belongs there.

## What the patch changes, per sync point

All inside `path-b/compat/BridgeVK.cpp`. The direct implementations
keep their bodies verbatim as `*Direct` functions; new dispatcher
entry points (same names, so the API table is unchanged) route them:

- **Bridge GS thread + bounded queue** (`PipeThreadMain`, `PipePost`,
  `PipePostVsync`, `PipeCall`). `Open` starts the thread and runs
  `GSopen` on it as its first fence item; every later GS call executes
  on that thread, in stream order. `Close` posts a close item (last in
  stream order) that runs `GSclose` on the bridge thread, then joins.
- **S3 → removed as a caller-side cost.** `Gif` copies the qwords into
  a queue item (the caller's buffer is only valid for the call — the
  same reason PCSX2's MTGS copies into its ring) and returns; `Vsync`
  copies the privileged registers into its item and returns. GS
  execution now runs concurrently with the EE producing the next
  field.
- **S1 → deflated, not deleted.** The core is untouched, so the
  latch's two drains per field still happen — but the worker they
  drain now only copies and posts into the bridge queue instead of
  executing the GS. A drain's cost collapses from "full GS execution
  of the backlog" to queue-throughput cost.
- **The one producer park that remains: the field-depth bound.**
  `PipePostVsync` parks the producer only while the pipeline already
  holds `PS2X_GS_PIPELINE_DEPTH` uncompleted fields (default **2**,
  range 1–8). Backpressure becomes a depth, not a per-sync drain.
- **S4 → kept synchronous, re-homed.** `ReadFifo` is a fence item:
  stream-ordered on the bridge thread, caller waits for completion.
  The bytes it produces reflect exactly the stream ahead of the
  transfer — no byte can be newer than the transfer's stream point —
  but the wait no longer includes the core worker pre-chewing
  unrelated chunks; those are enqueues now.
- **S5 → fence items too.** `ReadLocal` and `SaveVram` execute in
  stream order on the bridge thread (as do `LoadVram` and `Reset`);
  they no longer sit in the core worker's chunk stream. `Resize`,
  `SetScale` and `pcsx2gs_set_aspect` are fire-and-forget items —
  routing them through the queue also removes a latent b5 behaviour
  worth naming: they used to write GS config from the caller's thread
  while the GS read it on the worker.
- **S6 → unchanged internally, contained.** Device fence waits now
  stall only the bridge thread; producers feel them solely through
  the depth bound.
- **S2 → relieved transitively** (the core worker keeps up when its
  per-chunk cost is a copy + post).
- **Capture state** is now written by the bridge thread
  (`CaptureFrame`) and read by grab callers, so it gained
  `g_captureMutex` (held across `CaptureFrame`'s submit-only GPU
  recording, across every grab's refresh + read + copy, and across
  invalidate/destroy). `g_open` became `std::atomic<bool>` (written
  on the bridge thread under the pipeline, read on caller threads).
- **Evidence:** with the pipeline on, `Open` prints
  `[pcsx2-gs] pipeline on: ... depth %u fields ...`, and the bridge
  thread prints, every 300 executed fields,
  `[pathb] pipeline: fields=%llu ahead_max=%u park=%.1f ms (%llu waits) dropped_captures=%u depth=%u`
  — park is per-window (exchanged out at each print).
- **Kill switches** (project `PS2X_GS_*` conventions):
  `PS2X_GS_PIPELINE=0` — every entry point runs its Direct twin on
  the caller's thread: the reconstructed b5 behaviour in the same
  binary, so a device A/B needs no second build.
  `PS2X_GS_PIPELINE_DEPTH=n` — the field bound, 1–8, default 2.

## Honest risks

- **Input latency.** The pipeline can add up to `depth` fields of
  display latency in the worst case (2 fields ≈ 33 ms at the guest's
  field rate), on top of the B3 harvest's existing ~1 field. The
  bound is only reached while the GS side is the slower side; when
  the EE binds, the queue sits near empty. Depth 1 halves the added
  part at some throughput cost — the knob is the owner's.
- **Readback correctness.** Fence items preserve exact stream order,
  so readback semantics are the pre-pipeline ones. The core's async
  readback cache (untouched) composes unchanged; its refresh jobs now
  land as fence items via `ReadLocal`.
- **Capture drops.** If the bridge thread outruns the GPU by more
  than the 3-slot ring, captures drop (counted in the pipeline line)
  and the served frame ages a step — the same discipline as b5, now
  visible.
- **GIF copy cost.** One `memcpy` per `Gif` call on the producer.
  Fight GIF traffic is tens of MB per window; the copy total is
  expected in the low-ms range per window, against drains that cost
  hundreds. The device log's `callExec`/`queueWait` movement will
  show it if wrong.
- **Capture mutex contention.** A device rotation wait (S6) inside
  `CaptureFrame` now also delays a concurrent grab by that wait; the
  grab then serves the previous frame. Chosen deliberately over
  lock-free staging: the serve cache's size-then-copy call pair must
  see one consistent frame.
- **What this does not fix.** VU `join_wait`, native EE time, and
  the thermal question from the B7 forensics are untouched. If the
  coupling waits fall and fields/s still plateaus in the low 40s,
  that is those constraints binding, not this patch failing — the
  lines below separate the two.

## Verification done here

- **Build:** `ninja pcsx2gs` in the existing `path-b/build-b1` tree
  (established configure, not reconfigured): `BridgeVK.cpp`
  recompiled, `libpcsx2gs.so` linked, clean.
- **Product:** `path-b/build-b1/libpcsx2gs.so` — 28,786,128 B, md5
  `9e468a9082b5e51fdfaf435c9dad909a`. (Baselines: b5's bridge
  `96a5efde…`; the tree's previous staged bridge, b8's, `e86c7652…` at
  28,106,992 B. The new binary is neither, by construction.)
- **Exports:** `pcsx2gs_get_api`, `pcsx2gs_grab_frame`,
  `pcsx2gs_grab_frame2`, `pcsx2gs_set_aspect` all present
  (`llvm-nm -D`); the API table and its version are unchanged.
- **Strings:** `PS2X_GS_PIPELINE` present (both switches + the open
  line), `[pathb] pipeline:` present; **zero** warm-up / `PS2X_GS_WARMUP`
  strings — the reconstruction check.
- **Scope:** exactly one source file modified
  (`path-b/compat/BridgeVK.cpp`; of its 649 diff lines vs the b8
  source: the Part-0 reconstruction, the `*Direct` renames, the
  capture mutex, the pipeline + dispatchers) plus this report, and
  build outputs under `path-b/build-b1`. Nothing under
  `godhand-recomp/`, `path-b/src/`, or the rest of `path-b/compat/`
  was edited.
- **VU/core proof:** `diff -r godhand-recomp/generated/vu1
  b5-backup/vu1-gen-orig` — **identical**, file for file. The core was
  not rebuilt, so the b5-revival's `libgodhand.so`
  (`155df71be5157b86af1d7f2730962c4c`) remains the core any future
  package pairs this bridge with.

## The lines that arbitrate a future device run

Same protocol shape as B7 (cool phone, the pathb5 route, wide off):

- Boot: `[pcsx2-gs] pipeline on: the GS runs on the bridge thread,
  depth 2 fields ...` — its absence convicts the packaging (wrong
  .so) or the env (`PS2X_GS_PIPELINE=0` seeded from the ini).
- `[pathb] pipeline:` lines: `ahead_max` must never exceed the depth;
  `park` per ~300 fields is the pipeline's own producer park — the
  honest successor of the old drain totals, and it should read as a
  small fraction of them.
- `[ee-work]` `parked: sync ... present ...` per fight window,
  against b8's ≈416 + ≈319 ms (light) and up-to-719 ms sync (fights):
  this is the conviction metric for S1. If these do not fall, the
  pipeline is not landing, whatever fields/s does.
- `[frame-split]` `sync` ms/% and `fields +N` per window: the felt
  number; fight fields/s against the 44–52 pathb5 floor and b8's
  31.7–47.
- `[pathb] harvest: ... readback_wait=...`: must stay in its
  historical range (b8: 50–105 ms per 5 s window, i.e. poll + copy
  only); a jump indicts the capture mutex, not the queue.
- First-window `queueWait ... max`: the Part-0 reconstruction removes
  the stage-B7 warm-up, so the one-off boot spike returns to its b5
  form (~0.5 s, lazy capture-ring creation at the first vsyncs). The
  pipeline does not address it and it is not a fight metric; if a
  future stage wants it gone, the warm-up is a known, separable
  cherry-pick from the b8 bridge — deliberately not smuggled in here,
  so this stage's verdict stays attributable to the pipeline alone.
- **Attribution A/B:** the same build with `PS2X_GS_PIPELINE=0` in
  the external `godhand.ini` separates the pipeline (Part 1) from
  the reconstruction (Part 0) — the reconstruction is itself a delta
  against the bridge the owner last tested (b8's), so a verdict
  without the A/B cannot say which part earned it.

For the future packaging stage (not this one): the deliverable would
pair this `libpcsx2gs.so` with the b5-revival's remaining payload
(`libgodhand.so`, `libshaderc_shared.so`, `classes.dex` — all
byte-identical to b5 per PATH-B5REVIVAL.md) at a versionCode past 34;
the revival report's owner-ini caveat (an external `godhand.ini`
renderer line overrides the variant seed) applies unchanged, and the
boot log's `[pcsx2-gs] pipeline on` line is the packaging proof.
