# Vblank-barrier stage — bounding the last full drain

The live-register-reads stage (PATH-VUJOIN.md, shipped in
0.30-m29-vujoin2) fired exactly as designed: its device log
(M29-LOG-ANALYSIS.md) shows `regread` at 0.0 ms in every window and
light-scene `join_wait` at ~6 ms/window. But crowd throughput did
not move (heavy windows 31–51 fields/s, owner verdict: still not
playable, audio still choppy), because the freed wait migrated
~1:1 into the `idle` join site — the EE scheduler's vblank
barrier. Heavy 5 s windows: `idle` 664–1,589 ms of 671–1,590 ms
total join_wait, thousands of ~0.3 ms micro-waits. That migration
is the failure signature PATH-VUJOIN.md §3 named in advance, and
attributing it took one log line instead of a forensics pass.

This stage is analysis + implementation + this report. **No APK
is packaged and no performance outcome is claimed**; a future
device log arbitrates against the exact lines named at the end.

## 1. The barrier, mapped

`waitPipelineIdle()` (`ps2_async_vif.cpp`) is the full drain: VU
job queue empty *and* worker not busy, *then* GIF-stage chunks
empty *and* stage not busy, up to a caller-supplied timeout. It
has exactly two call sites, both charged to `JoinSite::Idle`:

1. **The vblank barrier** — `EeScheduler::processDueDeadlines()`.
   When a VBlankStart deadline is due in guest cycles, the MTVU
   timing model is on (`PS2X_MTVU_VBLANK_WAIT`, default on) and
   the async pipeline is active, the scheduler unlocks and loops
   `while (pipelineBusy())`: a pending GS interrupt or handler
   invocation preempts the wait (the handler kicks the next part
   of the frame — it must not miss the swap), a 100 ms cap logs
   and lets the vblank go ahead, and otherwise it parks in
   `waitPipelineIdle(250 µs)` slices. Only when the pipeline is
   *fully* idle does it fall through to dispatch the vblank
   (re-anchor pacing, then `processEvent(VBlankStart)`:
   `vsyncTick++`, `gs().vblank()`, INTC VBON, handlers).
   The ~0.3 ms micro-waits in the m29 log are these slices:
   ~16 slices per heavy field ≈ the pipeline's per-field tail.
2. **Host catch-up pacing** — `EeScheduler::hostCatchUp()`. When
   the EE's emulated cycle count runs ahead of wall-clock time
   while the pipeline is busy, it parks once for
   `min(ahead, 1 ms)`. In crowd windows the EE runs *behind*
   (guest 89%), so this site is a minor contributor there; it
   matters in light scenes, where it is also already time-capped.

### Why the barrier drains at all

It is a **pacing device for the timing model**, not a data
dependency. On hardware, one field of guest cycles takes one
field of wall time, so VU1/GIF keep pace with the EE naturally
and vblank needs no relationship to their progress. In this
runtime the EE can burn a field of guest cycles in far less wall
time than the workers need to execute that field's jobs; without
a coupling point the guest would observe vblank while the
previous frame's work is arbitrarily far from done. The code
carries the scar tissue of the unbounded version (hostCatchUp's
comment): the EE, spinning on the frame-done flag the GS handler
sets, burned a whole field of cycles before the render finished,
the game saw its frame take a field and missed the next vblank —
frames landing at +0.01 and +0.97 field, half-rate pacing in the
4-fighter select. The full drain is the strongest possible
coupling: at every vblank delivery, *all* submitted work —
including the field that just ended — is complete, worker and
GIF stage empty.

### What that correctness actually consists of

Dissected against the code, the drain buys three things:

1. **Frame-done ordering vs vblank.** The guest's frame
   completion arrives as GS SIGNAL/FINISH interrupts, raised by
   the GS frontend as the GIF stage executes those packets, and
   dispatched on the EE thread (`dispatchGsInterrupt`). With a
   full drain, frame N's FINISH is necessarily delivered before
   vblank N+1, so the guest's swap logic always sees a completed
   frame at the boundary.
