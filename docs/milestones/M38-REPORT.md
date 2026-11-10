# M38 REPORT — PATH-TRAINMERGE (0.39-m38-trainmerge, versionCode 46)

CALLCOUNT step 1: the in-job segment train merge, built on the m37 tree
(0.38-m37-resident2). The interpreter segment loop still drives; inside
`runFused`, consecutive VU1 segments of one job now re-enter through a
light path that pays the funnel prologue (capture gate, trace probe,
verify gate, frame-stats begin, registry lookup) **once per train**
instead of once per segment. Per-segment state init (pc, top, itop)
stays in `execute()`/`resume()`, exactly as in m37; the callback layer
above the funnel is untouched, so every segment still surfaces its own
D/T stop, budget slices and cycle accounting.

**Verdict: SHIP-READY for the device gate.** Every host gate passes:
the new train bench arbitrates byte-exact train-on vs train-off on all
five fixtures (including the three adversarial ones), the standard
fixture arbitration is exact across all 16 switch combinations against
m36's published per-image sets, the micro-bench sanity line is exact,
and the packaging arbitration against the shipped m37 APK shows only
the intended entries differing. Two parts of the brief's light-entry
sketch did **not** survive arbitration and were corrected during the
build (active pointers and MXCSR stay per run — §2c); what the train
saves is the runFused prologue and the prof guard's per-segment hash
probe. The device gates in §6 are the arbiters of whether what remains
is worth keeping.

Kill switch: **PS2X_VU1_TRAINMERGE=0** — no train ever opens and every
run takes the m37 path, in the same binary (proven at the behaviour
level by the fixture arbitration, §2).

Renderer: the shipped core is the Path B variant build — compiled-in
`GODHAND_PATHB_DEFAULT_PCSX2=ON` (b5/m27 mechanism: env seeding ranked
above the internal ini, pcsx2 when no external renderer line exists),
so the phone's persistent `PS2X_GS_RENDERER=native` ini cannot poison
the test. 12 "path B variant" literals in the shipped core, including
`PS2X_GS_RENDERER=pcsx2 (variant default; ...)`. The stage (non-variant)
core carries 0.

A note on sources: `docs/CALLCOUNT-STEP0.md` on disk is **truncated
mid-Q4** (16,195 bytes, ends mid-sentence). §0–Q4 (gate GO, H1–H5,
S1–S4, the Q2 inventory) were read from the file; the Q5/Q6/§7 detail
(finer counters, adversarial fixtures, step-1 shape) was implemented
from the task brief's specification of the report, which is also where
the span cap (one job AND 1,048,576 VU cycles) and the prerequisite
list come from. This disclosure is part of the deliverable, per the
brief.

Workbench: `trainmerge-build-work/` (originals of all 11 touched files
in `orig/`, unified diff in `trainmerge.patch`, bench sources and
replay outputs in `replay/`, packaging scripts, stage/variant cores).

---

## 1. Change set (11 files)

Full unified diff: `trainmerge-build-work/trainmerge.patch`
(961 lines). Per file:

