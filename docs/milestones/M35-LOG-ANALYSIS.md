# m35 device log analysis — the funnel paid its ~0.4 µs and closed; the residue is re-entry, confirmed by elimination

**Session: the last session in `device-logs/2026-11-07-m35/files/godhand-boot.log`
(lines 13252–14427 of the appended file), booted 2026-11-07 09:50:53 WITA.
Build under test: 0.36-m35-funnel (versionCode 43) — m34 plus the funnel
slimming (M35-REPORT.md). Owner's verdict: still laggy and frame-dropping
with stuttering audio, but noticeably better than m34. The log agrees with
him on every rate metric, and it closes the funnel as a front.**

- **The split (gameplay windows pooled): gap 3.524 µs/run = pre 0.101 +
  post 0.089 + residue 3.333 µs.** Against m34 (gap 3.93 = pre 0.111 +
  post 0.098 + residue 3.72): the gap fell **0.41 µs/run (−10.3 %)** and
  the residue fell **0.39 µs/run (−10.4 %)**. Essentially the entire m35
  gain landed exactly where the funnel diet aimed.
- **Residue 3.33 µs/run is nowhere near the 0.3 µs/run line** that would
  have convicted the funnel of holding the money (M35-REPORT §4). The
  funnel held ~0.4 µs/run of real, removable instruction cost — now
  removed — and the remaining ~3.3 µs/run survives the removal of every
  named instruction suspect in the bracket. That is the re-entry reading
  confirmed by elimination: per-invocation cache/TLB refill of the VU
  working set on the worker thread, proportional to invocation count,
  indifferent to instruction diets.
- **Total callback time fell:** vu1worker 21.90 µs/callback vs m34's
  24.49 (−10.6 %). Gap share of worker is unchanged (16.09 % vs 16.05 %) —
  shares move with session mix; the µs/run rates are the arbiter, and they
  all moved the same way, matching the owner's "better but still laggy":
  a modest real improvement, not a fix.
- **Feel:** gameplay fields/s floor **42.3** (m34: 40.7), ceiling **58.7**
  (53.2), median **53.6** (49.8). Frame-stats: avg frame 18.7 ms (m34
  session: 20.6), worst frame **923 ms** (2,742). Median per-window join
  wait 218.8 ms (m34: 356.6).
- **Correctness clean:** dropped_captures = 0 in all 50 pipeline windows,
  missfn 0, interp 0 / budget 0 / fb 0 across all gameplay, ADMA never
  starved (25/25 summaries 0 late / 0 starved). No missing-geometry signal.

---

## 1. Session identification and shape

- Boot banner `[2026-11-07 09:50:53.178] GameActivity onCreate` →
  `===== godhand android 0.19-m18 boot =====` at line 13264 (shell
  version; as with m34, the core is identified by its instruments, below).
