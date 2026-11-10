# m34 device log analysis — the gap lives in the residue, and the residue is per-invocation re-entry

**Session: the last session in `device-logs/2026-11-07-m34/files/godhand-boot.log`
(lines 12542–13246 of the appended file), booted 2026-11-07 05:04:43 WITA.
Build: 0.35-m34-rider (versionCode 42) — m33 plus the gap-decomposition rider
(M34-REPORT.md). One question was asked of this return: where does the 15.0 %
vu1worker-minus-vu1 gap live — before the run, after the run, or inside
execute()'s own funnel? It is answered, and the answer is not the one any of
the m33-era hypotheses wanted.**

- **The split (gameplay windows 6–23, pooled): gap 9,392.2 ms = pre 264.4 ms
  (2.8 %) + post 234.2 ms (2.5 %) + residue 8,893.6 ms (94.7 %).** Per run:
  gap 3.93 µs = pre 0.111 + post 0.098 + **residue 3.72 µs**. The callback's
  own setup (pre) and the finishVu1Program return path (post) are rounding
  errors. The gap is inside the timed span, outside the generated program:
  the execute()/resume() → run() → runLoop() → runLoopWith() funnel that
  wraps every one of the session's 2,390,007 worker-thread invocations.
- **Conservation holds everywhere:** pre + post ≤ gap in all 23 windows, zero
  violations — the rider's arithmetic is sound and the split is real.
- **The gap did not shrink; it held its share and grew its rate.** Gap =
  16.05 % of worker time (m33: 15.0 %), 3.93 µs/run (m33: 3.26). This session
  is heavier per call than m33's (worker 24.49 µs/run vs 21.78; VU 53–86 % of
  ee-work vs m33's lighter mix), so the rates are not like-for-like — but the
  share is the invariant, and the share survived a third build.
- **Outcomes:** gameplay fields/s floor **40.7**, ceiling **53.2**, median
  **49.8**, stdev 3.05 (m33: 41.8 / 56.7 / 53.1 / 4.83 — heavier session, see
  Line 5). Worst join **719.9 ms** (m33: 938.9), median gameplay join 356.6 ms
  (m33: 413.1), barrier + idle = 99.3 % of all join time, regread 0.0.
- **Correctness clean:** dropped_captures = 0 in every pipeline window,
  missfn 0, interp 0 / fallback 0 / budget 0 across all gameplay — every run
  is a compiled program running to its end. ADMA never starved (every [stall]
  summary 0 late / 0 starved / 0.0 ms). No missing-geometry signal anywhere.
- **The late-session inflation does not reproduce.** Second-half fields/s
  mean (49.3) ≥ first-half (48.3); ns/vcyc is flat and the last windows are
  the session's *best* (10.21–10.81). The m33 session's two-half shape was
  its own composition, not a machine-state leak. That target is demoted.

---

## Session identification

- Boot banner `[2026-11-07 05:04:43.699] super.onCreate()` → `===== godhand
  android 0.19-m18 boot =====` (shell version; the core is identified below).
- **m34 proof:** all 23 [ee-prof] windows carry the rider segment
  `gap %.1f ms (pre %.1f post %.1f, cb %llu)` — only m34 prints it. Config
  lines as required: `native: path B variant: PS2X_GS_RENDERER=pcsx2`,
  `PS2X_VU1_FAST_FMAC=1`, `PS2X_VU1_LEAN_EXECUTE=1`; BOOT env
  `PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`;
  `[vu1-aot] fmac: fast`, `[vu1-aot] lean execute: on`. The device ini could
  not and did not poison the test.
- Session shell settings differ from m33's return and are recorded for
  comparability: `scale=2` (m33 session ran the ini/env scale line at 0.5 in
  its boot block), `threads=off` (`PS2X_THREAD_POLICY=off`), vsync on,
  widescreen on. Feel-metric comparisons below carry that caveat.
- Shape: 23 profile windows ≈ 100 s of guest time; 5,790 fields by [ee-work],
  5,696 presents by [frame-stats]. Windows 1–5 are boot/menu/load (cb = 0,
  VU 1 % — the VU ran on the EE thread or not at all; the disc-load memslow
  storms live there). Window 6 is a partial ramp (worker 709.3 ms).
  **Gameplay = windows 6–23 (n = 18)** throughout this analysis.