- **`src/lib/vu/ps2_vu1_core.cpp`** (the core)
  - `vu1TrainMergeEnabled()` — PS2X_VU1_TRAINMERGE, default on, read
    once like every other PS2X_ switch in the file.
    `kVu1TrainSpanCapCycles = 1048576`.
  - `runFused(..., bool newSegment)` — the train decision. A train is
    only wanted with a memory present and no verify / frame-stats /
    trace armed (the diagnostics the light path skips); an open train
    closes with break(Other) if a diagnostic arms mid-train. Light
    re-entry requires identity: code ptr/size, data ptr/size, GS,
    memory, code generation, and thread all equal to the train's;
    accumulated cycles ≥ cap closes it with break(Cap) and the segment
    falls through to the full path, which opens the next train; an
    identity change closes it with break(Generation). The full path is
    otherwise m37's, unchanged; on its final branch it opens a train
    when wanted. After any train run, `stoppedByD/T` closes the train
    with break(Stop) — H4 surfaces at its own segment.
  - `run()` — the VU1 branch passes `newSegment=true`;
    `resume()` passes `!t_vu1BudgetHit` (a budget continuation is not
    a new segment).
  - `runLoopWith` — for train segments the `EeProfVuRun` guard reuses
    the train's image hash (`useTrainHash`) instead of re-probing the
    hash cache, and the train-open run's guard stores its computed
    hash into the train (`captureTrainHash`). Cycle accounting for
    the train (cycle0 fold at exit) is unconditional. Two corrections
    from arbitration are in §2c: the active-pointer stores and the
    MXCSR set/restore are **not** skipped for light runs.
  - `VU1Interpreter::breakVu1Train(kind)` — closes the train, records
    it via `recordVuTrain` (segments, cycles, wall span, break kind),
    notes core-detected kinds in the Q1 sequence. External kinds
    (job end, MPG, MSKPATH3 unmask) are noted by the wiring site just
    before it calls in, so every break is recorded exactly once.
    `reset()` closes any open train with break(Reset) first.
  - **Q1 instrument**: `Vu1SeqRecorder` — 100,000-record linear buffer;
    each record is one word (bit 31 = boundary, bits 30–27 = image
    slot, bits 26–0 = pc; boundaries carry the reason). Image table
    keyed by (code, memory, generation) with a last-slot fast path.
    Boundaries are recorded only when calls precede them. One-shot
    dump at capacity: a `[vu1-seq]` histogram line on stderr, plus the
    full sequence (`c <image-hash> <pc>` / `b <reason>` lines) to the
    path in PS2X_VU1_SEQ when it is a path. PS2X_VU1_SEQ=0 disables;
    unset or "1" records with stderr histogram only.
    `ps2x::eeprof::noteVuTrainBoundary` is defined here at file scope
    and forwards boundary kinds to the recorder.
  - `recordVuRun` stays **per segment** (deliberate): [ee-prof] vu1
    runs/program attribution remains comparable to m36/m37.
- **`src/lib/vu/ps2_vu1_impl.inl`** — `runFused` declaration gains
  `bool newSegment`.
- **`include/runtime/ps2_vu1.h`** — public `breakVu1Train(uint32_t)` +
  `vu1TrainOpen()`; private train state (open flag, identity keys,
  cached compiled program, image hash, cycles, segments, thread,
  span timestamps, light/capture-hash flags).
- **`include/runtime/ee_prof_stats.h`** — Snapshot gains
  vuTrainCount / vuTrainSegments / vuTrainCycles, vuTrainBreaks[8],
  vuTrainHist[9] (buckets 1, 2, 3, 4, 5–8, 9–16, 17–32, 33–64, 65+),
  vuTrainSpanMaxWinNs and vu1CbSpanMaxWinNs (per-window maxima,
  exchanged in `snapshot()`); `TrainBreakReason` enum {JobEnd, Mpg,
  MskPath3, Stop, Cap, Generation, Reset, Other}; declarations for
  `recordVuTrain` and `noteVuTrainBoundary`.
- **`src/lib/ps2_runtime.cpp`** — train atomics + `atomicMax` + hist
  bucket fn; `recordVuTrain` (zero-segment trains — pure continuation
  slices of an already-counted segment — are deliberately not
  recorded); the callback-worker branch of `recordVu1Callback`
  updates the per-window callback span maximum; `snapshot()` fills
  and exchanges the new fields; `syncCoreSubsystems` wires the memory
  boundary callback: note the boundary, then
  `m_vu1.breakVu1Train(kind)`.
- **`include/runtime/ps2_memory.h`** — `Vu1TrainBoundaryCallback`,
  setter, `notifyVu1TrainBoundary`, member.
- **`src/lib/ps2_memory.cpp`** — end of `processPendingTransfers`
  notifies TrainBreakJobEnd (H1) on the synchronous path only; in
  async mode the worker fires it.
- **`src/lib/ps2_async_vif.cpp`** — the worker's `run()` notifies
  TrainBreakJobEnd after the job's item loop, before the GIF
  drain/publish. (Supporting fact verified in the source: the worker
  sets `_mm_setcsr(job.mxcsr)` per job, so MXCSR is job-stable on the
  worker thread.)
- **`src/lib/ps2_vif1_interpreter.cpp`** — the VIF1 MPG branch
  notifies TrainBreakMpg (H2) after `markVU1CodeModified`, only when
  the copy actually happened; the MSKPATH3 unmask branch notifies
  TrainBreakMskPath3 (H5) **before** `flushMaskedPath3Packets`.
  (VIF0 MPG deliberately untouched.)
- **`src/lib/Kernel/EeScheduler.cpp`** — a separate
  `[ee-prof:train]` line after the [ee-prof] line (the [ee-prof]
  format itself is byte-stable for existing parsers), printed only
  when trains closed in the window: trains, seg, avg, vcyc,
  spanmax ms, cbmax ms, breaks job/mpg/msk/stop/cap/gen/reset/other,
  hist (9 buckets).