2. **A bound on EE↔pipeline decoupling.** Guest time can never
   run ahead of *completed* work at all: lag ≡ 0 at every field
   boundary. Host pacing and the delivery re-anchor (late
   vblanks re-anchor within 2 ms) assume this coupling.
3. **Nothing else.** No guest-visible *value* is produced by the
   drain: register reads compose EE-side state (PATH-VUJOIN §2),
   VU-result consumption drains at `mapVuMemory`, VRAM downloads
   drain at their setup sites, GS-priv ordering drains at its
   site, register writes drain before mutating `vif1_regs`. The
   barrier protects *timing relationships*, never data.

And the price is the serialization itself: in a crowd the VU
worker needs ~7–8 ms wall per heavy batch while the EE produces
a field in ~16 ms of guest cycles executed in ~2–4 ms of wall
time — so the EE arrives at the barrier with the tail of the
field's batches still running and pays their full remainder,
every field. The pipeline is latency-bound at the barrier for
the same reason it was at the register polls: a hardware
non-event (vblank) was made into a join.

## 2. The candidates

**(a) Bounded staleness of one field.** The barrier waits only
for jobs submitted *before the previous field boundary*; the
field that just ended may still be in flight across the vblank.
Decoupling becomes bounded by construction (completion lags
submission by at most the submissions of one field, plus the
in-flight remainder of the previous one) while vblank regains
its hardware semantics: an event, not a join. The frame-done
interrupt for an overrunning frame can land after the vblank —
which is what hardware does whenever a frame overruns, and what
guest sync code is written for (it waits on its own flags from
both ISRs; the intact `vumem`/`download`/`gspriv`/`regwrite`
drains still force completion wherever the guest *consumes*
results rather than merely pacing).

**(b) Age-based skip.** Skip the drain when outstanding work is
younger than the current field. Rejected: it approximates (a)
with a clock instead of a boundary, and the approximation fails
both ways. A crowd job (~7–8 ms) submitted just after a boundary
is "young" for most of the field, so the barrier skips while the
pipeline falls behind by nearly a field of *old* work anyway
once a second batch queues behind it — the age of the oldest
unfinished item, the only honest reading, crosses any threshold
exactly in the heavy steady state and the full drain returns
with a cliff. It also needs per-job wall timestamps and a
definition of "field age" in a runtime whose guest time and
wall time deliberately decouple. (a)'s boundary is exact, uses
counters that already exist, and degrades to the same guarantee
at every load.

**(c) No-op barrier.** Rejected: it discards the one invariant
the barrier legitimately owns (§1.2). Decoupling becomes
unbounded — bounded only by the 64-job enqueue cap and the GIF
stage's 8 MB publish cap, both of which then become de-facto
barriers with far worse jitter (a park *inside* submission,
mid-field, charged to backpressure). This re-opens the exact
+0.01/+0.97 field-slip failure the barrier was built against.
Note the codebase does ship a supported no-wait configuration —
`PS2X_MTVU_VBLANK_WAIT=0` disables the *timing model* wholesale
(host pacing of `vu1Busy`, catch-up, and the barrier together);
that is a different execution model, not evidence that removing
the barrier inside the timing model is safe.

**Chosen: (a)**, applied to the vblank call site only.
`hostCatchUp` keeps calling the full-drain `waitPipelineIdle`:
its waits are already capped at 1 ms, it fires when the EE is
*ahead* of wall clock (rare in crowds), and its post-change
residue stays visible in the `idle` bucket — which is precisely
how the next log separates "pacing waits" from "barrier waits".

## 3. The implementation

Kill switch: **`PS2X_VU1_VBLANK_BOUND`** — unset or any non-'0'
value = bounded (default on, codebase convention); `=0`
restores the m29 barrier exactly (the scheduler takes the
historical `pipelineBusy()` + `waitPipelineIdle()` loop,
verbatim). `PS2X_VU1_LIVE_REGS` behaviour is untouched; setting
it to 0 still restores draining reads independently.

Sequence plumbing (all in `ps2_async_vif.cpp`; bookkeeping only,
the B4 discipline — the only consumer is the barrier predicate):

- `enqueue()` stamps each `Job` with `seq` = the existing
  `s_queuedJobs` total + 1, assigned under the worker mutex so
  seq order is exactly queue order. `queueStats().queuedJobs`
  keeps its meaning.
