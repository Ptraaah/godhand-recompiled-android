# VU-join stage — attacking the [vu1] join_wait wall

The m27 pipeline build (0.28-m27-pipeline, PATH-PIPELINE-BUILD.md) is
owner-approved as "lebih nyaman". Its device log
(M27-LOG-ANALYSIS.md) proved the GS coupling waits collapsed and
named the remaining crowd-scene wall precisely: the EE's joins on the
async VU1 pipeline — `[vu1] join_wait` 35–45 ms per 5 s window in
light scenes, **507–1,694 ms in crowds** (worst window 1,694.3 ms /
179 waits, per-wait 0.37 ms → ~9.5 ms), `queue_hw=2` throughout,
`queued≈run`, VU busy 32–55%, GIF peaking at 7,361 jobs / ~390 MB
per window, GS exonerated (gsIdle 89–97%).

This stage is analysis + implementation + this report. **No APK is
packaged and no performance outcome is claimed**; a future device log
arbitrates against the exact lines named at the end.

## 1. The VU1 join mechanism, mapped (godhand-recomp core sources)

All of it lives in the ps2xRuntime: submission and accounting in
`runtime/ps2xRuntime/src/lib/ps2_memory.cpp` and
`src/lib/ps2_async_vif.cpp`, the VIF1 interpreter in
`src/lib/ps2_vif1_interpreter.cpp`, the scheduler's part in
`src/lib/Kernel/EeScheduler.cpp`.

### Submission — what a "job" is

