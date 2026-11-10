# Throughput stage — the per-run VU1 execution tax

Question handed down from M31-LOG-ANALYSIS.md: in crowd windows the
VU1 worker burns 1,593-4,257 ms per ~5 s window executing
80,680-212,711 compiled microprogram runs while every downstream
consumer idles (GS worker 63-72% idle, bridge park ~0). The joins
(32.2-1,007.4 ms/window) are ~95%+ waits for that real unfinished
work. What does one run actually cost, where inside the run does the
time go, and what lever cuts it?

Short answer: the runs are already AOT-compiled, already
segment-optimized, already fast-FMAC where the translator can prove
flags dead — and they still cost **11.45 ns per emulated VU cycle**
(session aggregate; ~10.5 in the heaviest windows), because the
emitted code pays full cycle-accurate ceremony around every
instruction pair: a screened FMAC costs ~14-18 SSE ops to do 2 ops
of arithmetic, and every MSCAL pays a ~3.2 µs fixed stack (scheduler
reset, capture hook, lookup, callback bookkeeping) before the first
instruction executes. The wall is not one thing; it is a stack of
three measured layers, and this report prices each one and ranks
the levers against them. The headline lever is a translator change:
extend the existing fast-FMAC treatment to the folded-flag sites
that still pay the range screen, dual-registered behind a runtime
switch so the device A/B is one binary.

## 1. The captures, profiled

First, a correction to the record. The device return
(`m31-device-return/files/vu-capture/`) does not hold 27 bins. It
holds **5 microprogram images** (16,384 B each = a full VU1 micro
memory dump, named by the FNV-1a-64 of their bytes — the hash
`captureVu1Program` computes at ps2_vu1_core.cpp:631) and **22
distinct entry points** across them. 22 + 5 = 27 files is presumably
where the earlier count came from. `calls.txt` reads
`calls 4500000 entries 22`.

How the capture files work (ps2_vu1_core.cpp:621-662, capture is
enabled by the shipped glue itself, godhand_android_main.cpp:691):
on each image-generation change the image is written once if
absent; each distinct (image, entry) is appended to entries.txt
**once per process** (`entryCalls[key]++ == 0`); a cumulative call
counter is rewritten to calls.txt at every 100,000-call boundary.
entries.txt is therefore exactly 14 repetitions of one identical
22-line cycle — 14 capture-enabled launches have each met the same
22 entries, in the same first-encounter order. The bins date from
the first of those launches (2026-11-05 03:34); the m31 session
appended its cycle and was the last writer of calls.txt.