- The worker records `publishingSeq` when it pops a job and
  `completedSeq` when the job is fully executed *and* its GIF
  output flushed (set in the same lock block that clears
  `busy`, after the job-end `publishGif`). Jobs complete FIFO,
  so one watermark describes worker progress.
- `publishGif` stamps each flushed `GifChunk` with the running
  job's seq. A chunk never spans jobs: `stage.current` is
  flushed at every job end, and streaming flushes belong to the
  same running job. The GIF stage records `currentSeq` for the
  chunk in flight; queued chunks are FIFO in job order, so the
  stage's progress toward a watermark is decided by its oldest
  outstanding chunk (deque front) and the in-flight one — no
  per-chunk completion counter is needed, and jobs that produced
  no chunks cannot wedge the predicate (they leave no chunk;
  the worker's `completedSeq` already covers their execution,
  and the stage predicate only inspects chunks that exist).

The bound itself:

- EE-thread-only statics in asyncvif: the last vsync tick seen,
  the enqueue total sampled one boundary ago, and the enforced
  watermark. `barrierPending()` samples on entry: the scheduler
  reaches the barrier *before* `processEvent` increments
  `gs().vsyncTick` for the vblank being delivered, so when the
  tick has advanced since the previous barrier, the sample from
  one boundary ago is exactly "jobs submitted before the
  previous field boundary" — it becomes the watermark, and the
  current total (everything submitted through the field that
  just ended) is sampled for the next boundary. The deferred
  path (interrupt preempts, deadlines re-arm, barrier re-enters
  with the tick unchanged) cannot double-shift; a skipped
  barrier (pipeline idle) leaves a stale-but-moot watermark
  because an idle pipeline has already completed everything.
- `barrierPending()` = worker `completedSeq` < watermark, or a
  GIF chunk with `jobSeq` ≤ watermark queued or in flight.
- `waitPipelineBounded(timeout)` mirrors `waitPipelineIdle`'s
  two-stage CV wait against those predicates and charges its
  time to the new `JoinSite::Barrier`, so the `[vu1] joins:`
  line now ends `… backpressure=… barrier=%.1f ms (n) other=…`.
  The `[vu1]` line itself is byte-identical; `idle` keeps
  meaning waitPipelineIdle (pacing + kill-switch-off barrier).

Scheduler diff (`EeScheduler.cpp`): in the vblank wait block,
one `bounded` bool selects `barrierPending()` /
`waitPipelineBounded(250 µs)` vs the historical calls. The
interrupt preemption, the 100 ms cap and its log, the deferral
and re-arm — all unchanged, in both modes. The delivery-side
2 ms re-anchor is untouched. Nothing under `path-b/`; the
pipelined bridge binary is byte-identical (md5
`9e468a9082b5e51fdfaf435c9dad909a` before and after).

Files touched (diff scope):

- `runtime/ps2xRuntime/include/runtime/ps2_async_vif.h` —
  `JoinSite::Barrier` (appended before `Count`; existing site
  indices unchanged), declarations + contract comments.