- Program mix (final window's [ee-prof:top], representative): 0x0358-cd8f
  24.76 µs/call; 0x0520-4dfe 17.77 µs/call; 0x0520-2cfd 17.56 µs/call —
  the two known walls, at their known prices.

## Line 1 — the decomposition (the build's only question): ANSWERED

Pooled over gameplay (cb = 2,390,007 worker runs; worker total 58,526.2 ms;
vu total 49,134.0 ms = 84.0 % of worker):

| component | ms | % of gap | µs/run |
|---|---|---|---|
| pre (callback entry → vu1worker timer start) | 264.4 | 2.8 % | 0.111 |
| post (execute return → finishVu1Program return) | 234.2 | 2.5 % | 0.098 |
| **residue (inside the timed span, outside the program)** | **8,893.6** | **94.7 %** | **3.72** |
| gap total | 9,392.2 | 100 % | 3.93 |

Per-window residue: 3.12–5.03 µs/run (window 12, the floor window, is worst
at 5.03; the partial window 6 reads 5.06). Gap share per window: 13.6–21.4 %.
The parent read of the last windows (gap 387–633 ms per ~4.6 s window, pre
~12–20 ms, post ~11–19 ms) verifies across the whole session — windows 7–23
sit exactly in that band (gap 355.1–708.1 ms per window).

**What this kills:**
- *Callback-setup cost* (the onWorker check, context fetch, fbrst fetch,
  d/t-bit writes): 0.111 µs/run. Dead.
- *XGKICK processing / post-run notification in finishVu1Program*:
  0.098 µs/run. Dead.
- *The scheduler reset as the residue:* every gameplay run reports
  interp 0, budget 0, fb 0 — every run ends clean, so `m_vu1LeanClean`
  re-arms after essentially every run and the lean skip is the norm;
  resetScheduler()'s ~13 memsets are the cold path, not a per-run tax.
- *The registry hash:* lookupCompiledVu1Program is generation-cached
  (pointer + generation compares; findCompiledVu1Image runs only on a code
  generation change). There is no per-run 16 KB hash anywhere in the funnel —
  the one hash that instrumentation needed was already cached in m33.

**What this confirms:** the m33 premise failure was structural, not a matter
of degree. The gap is *per-invocation* overhead — proportional to call
count, indifferent to which fixed costs get dieted — which is exactly why
m32's and m33's diets each left its share standing at 15–17 %.

## Line 2 — what the residue is made of (code reading, ps2_vu1_core.cpp)

The residue bracket is: execute()/resume() prologue → run() → runLoop() →
runLoopWith() up to EeProfVuRun's t0, plus EeProfVuRun's destructor and the
return path back to the callback's T2 stamp. The identifiable instruction
work in that bracket, priced generously:

- execute(): lean-mode gate + ~20 state stores + vf[0] reset. resume() is
  lighter still (~5 stores). The lean Check-mode audit loop does **not** run
  (mode is On, not Check).
- captureVu1Program(): generation load + compare, a linear scan of the
  capture entry-bitset + one relaxed atomic fetch_or. (Capture is enabled on
  this device; first-entry fopen happens once per (image, entry) pair.)
- traceVu1Run(): one atomic exchange, normally 0. vu1FrameStatsBegin():
  nullptr on this device (PS2X_VU1_FRAMESTATS unset) after a static load.
- lookupCompiledVu1Program(): singleton + generation compare (above).
- runLoopWith() setup: four pointer stores, _mm_getcsr, budget arithmetic;
  EeProfVuRun ctor/dtor: two steady_clock reads + ~11 relaxed atomic adds
  (recordVuRun) + the cached-hash compare.
- Epilogue: flushPipelines() on program end, CSR restore, lean re-arm store.

Summed with a straight face: **well under 1 µs/run** — against a measured
3.72 µs/run. The remainder does not correspond to instructions in the
funnel. It is most consistent with the per-invocation *re-entry* cost of
the VU machine on a shared worker thread: between calls, that thread runs
VIF/GIF work, and each invocation re-touches cold cache/TLB state — the
interpreter object and its scheduler/decode structures, the 16 KB code and
16 KB data images, and the generated code itself. The prologue is simply
where the cold lines get paid for, because the vu timer starts after it.
This also explains the residue's window-to-window spread (3.12–5.03 µs/run)
tracking how much other work the thread did between calls, and why it
grows in the heaviest window (12) rather than with any single program's
cost. Stated as what it is: the best-supported reading, not a direct
measurement — see the m35 section for why no further counter is warranted.

## Line 3 — feel metrics vs m33

- **Fields/s (gameplay, n = 18):** floor **40.7** (window 12, VU 68 %),
  ceiling **53.2** (window 6), median **49.8**, mean 48.9, pstdev **3.05**.
  Against m33 (41.8 / 56.7 / 53.1 / 4.83): the floor is unchanged within
  noise, the spread *narrowed*, the median is lower — in a session whose
  windows run VU 53–86 % (m33's gameplay included a light interlude at
  42–47 %; this session has none). Not like-for-like; no regression is
  claimed and none is hid
...[truncated 7210 chars]