Cross-check that these 5 images are the whole story for the m31
session: the ee-prof windows sum to **4,498,079 runs** for the
session, against the capture counter's last written 4,500,000 (the
counter crosses the boundary mid-session and keeps counting; the
~1,900 difference is runs outside the windowed span). Every session
in the appended log prints `[vu1-aot] 5 compiled VU program(s)
registered`, and all 37 m31 windows show **interp 0, fb 0, budget
0** — no image ran interpreted, no compiled invocation ever
declined to the interpreter, no run ever hit its 65,536-cycle slice
(ps2_runtime.cpp:1079, kVu1Slice; the finishVu1Program resume loop
is a safety net God Hand's programs do not use). The AOT set is
complete and stable; per-run cost is a codegen question, not a
coverage question.

### Image structure

The five images compose from shared parts (byte-compared):

- **One high half, shared by four images.** The 0x2000-0x3FFF half
  of 2cfd, 390a, 4dfe and cd8f is byte-identical; their generated
  shards for that region are the same size to the byte. Its entries
  are 0x2000, 0x20C0, 0x2338, 0x2378. 5dce has no high half at all
  (zeros above 0x11E5).
- **A low-half family.** 5dce's low half is byte-identical to
  cd8f's (so cd8f = that low program + the shared high half). The
  other three low halves are variants of the same family, differing
  by 391-2,068 bytes over 16 KB. Entry layout is shared with small
  shifts: 0x0000 everywhere; 0x00B8 in 2cfd/390a/4dfe vs 0x00C8 in
  5dce/cd8f; a third entry at 0x0520 (2cfd, 4dfe), 0x0358
  (5dce/cd8f), 0x06F8 (390a only).
- Roles (inference, labelled as such): the round-9 note in the
  translator (vu1_recomp.cpp:37-48) names 4dfe "the character
  program" and cd8f "the stage program". 2cfd and 390a are sibling
  variants of the same object/character family. The 5dce/cd8f low
  program is clip-heavy (38 clip sites in its first shard vs 4-9
  elsewhere) — environment geometry with per-vertex clip tests. The
  shared high half reads as a common post-pass over transformed
  data.

### Who dominates the crowd windows

The captures carry identity, not weights; the weights come from the
log's own `[ee-prof:top] vu1 programs by time`, which aggregates run
wall time **by start PC**. Session totals over the 37 windows
(top-3 lists only, so these are floors):

| entry (start PC) | images | calls | run time | µs/call | ≈vcyc/call |
|---|---|---|---|---|---|
| 0x0520 | 2cfd, 4dfe | 2,044,395 | 45,063 ms (49%) | 22.0 | ~1,920 |
| 0x0358 | 5dce, cd8f | 1,182,271 | 26,358 ms (29%) | 22.3 | ~1,950 |
| 0x2378 | cd8f, 2cfd (shared high half) | 252,315 | 3,551 ms (4%) | 14.1 | ~1,230 |
| 0x00C8 | 5dce, cd8f | 72,659 | 3,229 ms (4%) | 44.4 | ~3,880 |
| 0x00B8 | 2cfd, 390a, 4dfe | 22,495 | 572 ms (1%) | 25.4 | ~2,220 |

Top-3 accounting covers 78,773 of 91,036 ms of run time (87%); the
remaining 17 entries (all the 0x0000 load prologues, 0x2000, 0x20C0,
0x2338, 0x06F8, …) share the residual ≤13%, none ever cracking a
window's top 3.

**Two entries are the crowd wall: 0x0520 and 0x0358, 78% of all
run time and 72% of all runs.** Both are loop entries in the
transform family: 0x0520 is a two-pair trampoline that branches
back to the loop head at 0x00B8 (generated vu1_2cfd…_0.cpp:4143);
the body it feeds (seg_00B0 onward, ibid:1978) is a matrix-load +
vertex-transform loop. 0x0358 is the same shape in the stage
program — a delay-slot trampoline into seg_00C8
(vu1_5dce…_0.cpp:1462), with a per-vertex clip-commit epilogue at
0x0368-0x0430 (one flag-pushing FMAC + commitClip + IBNEQ per
vertex, ibid:1483). 0x00C8, the expensive-per-call entry (44.4 µs),
is the stage program's full object entry — few calls, long runs.

Confidence and limits, stated plainly:

- The per-PC aggregation conflates images that share a PC. The
  conflation is benign here: at 0x0358 the two sharing images are
  byte-identical in that half; at 0x2378 the code is one shared
  half; at 0x0520 the two variants differ by 391 B over the whole
  image and their loop structure is the same. What cannot be split
  from this log is 2cfd-vs-4dfe share at 0x0520 (4dfe's generated
  code is ~15% larger; it is plausibly the busier of the two in
  character crowds, but that is a guess, not a measurement).
- Captures alone cannot give dynamic weights, per-site FMAC flavour
  mix, or stall-vs-issue cycle split; the weights above come from
  the log, the flavour mix from the generated sources (§2), and the
  stall split does not exist in any current instrument (§3, L3
  rides the counters that would create it).

## 2. Where per-run time actually goes

### The call chain (anchors to current sources)

EE thread submits VIF1/GIF items in jobs (FIFO, cap 64) →
**VU worker thread** ("VUThread", priority Highest;
ps2_async_vif.cpp `run()`, :316) pops a job and calls
`memory.processVIF1Data(...)` per item (:352) → VIF dispatch in
ps2_memory.cpp: UNPACK writes into VU1 data memory; MSCAL/MSCALF/
MSCNT invoke the callback registered at ps2_runtime.cpp:1140 →
`m_vu1.execute(..., kVu1Slice)` (:1147) →
`VU1Interpreter::execute` (ps2_vu1_core.cpp) calls
**resetScheduler()** then `run()` (:1349) → `runLoop` (:1396) →
`runLoopWith` (:1406) → the registered `runImage<hash>` →
`runFast` (ps2_vu1_impl.inl:3914) → per-block `fastBlock<hash, N>`
dispatch (`table[pc>>3]`, table sized to the used code extent —
1,139 entries for 2cfd: exactly 0x2398/8) → inside blocks,
**segments** (`seg_XXXX`) for straight-line code and per-pair
`pc_XXXX` chains at control-flow joins. After the job's items:
`m_gifArbiter->drain()` and `publishGif(worker)` on the worker
(ps2_async_vif.cpp:355-361); XGKICK output streams mid-job through
`submitGif` (32 KB flushes for PATH1, ibid) to the **GIF stage
thread**, which replays packets into the GS frontend. The EE joins
at the vblank barrier (EeScheduler.cpp:2963, bounded since m30),
host catch-up (ibid:728), and the guest's own drain sites
(regread/regwrite/vumem/gspriv/download — ps2_async_vif.h JoinSite
table); all waits are JoinTimer-attributed (ps2_async_vif.cpp:175).

### The three accounting layers (all measured, window 4 as the type specimen)

The log nests three timers around the same work; keeping them
separate is what makes the levers below priceable:

| layer | what it times | w4 | session |
|---|---|---|---|
| runLoopWith (`[ee-prof] vu1`) | compiled execution only | 1,245.9 ms | 91,036 ms |
| MSCAL/MSCNT callback (`[ee-prof] vu1worker`) | execute() + run + finish, per run | 1,592.8 ms | 105,579 ms |
| worker job (`[ee-work] VU %`) | + VIF parse/UNPACK, arbiter drain, publishGif | ~1,700 ms (34%) | — |

So of the worker's crowd time: **~78% is the compiled execution
itself, ~14% is per-run fixed cost inside the callback but outside
runLoopWith (3.2 µs/run session average; 4.3 µs/run in w4), and
~6-8% is job-layer work around the callbacks** (~110 ms in w4;
~100-350 ms across heavy windows). Session rates: 4,498,079 runs,
7,951,030,061 vcyc → **1,768 vcyc/run, 11.45 ns/vcyc, 20.2 µs/run**
(runLoopWith basis). At the POCO F5's ~2.9 GHz boost that is ~33
host cycles per emulated VU cycle — and the emitted code's op
counts below account for it fully. There is no hidden wait inside
runs; the worker's busy time is real instruction execution, which
is precisely why only codegen and fixed-cost levers can move it.

### Inside the compiled run

Per `runLoopWith` invocation (ps2_vu1_core.cpp:1406-1520):

- **Prologue**: active-pointer stores, MXCSR save + force
  RZ/FTZ/DAZ rounding, an EeProfVuRun RAII (two steady_clock reads
  per run — part of the measured time, ~1% on its own).
- **runFast entry** (impl.inl:3914): entry-condition checks (a
  pending branch/ebit/store forces Fallback — never taken in m31),
  materializeWrites, the normalized-register shadow check
  (syncAllNorm = 33 vnorms, only when the interpreter or a state
  load invalidated it — cold in steady crowds), loadFlags, then the
  block-dispatch loop: one indirect call per block transition,
  including every loop back-edge that crosses a block function
  (blocks hold ≤128 pairs, kDefaultBlockPairs in vu1_recomp.cpp).
- **Segments** (the hot loops live here): one budget check per
  segment with a translator-proven bound (`if (cyc + 61u > budget)
  goto pc_00B0`, vu1_2cfd…_0.cpp:1979); registers hoisted to locals
  (raw + normalized copies); per-pair stall bookkeeping reduced to
  a timestamp store (`s00B0_N = cyc`) with latencies applied at use
  sites; self-looping segments re-enter without full reload
  (vu1_recomp.cpp:1422). This machinery is already good. What it
  cannot remove is the per-op cost below.
- **Per-pair `pc_` chains** (clip epilogues, branch joins, streaming
  sections): per pair — a budget check, an rmax stall chain over
  every source register's ready time (5-10 dependent loads from
  `vu.m_vfReady`/`m_viReady`), the op, and 4-8 ready-time stores
  (`m_vfReady[r][lane] = cyc + 4`). The 0x0520 region at
  vu1_2cfd…_0.cpp:4151-4317 is the type specimen. These regions are
  the minority of dynamic cycles (both hot loops are segments) but
  they are where the clip epilogue of every stage-program vertex
  pays full ceremony.