- **`ps2xIOP/src/emulator/devices/iop_spu2.cpp`** (Q5 ADMA counters) —
  per-window maximum lateness + six late bands (<1 ms, 1–5, 5–10,
  10–25, 25–50, ≥50 ms) accumulated in `admaSample`; at window close
  a second line `[stall] adma late bands:...` prints and the window
  state resets. The original [stall] summary line is untouched.

Design decisions recorded: the rider pre/post (callback layer) is
untouched; capture `calls.txt` undercounts under trains by design
(entries are still captured on train-open segments; an MPG generation
bump forces a full path); frame-stats / verify / trace streams never
train.

---

## 2. Fixture gates (host)

All benches link the tree's generated VU code (`libvu1_ship.a`,
rebuilt fresh for m38) + the host `libps2_runtime.a` / `libps2_iop.a`
under test.

### 2a. Train bench — the adversarial fixtures (Q6) + train arbitration

New bench `trainmerge-build-work/train_bench.cpp`: drives a real
PS2Memory + `processVIF1Data` + a persistent VU1Interpreter, with
callbacks wired exactly like PS2Runtime (65,536-cycle slices, the
16-slice `finishVu1Program` chain, boundary → note + break). It runs
trainmerge on vs off and diffs the transcripts: **every CB line and
every FINAL digest (effective state + data + code FNV) must be
identical; only the TRAIN counter lines may differ.** One Rig (one
memory) serves all fixtures, like the real runtime — see §2c for why
that matters. Final run: `replay/train-on5.txt` / `train-off5.txt` —
**diff is clean except the TRAIN lines themselves.**

| fixture | what it proves | train evidence (on) |
|---|---|---|
| basic — 6× UNPACK+MSCAL 0x0358 (image 5dce…), MSCNT finisher | trains form and close inside one job (H1/cap discipline) | trains=6 seg=6, breaks cap=6; each wall segment alone exceeds the cycle cap, so each is its own train |
| mpg — 3 wall segments, MPG overwrites the 0x0358 pair with an E-bit NOP, 3 more MSCALs | **H2**: MPG breaks the train mid-job; post-MPG segments run the new program (CB cycles drop to 2) | trains=4 seg=6, breaks job=1 mpg=1 cap=2, hist has one 3-segment train |
| dbstop — D bit patched onto pair 0 of the wall entry, D stops enabled, 4 MSCALs | **H4**: every segment stops on its first pair (stopD=1, cycles 18/2/2/2) and the train breaks with Stop at its own segment | trains=4 seg=4, breaks stop=4 |
| mskpath3 — mask, 2 segments, unmask, 2 segments | **H5**: the unmask breaks the train before masked packets flush | trains=4 seg=4, breaks job=1 msk=1 cap=2 |
| cap — 24× MSCAL 0x00C8 (image cd8f…) in one job | span cap fires inside a job; last train closes at job end | trains=24 seg=24, breaks job=1 cap=23 |

H3 (EE join points) holds by construction: trains live entirely
inside the VU worker's job processing and in the synchronous
`processVIF1Data` path; nothing carries a train across a job
boundary — H1 closes it at every job end in both modes.

### 2b. Standard fixture arbitration (per-entry cycle identity + digests)

`trainmerge-build-work/bench-vu_bench` (the stageprog vu_bench,
recompiled against the m38 headers — the VU1Interpreter layout
changed, so the old object could not be reused) run **per image in
its own process** (the proven m35/m36 method; whole-process runs are
nondeterministic in this environment) over the 5 captured images /
22 entries, across the full switch matrix — lean on/off × slim
on/off × resident on/off × **trainmerge on/off** = 16 combinations
× 5 images = 80 runs (`replay/pi38/`, driver `arbitrate_std.sh`):

- **trainmerge-on ≡ trainmerge-off in every one of the 8 base
  combinations, for all 5 images** (RESULT/TRIVIAL sets
  byte-identical).
- **trainmerge-off ≡ m36's published per-image sets**
  (`resident-build-work/replay/pi/`) in all 6 published
  combinations — so m38 ≡ m36 ≡ the arbitrated m35 sets: **29
  RESULT/TRIVIAL lines in the shipped configuration** (lean on,
  slim on, resident on, trainmerge on).
- Kill-switch equivalence is part of this result: PS2X_VU1_TRAIN
...[truncated 8532 chars]