- **m35 proof:** all 52 gameplay [ee-prof] windows carry the m34-rider
  segment `gap %.1f ms (pre %.1f post %.1f, cb %llu)` — the rider is
  untouched in m35, and m35 is the only build after m34 that prints it;
  this log is the return for the m35 zip delivered the same morning
  (09:51 vu-capture timestamps inside the owner's zip). Config lines as
  required: `PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`,
  `PS2X_GS_SCALE=2`, `PS2X_THREAD_POLICY=off`, `[vu1-aot] fmac: fast`,
  `[vu1-aot] lean execute: on`. The device ini did not poison the test.
  No slim-funnel banner exists (the switch is silent by design); slim-on
  is the shipped default and PS2X_VU1_SLIM_FUNNEL appears nowhere in the
  session's env dump, so the fused path ran.
- Shape: **56 profile windows ≈ 240 s of guest time; 14,990 fields by
  [ee-work], 12,794 presents by [frame-stats]** (per-process counters).
  Windows 1–4 are boot/menu (cb = 0, VU 1 %). **Gameplay = the 52 windows
  with cb > 0** throughout this analysis (m34 pooled 18).
- **Comparability warning:** this session is ~2.4× longer than m34's and
  compositionally different — it includes light stretches (fields/s
  56–58.7, VU 45–60 %) that m34's all-crowd session lacked, alongside 8
  genuinely heavy windows (fields/s < 50, VU 82–92 %). Rates per
  invocation (µs/run, µs/callback) are compared head-to-head; fields/s
  and totals carry the mix caveat wherever they appear.

## 2. The decomposition, m35 vs m34 (pooled gameplay)

m35 pool: cb = 7,203,522 worker runs; vu1worker total 157,752.7 ms;
vu1 total 132,370.5 ms (83.9 % of worker).

| component | m35 ms | m35 µs/run | m34 µs/run | Δ |
|---|---|---|---|---|
| pre (callback entry → timer start) | 730.7 | 0.101 | 0.111 | −0.010 |
| post (execute return → finish return) | 644.2 | 0.089 | 0.098 | −0.009 |
| **residue (timed span, outside the program)** | **24,007.2** | **3.333** | **3.72** | **−0.39 (−10.4 %)** |
| gap total | 25,382.1 | 3.524 | 3.93 | −0.41 (−10.3 %) |
| vu1worker total per callback | 157,752.7 | 21.90 | 24.49 | −2.59 (−10.6 %) |

- **Conservation: pre + post ≤ gap in all 52 gameplay windows, zero
  violations** (also in all 56 windows overall). The rider's arithmetic
  remains sound; the split is real.
- Per-window residue: 2.04–4.62 µs/run, median 3.27 — the spread still
  tracks how much other work the thread did between invocations (the
  heaviest windows sit at the top), which is the re-entry signature, not
  an instruction signature.
- pre and post each fell ~0.01 µs/run — inside noise, and irrelevant
  either way: together they are 5.4 % of the gap. The callback's own
  setup and finish path stay dead as suspects.
- Reading of the −0.39: the M35 report priced changes 1+2 at "a few ns"
  on the warm host loop and change 3 (the EeProfVuRun gate) at ~180 ns
  of host funnel — but on device, the collapse also removes *cold*
  touches: one registry lookup chain, one dispatch layer's stack frame
  and branch targets, and the capture scan's atomic traffic, each paid
  at cold-cache prices on a thread that had just been doing other work.
  0.39 µs/run of cold-priced instruction work in the funnel is
  consistent with everything measured. It is also, now, **gone**: the
  funnel that remains is execute() prologue → runFused() → runLoopWith()
  with no removable layer left in it.

## 3. Feel metrics vs m34

- **Fields/s (gameplay, n = 52):** floor **42.3**, ceiling **58.7**,
  median **53.6**, mean 53.0, pstdev 3.77. m34 (n = 18): 40.7 / 53.2 /
  49.8 / 48.9 / 3.05. Floor and median both up; the ceiling rise is
  partly the light stretches this session had and m34's did not — the
  floor rise (+1.6 in a session with 8 sub-50 windows) is the figure
  that survives the mix caveat.
- **Frame-stats (per-process final):** presents 12,794, avg frame
  **18.7 ms**, worst **923 ms**; bands ≤16.7 ms 38.9 %, ≤25 ms 50.7 %,
  ≤33 ms 9.5 %, ≤50 ms 0.55 %, ≤100 ms 0.23 %, >100 ms 0.05 %. m34
  session: avg 20.6 ms, worst 2,742 ms, bands 36.3 / 43.9 / 16.3 / 2.5 /
  0.8 / 0.07 %. The >25 ms tail roughly halved its share.
- **Joins:** per-window join_wait median **218.8 ms** (m34 gameplay
  median 356.6), worst window **1,041.6 ms** (m34: 719.9) — one
  stage-load window; queueWait max 782.3 ms (m34 session peaked at
  1,565.7). Pooled join time is again barrier + idle = **99.5 %**
  (m34: 99.3 %), regread/regwrite/vumem/download all 0.0. Joins remain
  a synchronization shape, not a cost front: the EE waits because the
  worker is busy, and the worker got ~10 % cheaper per callback.
- **Memslow:** unlike m34 (storms confined to boot + two windows), this
  longer session storms recurrently in gameplay — 17 of 52 windows
  >100 ms, pooled 10,532 ms, max 897.2 ms — tracking area streaming in
  the light stretches as much as crowds. It does not price the fps
  floor: the two heaviest memslow windows (897 / 868 ms) run at 51.9
  and 56.2 fields/s, and the floor window (42.3) has memslow 8 ms.
  Recorded as session texture, not a front.
- **Audio:** the owner's "suara juga masih patah patah" stands
  unrefuted — ADMA never starves at the 50 ms granularity the [stall]
  summary measures, but a session whose crowd windows still run 42–50
  fields/s against a 60-fields cadence has field-time jitter the audio
  path hears. Audio remains a speed symptom until the floor moves.

## 4. Verdict on the funnel front (per the M35 report's arbiter rules)

- Residue fell from 3.72 to **3.33 µs/run** — a real fall, an order of
  magnitude above the 0.3 µs/run line. The rule's second branch applies
  in spirit with a correction: residue did not stand at ~3.7, it fell
  by the amount of genuine instruction work the funnel contained, and
  total callback time fell with it (−10.6 % per callback). **The
  re-entry reading stands, strengthened**: it now rests on elimination —
  dispatch layers, registry lookups, capture scans, profiling blocks,
  scheduler resets, callback setup and finish paths have each been
  removed or priced at ≤0.1 µs/run, and 3.33 µs/run remains, tracking
  invocation count and inter-invocation thread work, exactly as a
  cache/TLB refill cost should.
- **The funnel is closed as a front.** No fourth diet is proposed.
  Further µs/run must come from making the invocations cheaper to
  *enter*, not from removing instructions at the door — that is the
  residency experiment below — or from making fewer invocations
  necessary, which is a program-structure question (the 0x0358/0x0520
  walls) already closed at step 0 on both entries.

## 5. The next experiment — PATH-RESIDENT: keep the VU working set resident on the worker thread

### What the working set is, in this codebase (code read, 2026-11-07)

- **The interpreter object:** `PS2Runtime::m_vu1`, a `VU1Interpreter`
  held by value inside the runtime object (`include/ps2_runtime.h:502`).
  Its hot state — VF registers, ACC/Q/P, control registers, scheduler
  and pipeline bookkeeping — is re-touched at every one of the session's
  7.2 M invocations.
- **The memory images:** `PS2Memory::m_vu1Code` / `m_vu1Data`, two
  separate `new uint8_t[16384]` heap allocations
  (`ps2_memory.cpp:669-670`), wherever the allocator happened to place
  them — different pages from each other and from the interpreter, on a
  heap shared with every other subsystem's churn.
- **The generated code:** the compiled-program registry's host code
  pages for the active images (0x0358-cd8f and 0x0520 ×2 on this
  session's mix), entered via `runLoopWith()`.
- **The thread:** one FIFO worker (`ps2_async_vif.cpp`, "VUThread").
  It pops a Job, runs `processVIF1Data`, and the MSCAL/MSCNT callback
  (`ps2_runtime.cpp:1137`/`:1178`, `onWorker()`) fires
  `m_vu1.execute()` from inside that processing — ~400–500 invocations
  per job. Between jobs the thread does publish/watermark bookkeeping
  and sleeps on the queue; between invocations inside a job it runs
  VIF packet processing. Android is free to re-place the thread on a
  different core — a different cluster's L2 — at every wake.

### The change set (one build, three parts, all or nothing per part behind one switch)

1. **Co-locate the images.** Allocate `m_vu1Code` + `m_vu1Data` as one
   64 KB-aligned, page-aligned contiguous arena (32 KB payload) at
   PS2Memory construction, first-touched by the VUThread, instead of
   two independent `new[]` calls. Accessors (`getVU1Code/Data`,
   `mapRange`) keep their exact API, so save-state and DMA paths are
   untouched semantically. The interpreter object itself is *not*
   moved in this build (it is embedded by value in PS2Runtime; moving
   it is a structural change that must not ride along unpriced).
2. **Prefetch at the funnel mouth.** At the head of the fused dispatch
   (`VU1InterpreterAccess::runFused`, ps2_vu1_core.cpp:1640) — the
   first code the residue bracket executes — issue `__builtin_prefetch`
   for: the interpreter's state block, the code image page containing
   the entry PC, and the data image base. Three to four lines, issued
   while the remaining prologue (capture gate, verify gate, prof ctor)
   executes, so the refills overlap the work that currently pays for
   them serially.
3. **Pin the thread.** At VUThread start (`ps2_async_vif.cpp` worker
   entry), `sched_setaffinity` the thread to the highest-capacity CPU
   it is allowed on, fail-soft: on EPERM or any error, log one line and
   run unpinned (Android cpuset policy may refuse; a refused pin must
   never be a boot failure). This attacks the migration term: a thread
   that sleeps between jobs currently wakes wherever the scheduler
   puts it, and each cross-cluster wake reprices the whole working set
   at cold L2.

Kill switch: **PS2X_VU1_RESIDENT=0** reverts all three parts (plain
`new[]` allocations, no prefetches, no affinity call) in the same
binary, read once into a static like every other PS2X_ switch in the
tree.

### Expected mechanism of gain

The residue is paid at the start of the timed span because that is
where the cold lines are first touched. Residency attacks the three
ways they get cold: scattered placement (part 1: the two 16 KB images
stop competing with unrelated heap traffic and sit in known pages),
serial first-touch (part 2: the refill starts ~0.1–0.2 µs earlier and
overlaps the prologue instead of stalling it), and migration (part 3:
the thread's L1/L2 warmth survives job boundaries because the thread
stays on one core). No instruction is removed and no program changes;
if the re-entry reading is right, residue falls toward the warm-loop
price the host micro-bench already exhibits (~0.3 µs/call for the whole
funnel on x86). A realistic device target is residue ≤ 2.5 µs/run;
the full 3.3 is not promised — the generated code's own pages also
cycle between the stage and character images inside a job, and this
build does not touch that.

### Risks and correctness gates

- **Semantics:** parts 1–3 change placement and timing only. The
  fixture bench must reproduce m35's digests *and* cycle counts
  exactly, slim/resident in every on/off combination — any divergence
  is an automatic fail, since nothing here may alter a single VU
  cycle. Save-state round-trip (saveVuState/loadVuState) must be
  re-run against the arena allocation.
- **Thread safety:** the arena is written by DMA/VIF paths on the EE
  and GIF threads as today (same pointers' targets, new backing
  store); no synchronization changes. Prefetch is side-effect-free by
  construction. Affinity is process-local and fail-soft.
- **Contention:** pinning the VUThread to the prime core puts it
  beside the game thread's preferred core; if the log shows EE-side
  inflation (native ms per window up at equal fields), the pin target
  moves to the second performance core in a rider, not a rebuild of
  the experiment.
- **Bench blindness (stated in advance):** the host micro-bench loop
  is already resident — it *cannot* price this change and must show
  ~no delta; a host delta would itself be a red flag. The device log
  is the only arbiter, same as for m35.

### Device gates (pre-registered, against this report's m35 baselines)

- **Kill:** residue within ±0.15 µs/run of 3.333 and total callback
  time within ±2 % of 21.90 µs/callback at comparable crowd windows →
  re-entry is not priced by placement/pinning either; the residency
  front closes and the residue is accepted as the cost of the async
  architecture, with effort returning to the program walls' *call
  count* (fewer, larger invocations) as the only remaining lever on
  this term.
- **Success:** residue ≤ 2.5 µs/run with conservation intact and the
  crowd fields/s floor above 42.3 → residency becomes the default and
  part 2's prefetch set is tuned (image pair prefetch for the
  alternating 0x0358/0x0520 pattern) as a follow-up.
- Automatic fail, as always: any new missing geometry or visual
  corruption; dropped_captures ≠ 0; conservation violation in any
  window.