- **Epilogue**: xgkickSync, commitFlags/commitEfu, storeFlags,
  cycle write-back, MXCSR restore.

### The FMAC price table (impl.inl, emitted per site by vu1_recomp)

VU float semantics are not IEEE: operands are read normalized
(`vnorm`, impl.inl:2823 — ~11 SSE ops flushing denormal exponents
to signed zero and 0xFF exponents to ±FLT_MAX), and every VF write
maintains **two** representations (raw + normalized shadow,
writeVf :2871). On top of that, each FMAC flavour pays:

| flavour | what it does beyond mul+add | ≈ SSE ops |
|---|---|---|
| fmacFastN (:3586) | plain op; MADD/MSUB adds a product-saturation screen | 2-5 |
| fmacFastV (:3575) | + sticky zero/sign accumulation | 6-11 |
| fmacV (:3471) | fmacCore **range screen**: abs/range compares on result and product to prove the plain result equals the exact VU result, + movemask, + flag folding | 14-18 |
| fmacF (:3443) | fmacCore + full FmacFlags build + pushFmacFlags ring record | 18-24 |
| fmacExact (:3259) | NOINLINE full software semantics — taken only when a screen fails (dynamic rate unmeasured; bounded small by the observed ns/vcyc) | call + dozens |

The translator (vu1_recomp.cpp:50, :1141, :1534) already emits
fmacFastV where a site's flags are provably dead within the
segment, fmacFastN where the image never reads status, and keeps
the exact FMACs only as the screen's fallback. **What remains on
the screened flavours is every site whose flags are folded into
the segment record but still consumed** — in the hot transform
loops, the MADD chains whose MAC flags feed the next clip/branch
test. Site counts (static, all shards): fmacV ≈ 405, fmacF ≈ 125,
fmacFast* ≈ 85, fmacExact 0. The dynamic mix is not directly
logged; the ns/vcyc reconciliation above says the screened path
dominates the hot loops' FMAC cost.