- `runtime/ps2xRuntime/src/lib/ps2_async_vif.cpp` — seq fields
  and stamping (`Job`, `Worker`, `GifChunk`, `GifStage`),
  `publishGif` moved below `Worker` (it now reads the running
  job's seq), the bound sampler + `barrierPending()` +
  `waitPipelineBounded()` + `vblankBarrierBoundEnabled()`.
- `runtime/ps2xRuntime/src/lib/Kernel/EeScheduler.cpp` — the
  barrier-loop branch and the `barrier=` attribution field.
- `ps2_memory.cpp` untouched (pre/post md5
  `40357f9489521341700621e9db4df0af` in both snapshots).

## 4. Correctness — what could break, and what covers it

Named guest behaviours, one by one:

- **Swap-on-vblank-only pacing** (a game that presents at the
  vblank ISR without waiting for its GS flags): the frame whose
  packets were submitted in the field just ended may still be
  executing on the GIF stage when `gs().vblank()` delivers the
  field. Ordering is never violated — worker, GIF stage and the
  bridge queue are all FIFO, so field N's packets reach the GS
  before field N+1's; only *timing* shifts, by less than one
  field. With the pipelined bridge (the shipping configuration)
  presentation is already queued two fields deep bridge-side;
  the frontend consumes a FIFO stream either way. A game that
  reuses a draw buffer the instant vblank fires, without any
  sync, could now have the GS still reading the previous field's
  tail from it — on hardware the same game races the GS the
  same way whenever a frame overruns, and God Hand's own sync
  (below) does not have this shape.
- **The frame-done handshake** (`sceGsSyncPath` and the game's
  SIGNAL/FINISH semaphores): FINISH for frame N can now be
  dispatched after vblank N+1's INTC. Both are independent
  interrupt sources whose hardware order is likewise unguaranteed
  under overrun; the guest's wait is on flags from both, and the
  barrier loop's interrupt preemption (kept, both modes) still
  runs a mid-wait GS handler before the vblank when one arrives.
  `sceGsSyncPath`'s polls are the live reads of the previous
  stage — they report honest busy and spin in guest time priced
  by the VU1 timing model; nothing about the bound changes
  their answers, only how much work remains when they ask.
- **Reading VU1 results right after vblank** (e.g. a thread
  woken by the vblank ISR that reads VU memory or kicks a
  download): `mapVuMemory` still full-drains (`vumem`), the
  download setups still full-drain (`download`), GS-priv still
  drains (`gspriv`), and any VIF1 register write still drains
  before mutating in-flight state (`regwrite`). The bound
  exempts only work nobody has yet demanded the result of.
- **Runaway decoupling**: impossible by construction. At every
  delivered vblank, all work submitted before the previous
  boundary is complete; outstanding work is at most one field's
  submissions plus the in-flight remainder of the field before
  it. The 64-job cap / 8 MB GIF cap are never approached
  differently than today (crowd GIF volume is ~1.3 MB per field;
  two fields of stage lag ≈ 2.6 MB < 8 MB).
- **Save states / load**: `sync()` callers are untouched;
  timing-state save/load does not carry the sampler (EE-thread
  statics re-derive from the live tick within one field; the
  tick is not restored from state, so no discontinuity arises).

## 5. Honest risks

- **The +0.01/+0.97 slip, in miniature.** The barrier was built
  against a real pacing pathology. The bound caps decoupling at
  one field rather than zero; if God Hand's frame pacing keys
  off vblank-vs-FINISH ordering more tightly than the argument
  above credits, the symptom would be a *new* judder pattern
  (alternating early/late frames) at similar fields/s rather
  than a clean gain. The log arbitrates: watch `[frame-split]`
  present-age spread and the fields/s *variance*, not just the
  mean. The kill switch restores the m29 world in one ini flip.
- **`barrier` may not collapse to light-window levels.** The
  bound removes the wait for the *current* field's tail; the
  previous field's remainder is still owed at the barrier, and
  in a saturated crowd that remainder is one heavy batch
  (~7–8 ms) per field worst case. The expected shape is crowd
  `barrier` ≈ (previous-field tail) instead of (whole pipeline
  tail): join_wait should fall by roughly the current field's
  share, and fields/s should rise as the EE's field N+1 logic
  overlaps the VU's field-N tail — but if the crowd is closer
  to throughput-bound than the m29 log suggests (VU busy was
  32–55%, so it should not be), gains compress toward zero and
  the wall behind this one — native EE time, guest 76–89% —
  becomes binding, as PATH-VUJOIN §3 already warned.
- **Sampler edge cases.** First fields after boot use watermark
  0 (no wait) until two boundaries have been sampled — a
  two-field grace identical in spirit to the timing model's
  priming. A vblank delivered while a *deferred* barrier from
  the previous field is still re-armed cannot occur (delivery
  is what the barrier gates), and the tick guard makes
  re-entry idempotent.
- **GIF-stage stall release.** The SIGNAL-stall replay runs on
  the GIF thread outside chunks; while it runs, `busy` is set
  with the previous chunk's `currentSeq`, so the bound may wait
  out a replay that a chunk-only reading would have exempted.
  Conservative direction, replay-length duration, and identical
  in spirit to the old predicate (which also counted `busy`).

## 6. Verification done here

- **Touched translation units compile clean** in the real build
  tree (`build/android-m2a`, RelWithDebInfo, NDK clang,
  aarch64): `ps2_async_vif.cpp.o`, `EeScheduler.cpp.o`, and
  `ps2_memory.cpp.o` (rebuilt because the header is in its
  include chain; its source is untouched).
- **Full build links:** `ninja godhand` exit 0, ending in
  `Linking CXX shared library src/libgodhand.so`. Built
  artifact, reference only (nothing packaged):
  `build/android-m2a/src/libgodhand.so`, 99,717,800 bytes, md5
  `cf08fa3bf06723084a6947bf61910b80` (pre-change artifact —
  the m28/m29 core — was 99,713,624 bytes, md5
  `82fc27c5437d15144387b0cd0a384699`; +4,176 bytes).
- **VU generated tree byte-identical, pre and post:**
  `diff -r godhand-recomp/generated/vu1 b5-backup/vu1-gen-orig`
  reports no differences.
- **Bridge untouched:** `path-b/build-b1/libpcsx2gs.so` md5
  remains `9e468a9082b5e51fdfaf435c9dad909a`; nothing under
  `path-b/` was modified.
- **Live-regs intact:** `PS2X_VU1_LIVE_REGS` still present in
  the linked library and `ps2_memory.cpp` is md5-identical to
  the pre-change snapshot; the regread path was not edited.
- **Kill switch + attribution present in the built core:** the
  strings `PS2X_VU1_VBLANK_BOUND` and `barrier=%.1f ms (%llu)`
  are both found in the linked `libgodhand.so`.
- **Scope discipline:** pre-change md5s of the four candidate
  files were snapshotted first (`vblank-work/pre-change-md5.txt`);
  exactly the three named files differ. `waitPipelineIdle()`'s
  body is byte-for-byte the original — the unbounded primitive
  survives intact for hostCatchUp and the kill switch.
- **Not verified here (by design):** any runtime behaviour.
  This VM cannot run the game; the device log arbitrates per §7.

## 7. The lines that arbitrate a future device run

Same protocol as m27/m29 (cool phone, wide off, pipeline on at
depth 2, the b5 route into a crowd). Baselines:
M27-LOG-ANALYSIS.md and M29-LOG-ANALYSIS.md.

- **Boot/config proof:** unchanged core provenance rules plus
  the bridge at md5 `9e468a9082b5e51fdfaf435c9dad909a`;
  `[pcsx2-gs] pipeline on: ... depth 2 fields` must still print.
- **`[vu1] joins:` — the conviction line.** `regread` stays
  0.0 ms (live-regs non-regression). The m29 crowd signature —
  `idle` 664–1,589 ms/window — must *move*: residual `idle`
  should fall to the pacing-only scale (near zero in crowds,
  where the EE runs behind wall clock), and whatever barrier
  wait remains appears as **`barrier`**, expected to be a
  fraction of the old `idle` (the previous field's tail only).
  Crowd total `join_wait` target: from 671–1,590 ms toward the
  light-window scale — **< ~150 ms per 5 s window** remains the
  conviction threshold.
- **Fields/s:** crowd `[ee-work]` windows rise from 31–51
  toward **55–60**; light windows unchanged (57.9–59.9).
  `[frame-split]` presents avg toward ~17 ms with no new
  early/late bimodality (the §5 slip signature).
- **`[pathb] pipeline: ahead_max` touching 2 in crowds** and
  `dropped_captures` 0, as before: the producer finally feeding
  the bridge faster than it drains.
- **Audio:** no separate metric; choppiness should track the
  crowd fields/s rise and the join_wait collapse.
- **Attribution A/B:** the same build with
  `PS2X_VU1_VBLANK_BOUND=0` in the external `godhand.ini`
  restores the m29 barrier (expect `idle` to re-absorb the wait
  and `barrier` to read 0); `PS2X_VU1_LIVE_REGS=0` and
  `PS2X_GS_PIPELINE=0` remain available to re-isolate the
  earlier stages.