- Guest DMA starts (DMAC CHCR writes) queue `PendingTransfer`s; the
  channel handler then calls `PS2Memory::processPendingTransfers()`
  immediately for most channels (also pumped from
  `ps2_runtime.cpp`'s GIF-DMA path). That function folds **all**
  pending PATH3-GIF and VIF1 transfers into a single
  `asyncvif::enqueue()` — **one job = one processPendingTransfers
  batch**, not one MSCAL. Chain data is *copied* into the job's items
  at this point (or the chain buffer is moved), so after submission
  the guest's RDRAM source buffers are runtime-side safe to reuse.
  A direct 128-bit write to VIF1_FIFO enqueues its own 16-byte job.
- The queue is a plain FIFO `std::deque<Job>` capped at
  **64 jobs** (`kMaxQueuedJobs`); `enqueue()` parks the EE only at
  that cap (charged as backpressure). Jobs carry the EE's mxcsr and
  the FBRST snapshot taken at enqueue time.

### Execution — one worker, one GIF stage

- A single **VUThread** (priority Highest) pops jobs FIFO and runs
  each item: `processVIF1Data` (VIF1 decode + VU1 microprogram runs —
  in the m27 crowd windows ~350 AOT-compiled runs per job, ~640k VU
  cycles per job, `interp 0 comp 113133 fb 0`, so per-job cost is
  honest geometry volume, not interpreter overhead) or PATH3 GIF
  submission; then it drains the GIF arbiter and publishes the job's
  accumulated XGKICK output to the **GIFThread** in 256 KB chunks
  (publish blocks the worker if 8 MB is queued — in m27 the GIF stage
  sat at ~6% busy, so this never bound). VU busy in crowd windows was
  32–55%: **the worker is not throughput-saturated**.

### The joins — every way the EE stops

All joins are timed by one `JoinTimer` in ps2_async_vif.cpp and lumped
into the `[vu1]` line's `join_wait`. Before this stage the log could
not attribute them; the sites are:

1. **`asyncvif::sync()` — the full drain.** Waits for the VU job
   queue empty *and* the worker not busy, *then* for the GIF stage
   chunks empty *and* not busy. Callers, all in ps2_memory.cpp:
   - **any read** in the async-VIF register space
     (`isAsyncVifRegister`: GIF regs 0x10003000–0x10003100, VIF1 regs
     0x10003C00–0x10004000, 0x10005000–0x10007000) in
     `readIORegister` — the status-poll path (below);
   - **any write** in the same space in `writeIORegister`;
   - **any EE map of the VU1 code/data window** (`mapVuMemory`) —
     a true data dependency;
   - GS privileged-register writes other than CSR/IMR/SIGLBLID
     (`gsPrivWriteNeedsSync`) and GS-priv reads only under
     `PS2X_GS_READ_SYNC=1` (off by default — not a factor);
   - the two VRAM-download setups (VIF1_STAT FDR write; VIF1 DMA
     CHCR with DIR=0) — rare and load-bearing;
   - save/load state (ps2_save_state.cpp).
2. **`syncVu()`** (VU queue only), called from `vu1Busy()` when the
   VU1 timing model is disabled, blocking, or unprimed. With the
   shipped defaults (timing scale 1.0, primed, non-blocking)
   `vu1Busy()` instead prices the wait in guest cycles via
   `s_vu1Completed` — so **VIF1 CHCR polls at 0x10009000 are already
   model-priced and are not join_wait**. The register-space syncs
   above are the un-priced remainder.
3. **`waitPipelineIdle()`** — the EE scheduler's MTVU vblank barrier
   (when vblank is due and the pipeline is busy: 250 µs slices until
   VU+GIF fully idle, 100 ms cap) plus ≤1 ms pacing waits.
4. **enqueue backpressure** at 64 queued jobs — never observed.

### What queue_hw=2 means, exactly

`queueHighWater` is the deepest `jobs.size()` seen *at push time*,
all-time, excluding the job currently executing. **2 means the system
never in its life held more than 1 running + 2 queued jobs** — with a
cap of 64. Nothing configures the depth to 2; it is the *fingerprint*
of the joins: the EE drains everything so often that a third batch
can never accumulate. "Raise the queue depth" is therefore a no-op
against this evidence — the queue is not the constraint; the drains
are what keep it empty.

### Why per-wait cost grows 0.37 ms → ~9.5 ms in crowds

A join returns only when the in-flight batch(es) finish. Per-job
wall time is ~0.1–0.4 ms in light scenes and **~7–8 ms in crowds**
(VU busy 2,500 ms / 321 jobs in the sampled crowd window): crowd
batches carry more VIF1 stream per flush (~350 microprogram runs,
~640k VU cycles, and the GIF stream the batch produces peaks at
~390 MB per window). The wait count barely changes (~0.9 joins per
field in both regimes; ~190 waits against ~340 jobs per crowd
window) — the *price per join* is one in-flight crowd job. A
secondary, unproven contributor is memory-system contention between
the Highest-priority VUThread streaming job payloads and the EE
thread; the log cannot convict it and this stage does not treat it.

### The diagnosis in one sentence

The system is **latency-bound, not throughput-bound**: with the VU
only 32–55% busy, the EE still pays 0.5–1.7 s per window parked,
because its first status poll after each submission drains the whole
pipeline and answers "idle" — the guest code pattern that exists on
hardware to create overlap ("poll → busy → go do other work → poll
again") can never see "busy", so EE and VU take turns instead of
overlapping. The guest's canonical per-frame synchronisation is
visible in the runtime's own stubs: `sceGsSyncPath` mode 0
(`src/lib/Kernel/Stubs/GS.cpp`) polls VIF1 CHCR, GIF CHCR,
**VIF1_STAT (0x10003C00)** and **GIF_STAT (0x10003020)** — the last
two are exactly the reads that full-drained on every poll.

## 2. The chosen lever — live register reads (`PS2X_VU1_LIVE_REGS`)

**Reads in the async-VIF register space no longer drain the
pipeline.** They compose their values from exactly the state they
used before — the drain never fed the value:

- GIF_STAT is composed in `readIORegister` from `m_ioRegisters` plus
  `m_path3Masked`. `m_ioRegisters` is written **only** from EE-thread
  code (verified: no write to it exists in the VIF1 interpreter, the
  async worker, or the GS frontend); `m_path3Masked` is a bool the
  worker updates for MSKPATH3 — a live read of it is what hardware
  presents.
- A plain VIF1_STAT read returns the `m_ioRegisters` value (the
  worker's `vif1_regs` are consulted only by the download branch,
  which is unchanged); the CHCR busy bits are EE-side state kept
  honest in guest time by the VU1 timing model's `vu1Busy()`.
- So for reads, the drain was a pure ordering side effect, not a
  data prerequisite. Removing it makes the register behave as the
  hardware's does: it reports live status, and a guest spin/poll
  loop itself becomes the synchronisation — in guest time, while
  the VU worker keeps running.

What is **not** relaxed, deliberately:

- **Writes** in the register space keep the full drain: they mutate
  `vif1_regs` fields (mark/cycle/mode/num/mask/code/tops/base/…)
  that an in-flight job's interpreter is actively reading. A live
  write could corrupt a running batch; the hardware queues such
  writes behind VIF idleness for the same reason.
- **VIF1-block reads during a VRAM download stay drained.** While
  FDR is set or download data may be pending, a VIF1_STAT read whose
  FIFO momentarily reads empty must mean "the GS really finished
  producing", not a live guess — that semantics is what the FDR and
  DMA-start drains exist for (the save/load-dialog hang in the
  file's history). GIF-block reads stay live even then.
- **VU-memory maps, GS-priv accesses, download setups, save
  states, the vblank barrier** — untouched.

Kill switch: **`PS2X_VU1_LIVE_REGS=0`** restores the draining reads
exactly (codebase convention: unset or any non-'0' value = on). The
A/B is one ini/env flip in the same future build.

### Attribution instrumentation (part of the same change)

The B4 lump made this stage's diagnosis take a full log forensics
pass; the next one should not. Every `JoinTimer` now takes a
`JoinSite`, `QueueStats` carries per-site ns/count arrays, call sites
pass their site (`RegRead`, `RegWrite`, `VuMem`, `GsPriv`,
`Download`, `VuPoll`, `Idle`, `Backpressure`, `Other`), and the EE
scheduler prints a second line when any site waited in the window:

```
[vu1] joins: regread=%.1f ms (n) regwrite=… vumem=… gspriv=… download=… vupoll=… idle=… backpressure=… other=…
```

The existing `[vu1]` line is byte-identical. Counters only — the
B4/M14 discipline: nothing feeds a scheduling or rendering decision.

### Files touched (diff scope)

- `runtime/ps2xRuntime/include/runtime/ps2_async_vif.h` — `JoinSite`
  enum, per-site `QueueStats` arrays, `sync()` gains a site
  parameter (default `Other`, so ps2_save_state.cpp is untouched).
- `runtime/ps2xRuntime/src/lib/ps2_async_vif.cpp` — per-site atomic
  counters, `JoinTimer` site plumbing, site tags at `syncVu`
  (VuPoll), `waitPipelineIdle` (Idle), enqueue backpressure
  (Backpressure), `queueStats()` fill.
- `runtime/ps2xRuntime/src/lib/ps2_memory.cpp` — `vu1LiveRegsEnabled()`
  gate; the `readIORegister` register-space sync becomes conditional
  (live unless kill switch off or download-armed VIF1 block); site
  tags at all 11 sync call sites.
- `runtime/ps2xRuntime/src/lib/Kernel/EeScheduler.cpp` — the
  `[vu1] joins:` print (window deltas off the existing snapshot).

Nothing under `path-b/` (the approved pipelined bridge is untouched;
its kill switches `PS2X_GS_PIPELINE` / `PS2X_GS_PIPELINE_DEPTH` are
unaffected), nothing in the generated trees, no build files.

## 3. Honest risks

- **Ordering dependencies the drain used to hide.** The drain made
  "guest observed idle" imply "all prior VU/GIF work completed".
  With live reads, guest code can observe live-busy, spin in guest
  time, and proceed the moment status clears — the hardware
  contract. The paths where the guest *consumes* VU results keep
  their hard syncs: VU-memory reads/writes (mapVuMemory drain),
  VIF1 register writes (drain), downloads (drains). The residual
  exposure is a guest path that consumes VU output *without* any of
  those touches — e.g. trusting a status bit whose producer is the
  worker while the value lives in EE-side state. From the code, the
  values in this space are EE-side by construction (above), but a
  game-specific dependency invisible from the runtime sources
  cannot be excluded; the `regread` counter bucket and the kill
  switch are the containment: one log shows whether joins merely
  moved, and one ini flip restores the old world.
- **Spin behaviour.** A guest poll loop that today exits after one
  draining read will now spin in guest cycles until the worker
  finishes. That is the intended trade (guest-time waiting overlaps
  the VU), and the scheduler's pacing + the vblank barrier bound
  it; but a pathological tight loop on a bit the worker sets only
  via a path that itself needs EE progress would livelock where the
  drain used to force progress. No such bit was found in this space
  (the SIGNAL/FINISH handshake lives in the GS-priv space, whose
  rules are unchanged, and its release path is EE-driven via CSR
  writes that do not pass through these reads).
- **The wall behind this one.** Native EE time (guest 76–89% in
  crowd windows, ~14 ms of EE execution per crowd field) is
  untouched and becomes the binding constraint if the joins fall —
  the analysis said so, and the success signature below is written
  to show the plateau moving, not to promise 60 in crowds.
- **The vblank barrier may become visible.** Today the game's own
  syncs usually drain the pipeline before vblank, so the barrier
  rarely waits. With live reads the EE can reach vblank with VU
  work outstanding; the barrier (full idle, 250 µs slices) then
  charges the `idle` bucket. If a future log shows `idle` replacing
  `regread` millisecond-for-millisecond with no fields/s gain, the
  barrier — not the register reads — is the next stage's subject,
  and relaxing *it* is a timing-model decision, not a register one.

## 4. Verification done here

- **Touched translation units compile clean** in the real build
  tree (`build/android-m2a`, RelWithDebInfo flags, NDK clang,
  aarch64): `ps2_async_vif.cpp.o`, `ps2_memory.cpp.o`,
  `Kernel/EeScheduler.cpp.o` — no errors, no warnings-as-errors.
- **Full build links:** `ninja godhand` ran to completion,
  exit code 0, 13,207 steps, ending in
  `Linking CXX shared library src/libgodhand.so`. (The header sits
  in the generated code's common include chain, so the lever
  forced a near-full-tree rebuild — the strongest link check
  available here.) Built artifact, reference only (nothing was
  packaged): `build/android-m2a/src/libgodhand.so`,
  99,713,624 bytes, md5 `82fc27c5437d15144387b0cd0a384699`
  (pre-change mainline artifact was 99,711,576 bytes; +2,048
  bytes of attribution strings/code).
- **VU generated tree byte-identical, post-change:**
  `diff -r godhand-recomp/generated/vu1 b5-backup/vu1-gen-orig`
  reports no differences — the same result as the pre-change
  baseline taken before any edit.
- **Bridge untouched:** nothing under `path-b/` was modified;
  `path-b/build-b1/libpcsx2gs.so` md5 remains
  `9e468a9082b5e51fdfaf435c9dad909a`. Its kill switches
  (`PS2X_GS_PIPELINE=0`, `PS2X_GS_PIPELINE_DEPTH`) are unaffected
  by construction.
- **Kill switch present in the built core:** the string
  `PS2X_VU1_LIVE_REGS` and the `[vu1] joins: regread=...` format
  string are both found in the linked `libgodhand.so`.
- **Scope discipline:** pre-change md5s of the four edited files
  were snapshotted before editing; only those four files differ.
  `ps2_save_state.cpp` deliberately keeps the default `Other`
  attribution (save states are never in a frame window). A final
  grep sweep confirms no other code consumes `QueueStats` or
  `JoinTimer` outside the edited files.
- **Not verified here (by design):** any runtime behaviour. This
  VM cannot run the game; the device log arbitrates per §5.

## 5. The lines that arbitrate a future device run

Same protocol as m27 (cool phone, the pathb5 route, wide off,
pipeline on at depth 2). Baseline: M27-LOG-ANALYSIS.md.

- **Boot/config proof:** the lever is a core change, so the future
  build's provenance must pair this core with the *unchanged*
  pipelined bridge (`libpcsx2gs.so` md5 `9e468a9082b5e51fdfaf435c9dad909a`);
  `[pcsx2-gs] pipeline on: ... depth 2 fields` must still print.
- **`[vu1] join_wait` (the conviction metric):** crowd windows fall
  from 507–1,694 ms toward the light-window scale — **target
  < ~150 ms per 5 s window**. Light windows stay at 35–45 ms or
  below.
- **`[vu1] joins:` (the new attribution line):** with the lever on,
  `regread` ≈ 0 outside download moments. Where the remaining
  join_wait sits is the verdict's second half: `idle` growth means
  the vblank barrier inherited the wait; `regwrite`/`vumem` growth
  means the game's true dependencies live there. Any of those is a
  *successful measurement* even if fields/s disappoints — it names
  the next lever instead of guessing.
- **Fields/s:** crowd windows rise from 39–49 toward **55–60**;
  `[ee-work]` guest% rises as parked time converts to execution;
  `[frame-split]` presents avg falls toward ~17 ms and the sync
  share drops back under ~90%.
- **`[pathb] pipeline: ahead_max` touches 2 in crowd windows** —
  the pipeline finally being fed faster than it drains is the proof
  the producer side was unblocked. `park` stays a small fraction of
  the old drain totals; `dropped_captures` stays 0; harvest
  `readback_wait` stays in its 67–106 ms family.
- **Audio:** no separate metric exists; choppiness should track the
  crowd fields/s rise (SPU2 is slaved to guest timing).
- **Attribution A/B:** the same build with `PS2X_VU1_LIVE_REGS=0`
  in the external `godhand.ini` restores draining reads; comparing
  the two logs separates this lever from everything else in the
  build. `PS2X_GS_PIPELINE=0` remains available to re-isolate the
  bridge stage.