### The per-run fixed stack (the callback layer, 3.2 µs/run)

Named residents of the 14% between runLoopWith and the callback
total, in expected size order (their individual split is **not**
measurable from this log — that is a finding, addressed by L3's
counters):

1. `execute()`'s **resetScheduler()** — memsets of the flag
   pipeline, FDIV/EFU state, store pipeline, three write pipelines,
   and the vf/vi/acc ready + latest-write arrays: ~3.5 KB cleared
   per MSCAL, ~700 MB of memset traffic per heavy window at 200k
   runs.
2. The **capture hook** — on in the shipped app by the glue's own
   setenv: a generation check plus an `unordered_map` increment
   **per run** (ps2_vu1_core.cpp:645-654).
3. run()/runLoop prologue: compiled-program lookup (generation
   compare + cached pointer — cheap), trace/frame-stats null
   checks, MXCSR handling, the EeProfVuRun clock reads.
4. Callback wrapper: std::function dispatch, fbrst read, D/T-bit
   state pokes, finishVu1Program's budget check,
   recordVu1Callback's clock reads and atomics.

## 3. The levers, ranked by expected crowd gain per unit of risk

### L1 — Folded-flag fast FMACs (translator; the headline lever)

**Mechanism.** Today a site whose flags are *folded but read*
still pays fmacCore's range screen on every execution, because
fmacV must guarantee the value is the exact VU result wherever a
later instruction might read the flags that describe it. But the
flag *values* fmacV folds (zero/sign per lane) are computed from
the plain SSE result in both flavours — the screen's only effect
is diverting the rare cancellation case (a MADD/MSUB whose result
cancels to the tiny/zero boundary where product rounding decides)
to fmacExact. fmacFastImpl already accepts exactly that divergence
class for dead-flag sites, with the one observed exception
(product saturation before accumulation) screened separately. L1
extends the translator's site selection: where the dead-flag
analysis shows a site's flags are only *folded into the segment
record* (never read individually before being overwritten or
committed), emit fmacFastV instead of fmacV. The change is in
vu1_recomp's emission choice (the `flagsDead` condition at
vu1_recomp.cpp:1141 widens to a proven `flagsFoldedOnly` class);
the runtime helpers already exist. Regenerate the five images.

**Kill switch (house style, runtime — not just codegen).**
Dual-register like the VU0 precedent (ps2_vu1_core.cpp:571,
PS2X_VU0_FAST): the screened variant of each image is additionally
registered under `hash ^ kVu1ScreenedTag`; lookupCompiledVu1Program
prefers the fast variant unless **PS2X_VU1_FAST_FMAC=0**, which
selects the screened variant = today's behaviour verbatim, in the
same binary. Default ON via the variant seed. Boot line:
`[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)`.

**Predicted effect** (assumptions stated: FMACs ≈ 40-50% of
hot-loop pairs; the screen ≈ 30-40% of a screened FMAC's cost;
≈60% of dynamic FMACs currently screened — the A/B below measures
the true product before any second stage is designed on it):

- ns/vcyc 11.45 → **~10.2-10.8** (−6-11% of runLoopWith time);
- heavy-window callback time 1,593-4,257 → **~1,450-3,850 ms**;
- worst-window join 1,007 ms → **~900-950 ms** at matched join load
  (joins fall because work finishes sooner, not because any wait
  was shortened);
- heavy fields/s **+1-3** in the VU-bound windows (VU 85-89%),
  +0-2 in mid crowds; the 58.7 ceiling may touch 60 in the lighter
  crowd windows. Light windows unchanged (pacing-capped).

**Step 0 of the build stage (measurement before commitment):**
regenerate once with VU1_RECOMP_EXACT_FMAC=1 (screen everywhere —
the existing knob) and bench shipped-vs-exact on the desktop replay
rig (the chained vu1trig replays of round 9). The delta prices the
screen's total dynamic cost; if it comes back under ~3% of run
time, L1's premise is wrong and the stage stops at the report.

**Correctness risks.** The divergence class is real and narrow:
values at the cancellation boundary can differ from the
interpreter by one rounding decision (tiny vs exact zero), and
flags folded from them stay self-consistent (they describe the
value actually produced). Visible failure mode if the game is
sensitive at a hot site: subtly wrong vertex positions/clip
decisions — geometry shimmer, a triangle popping at a specific
camera angle — *not* the round-9 missing-floor mode (that was raw
loads poisoning the norm shadow; L1 does not touch loads,
normalization, or the shadow). Arbitration: PS2X_VU1_VERIFY=obs
soak on the replay rig plus one device session must report 0
mismatches outside the known unobservable-status class before the
build is declared; the owner's eye on crowd scenes is the final
line (§7).

### L2 — Callback-layer diet (lean execute + capture bitset)

**Mechanism.** Two independent cuts at the §2 fixed stack:
(a) *Lean scheduler reset*, mirroring the in-tree PS2X_VU0_LEAN
precedent (impl.inl:2464-2470): `execute()` skips the full
resetScheduler() when the previous run ended clean. This is sound
for VU1 because m_cycle is monotonic across programs in a session
(execute never resets it), so stale ready times are always ≤ now =
already ready, and a clean-ended program has already drained its
flag/EFU/store/write pipelines (flushPipelines + commitFlags/
commitEfu at run end). A dirty bit — set by any budget cut, stop
request, interpreter pair, state load, or reset — forces the full
reset. (b) *Capture bitset*: replace the per-run unordered_map
increment with a per-image 256-bit entry bitset indexed by pc>>3
(the entry set is bounded by the 16 KB code size); the map stays
for the once-per-entry file append, touched only on a bit flip.

**Kill switches.** **PS2X_VU1_LEAN_EXECUTE=0** restores the
unconditional resetScheduler per execute; capture change is
observability-only (the hook's outputs are byte-identical), no
switch. Boot line for the lean state, as with L1.

**Predicted effect.** Recovery of 40-70% of the callback gap:
heavy-window callback time −100-250 ms on top of L1 if stacked
(session execution −4-7%); joins and fields/s move by the same
proportion of the remaining work. No effect on vcyc or on the
runLoopWith figure itself — the signature is the callback−run gap
narrowing from 14% toward ~5-8%.

**Correctness risks.** (a) is the risk-bearing half: if any path
leaves scheduler state the clean-flag misses, the next program
reads stale ready times (too-early reads = wrong values =
arbitrary visual corruption) or stale flag records. Mitigations:
the dirty bit is set conservatively at every non-compiled exit;
the VU0 lean checker's audit mode (impl.inl:2560, "scheduler not
clean on a skipped clear") ports directly and runs in the verify
soak. (b) is risk-free by construction.

### L3 — Job-layer split first, then the copy (instrument, then cut)

**Mechanism.** The job layer (~100-350 ms/heavy window) has no
internal split in any current instrument. This stage adds
counters-only timers inside the worker's `run()` (ps2_async_vif.cpp)
— VIF process time, arbiter-drain time, publishGif time, per window,
printed beside [ee-work] — and designs **no** job-layer fix until
the split exists. The expected largest resident, on the source
evidence, is the XGKICK byte path: `submitGif` memcpy's every
PATH1 packet into 256 KB chunks on the worker (peak ~514 MB/window
of GIF traffic ⇒ ~25-50 ms/window of pure copy at mobile bandwidth)
plus the job-end arbiter drain. If the split confirms it, the
follow-up lever is a move-instead-of-copy packet handoff
(buffer ownership passes to the chunk; the spare-buffer recycling
already in run()/takeBuffer is the pattern), behind
**PS2X_GS_GIF_MOVE=0** restoring the copy. If the split convicts
UNPACK or the drain instead, the follow-up is designed against
that, not against this guess.

**Predicted effect.** Bounded by the layer: −0-350 ms/window,
realistically −50-120 if the copy share is as estimated (2-4% of
job time). **Risks.** The counters: none (house counters
discipline). The eventual copy change: GIF packet ordering is
load-bearing (chunks replay in job order); a botched handoff
shows as corrupted geometry/textures — caught by the same verify
soak + owner eye. It is deliberately *not* in the first build.

### Recommended first build

**L1 alone** (translator change + regenerated images + dual
registration + boot line), with L3's counters riding along —
counters change no behaviour, so the build still has exactly one
behavioural variable, per house discipline. L2 is the second
build, arbitrated on its own gap signature. This stage changes
**libgodhand.so only**: the VU code lives in the core, so m31's
"core byte-identical" discipline inverts — the bridge
libpcsx2gs.so must come out byte-identical to m31's
(md5 0fc367db8b63cd733d1bccd8c19b8574), the shell/dex/resources
identical, and the core's delta confined to the generated vu1
objects + the lookup/registration seam. The variant seeds
(forced-pcsx2 renderer, pipeline depth 2, VU1 async) live in glue
this stage does not touch and must be re-proven by the boot lines,
exactly as PATH-PRESENT-BUILD required. No step requires Pablo to
edit a settings file: both switches are reachable through the
shipped ini/seed mechanism like every previous stage's.

## 4. Separation — what helps what, and what not to do

**Helps the steady crowd ceiling:** L1 (the run itself), L2 (the
fixed stack around every run), L3's eventual fix (job-layer serial
time delays every job's completion identically). All three attack
worker-busy milliseconds, which is the only currency the joins
accept.

**Helps only the fight-entry consume blocks:** nothing in L1-L3,
and this report will not pretend otherwise. The consume parks
(153.4 ms/6 and 265.3 ms/9 in the two entry windows, ~25-30 ms
each) are **not** a VU symptom: `parked: consume` times
`GSThreadedBackend::ConsumeLocalToHostBytes` drains
(gs_threaded_backend.cpp:1324; gs_coupling_stats.h:16, "the
readback drain") — the host thread waiting on the GS worker, whose
queue at scene entry is chewing the asset burst (the same two
windows carry the session's only queueWait avg spikes, 5.61/2.90 ms
vs ≤1.41 steady). What the guest reads back at entry, and how many
bytes, is not in this log. The entry lever for this stage is
therefore **instrumentation only**: byte counts and queued-ahead
depth per Consume drain, printed with [ee-work], so the next stage
can tell a guest data readback (semantics: cannot be bounded
without changing what the guest sees) from frame-consumption
queueing (potentially boundable like the vblank barrier was). L1
may shave the VU share of the burst marginally; the arbitration
lines (§7) treat consume as **expected unchanged**, and any
collapse must be explained before it is credited.

**Recommended NOT doing, with the evidence:**

- **Join-frequency reduction at the barrier/idle sites.** Priced:
  the worst window's 2,353 waits total 1,007 ms of *unfinished
  work*; the per-touch overhead (mutex + cv + JoinTimer atomics)
  at a generous 1-3 µs × 2,353 ≈ 2-7 ms/window, ≤0.7%. m30 already
  took this site's semantic win (the bound); what remains is work,
  and coarser completion granularity cannot manufacture it away —
  the guest's own polls would simply re-discover the same
  unfinished work at their drain sites (regread/vumem are 0.0
  today precisely because the barrier absorbs the wait first).
- **GIF-volume reduction.** The GS side idles 63-72% in the same
  windows; upload dedup already exists (ARUS pillar 1); the
  remaining ~2.12 MB/field is geometry streaming that exists to
  feed VU1. The only GIF cost on the critical path is the worker's
  copy/drain, already counted in L3. There is no independent wall
  here to attack.
- **A second VU worker.** VU1 is one serial state machine: jobs
  consume the previous jobs' VU memory and register state (MSCNT
  resumes mid-program state), and the FIFO + completion watermark
  are what the barrier's correctness rests on. queued ≈ run in
  every window — there is no backlog to parallelize, only a slow
  producer to speed up.
- **Raw loads / skipping vnorm.** Proven harmful *in this game*:
  the round-9 incident (vu1_recomp.cpp:37-48) — integer bit
  patterns in VF registers (0xFFFFFF8F…) read as NaN instead of
  ±FLT_MAX, the norm shadow carried the poison across programs,
  and floor/walls near the camera vanished. Any future proposal
  that touches load normalization starts from that counterexample.
- **Raising the slice budget / chasing vcyc.** Budget cuts are 0;
  vcyc is a guest-timing quantity (it includes hardware stall
  cycles that cost ~nothing to emulate), not a cost driver. The
  cost drivers are pairs executed × per-pair cost + fixed costs.
- **Thread affinity / priority games.** VUThread already runs at
  the highest priority class; pinning cores on the owner's phone
  is an unmeasurable a priori thermal/scheduler experiment. Not a
  build; at most a later A/B with the same log as arbiter.

## 5. Honest risks

- **L1's premise rests on an unmeasured dynamic mix.** The static
  site counts and the ns/vcyc reconciliation agree that screened
  FMACs dominate, but the exact share is estimated, not logged.
  That is why the build starts at step 0 (the EXACT_FMAC A/B on
  the replay rig) and why §7's first line is ns/vcyc itself: if
  the screen is worth <3%, this design's headline is wrong and the
  log will say so in one number.
- **L1 trades an exactness class the game may or may not feel.**
  The divergence is confined to cancellation-boundary values, the
  verify harness arbitrates mechanically, and the kill switch is
  one ini line in the same binary — but a subtle clip difference
  in one untested scene is possible in principle. The soak must
  include crowd scenes, not only the replay fixtures.
- **L2's lean reset is a correctness-by-invariant change.** The
  invariant (clean end ⇒ drained pipelines + stale-but-past ready
  times) is argued from the code, and the VU0 precedent + its
  audit checker exist — but VU1's exit paths are more numerous
  (XGKICK state, clip ring, EFU). If the audit checker reports a
  single dirty skip in the soak, L2 does not ship.
- **Session variance.** m31's session was heavier than m30's
  (34/37 heavy windows); all predictions are therefore stated
  against matched join load and per-vcyc rates, not bare window
  averages, and §7 keeps that discipline.

## 6. Evidence base

- M31-LOG-ANALYSIS.md (the commissioning analysis): crowd profile,
  join split (worst window 1,007.4 ms = barrier 492.4 + idle 511.9
  + gspriv 3.1 — recomputed from the log's [vu1] lines for this
  report), consume-park class, closed list.
- The m31 device return itself: godhand-boot.log last session
  (37 windows) — all §1/§2 aggregates recomputed from its
  [ee-prof], [ee-prof:top] and [ee-work] lines; vu-capture/
  (5 images byte-compared, entries.txt cycle structure).
- Source trace as cited: ps2_vu1_core.cpp (capture :621, lookup
  :515, run :1349, runLoopWith :1406, resetScheduler),
  ps2_vu1_impl.inl (runFast :3914, stepPair :3968, vnorm :2823,
  writeVf :2871, fmac family :3259-3599, Vu1FastCtx :2430),
  vu1_recomp.cpp (:37-66 knobs and the round-9 note, :830, :1141,
  :1422, :1534), generated/vu1/ (registration, seg_00B0,
  seg_0000, pc_0520, pc_0358/pc_0368), ps2_async_vif.cpp (worker
  :316, enqueue, JoinTimer :175, waitPipelineIdle/Bounded),
  ps2_runtime.cpp (MSCAL callback :1140, finishVu1Program :1082,
  recordVuRun :2135), EeScheduler.cpp (print :456-545, barrier
  :2963, catch-up :728), gs_threaded_backend.cpp:1324,
  godhand_android_main.cpp:691.

No build was made for this report; the report-first gate applies
and the next build is commissioned against §7.

## 7. The lines that arbitrate a future device run

From the throughput build's log, last session, in order. Config
proof first — if these lines are absent or wrong, the run measures
nothing:

0. **Config proof:** the m31 lines must all fire again —
   `env: PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`,
   `[pcsx2-gs] pipeline on: ... depth 2 fields`,
   `[pcsx2-gs] harvest fastpath: on` — plus this stage's:
   `[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)`
   and `[vu1-aot] 5 compiled VU program(s) registered`.
1. **`[ee-prof]` vu1 ns/vcyc** (vuRunNs ÷ vuCycles, heavy
   windows): target ≤ ~10.8 (m31: 11.45 session, ~10.5 in the
   single heaviest windows — judge on matched windows, not the
   aggregate). This is L1's direct signature; a result ≥11.2
   falsifies the screen-cost premise.
2. **`[ee-prof:top]` vu1 programs**: pc 0x0520 and pc 0x0358
   µs/call — targets ≤ ~19.5 and ≤ ~20 (m31: 22.0, 22.3); their
   combined share of run time should stay ~78% (same work, cheaper)
   — a share collapse means the workload moved, not that it sped
   up.
3. **`[ee-prof]` vu1worker − vu1 gap**: expected ~unchanged by L1
   (~14%, 3.2 µs/run; m31 session 14,543 ms). It is L2's line, kept
   here so L1 is not credited or blamed for it.
4. **`[vu1]` joins at matched VU load**: worst-window total target
   ≤ ~950 ms (m31: 1,007.4 = barrier 492.4 + idle 511.9), with the
   fall proportional to the vu1worker fall. Joins falling while
   vu1worker ms/vcyc is unchanged falsifies the throughput model
   and must be explained before anything is credited.
5. **`[ee-work]` heavy fields/s**: target floor ≥ ~52 in
   matched-join windows, mid-crowd windows touching 60 (m31:
   47.8-58.7, ceiling untouched). Light windows stay 59.8-60.0 —
   any light-window regression is a fail, not a trade.
6. **Fast-path integrity**: interp 0, fb 0, budget 0 in every
   window (m31: all zero). Any nonzero fb means the codegen change
   broke runFast's entry conditions; any interpreted run means an
   image lost its registration.
7. **Correctness soak (before the device run is judged)**:
   PS2X_VU1_VERIFY=obs on the replay rig + the device session: 0
   mismatches outside the unobservable-status class; and in the
   owner's scenes specifically — no missing floor/walls near the
   camera (the round-9 signature), no new triangle popping or
   geometry shimmer in crowds, character models intact.
8. **Entry windows**: `parked: consume` in the first two heavy
   windows — expected **unchanged** (m31: 6/153.4 ms, 9/265.3 ms);
   the new consume byte/depth counters must appear. A collapse
   here is a surprise to be explained, not a win to be claimed.
9. **Kill-switch A/B (same binary, external ini)**:
   PS2X_VU1_FAST_FMAC=0 must reproduce the m31 bands on lines 1-2
   (ns/vcyc back to ~11.45, µs/call back to ~22) — proving the
   switch selects the screened variant and the delta is the FMAC
   flavour, nothing else.
10. **Owner feel, as always, outranks every line above**: crowds
    should feel smoother at the same scenes and the audio texture
    with them; any new visual defect he can see is a fail
    regardless of the numbers.
