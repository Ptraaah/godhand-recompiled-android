# m36 device log analysis — PARTIAL: residency paid well past its own prediction, missed the success line by 0.10 µs/run; the front stays open

**Session: the last session in `device-logs/2026-11-07-m36/files/godhand-boot.log`
(lines 14432–15229 of the appended file), booted 2026-11-07 12:01:27 WITA.
Build under test: 0.37-m36-resident (versionCode 44) — m35 plus the
PATH-RESIDENT experiment (M36-REPORT.md). Owner's verdict: still laggy
and frame-dropping, audio still very choppy — and, notably, no "better"
this time, unlike m35. The log explains both halves of that: the worker
got much cheaper per invocation, and the session he happened to play
never once reached the 60-fields cadence.**

## Gate verdict (pre-registered in M36-REPORT §4 — applied exactly, not moved)

- **Kill gate: NOT hit.** It required residue within ±0.15 µs/run of
  3.333 (band 3.183–3.483) **and** total callback within ±2 % of 21.90
  µs/callback (band 21.46–22.34) at comparable crowd windows. Measured:
  residue **2.604** pooled / **2.618** crowd-pooled; callback **16.26**
  pooled / **16.00** crowd-pooled. Both terms are far outside the kill
  band, in the improving direction. The residency front does **not**
  close, and the residue is **not** accepted as a fixed cost of the
  async architecture.
- **Success gate: NOT met on its strict letter.** It required residue
  ≤ 2.5 µs/run with conservation intact and the crowd fields/s floor
  above 42.3. Conservation intact ✓; floor **48.2** > 42.3 ✓; but
  residue **2.604** pooled (2.618 crowd-pooled, per-window median 2.57)
  misses the 2.5 line by **0.104 µs/run (~4 %)**. Residency therefore
  does not become the unconditional default by the gate's own terms —
  see the follow-up in §6, which keeps it on (the kill switch remains
  the owner's revert) and aims the next build at the 0.10 that is left.
- **Automatic fail: none.** dropped_captures = 0 in every pipeline
  window; missfn 0, interp 0 / budget 0 / fb 0 across all gameplay;
  conservation (pre + post ≤ gap) holds in all 27 gameplay windows,
  zero violations; the owner reports no new missing geometry or visual
  corruption. ADMA never starved (all [stall] summaries 0 late /
  0 starved).

**Headline numbers (gameplay pooled, cb = 4,092,757): residue 3.333 →
2.604 µs/run (−21.9 %); gap 3.524 → 2.779; total callback 21.90 →
16.26 µs/callback (−25.7 %). Crowd-pooled (fields/s < 50, 8 windows
each): residue 3.505 → 2.618 (−25.3 %); callback 22.80 → 16.00
(−29.8 %). The improvement is roughly six times larger than the
experiment's own prediction for its target term — §3 explains why, and
what part of it the log cannot fully arbitrate.**

---

## 1. Session identification and shape

- Boot banner `[2026-11-07 12:01:27.357] GameActivity onCreate` →
  `===== godhand android 0.19-m18 boot =====` at line 14444 (shell
  version; the core is identified by its instruments, below).
- **m36 proof:** the session carries one `[vu-resident]` line (line
  14646) — a string that exists only in the m36 core — plus the
  m34-rider segment `gap %.1f ms (pre %.1f post %.1f, cb %llu)` in all
  27 gameplay [ee-prof] windows, and the path-B variant config lines
  (`PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`,
  `PS2X_GS_SCALE=2`, `PS2X_THREAD_POLICY=off`, fast FMAC and lean
  execute on via the variant defaults). The device ini did not poison
  the test. PS2X_VU1_RESIDENT appears nowhere in the env dump, so the
  resident layout ran at its shipped default (on).
- **Pin check (required by the brief): ENGAGED.** Line 14646:
  `[vu-resident] VUThread pinned to cpu 7` — the highest-index allowed
  CPU, the prime core on this SoC. Not the fail-soft unpinned line.
  Parts 1+2 are not separately bannered (by design); the arena and
  prefetch are in the same shipped core the packaging arbitration
  verified (M36-REPORT §5).
- Shape: **30 profile windows ≈ 123 s of guest time; 6,912 fields by
  [ee-work] (gameplay), 6,083 presents by [frame-stats].** Windows 1–3
  are boot/menu (cb = 0). **Gameplay = the 27 windows with cb > 0.**
- **Comparability warning:** this session is about half m35's length
  (27 gameplay windows vs 52; 6,912 fields vs 13,800) and
  compositionally different in an important way: it is *uniformly
  mid-weight* — every gameplay window sits between 48.2 and 54.0
  fields/s. m35's session mixed 8 genuinely heavy windows (floor 42.3)
  with light stretches up to 58.7 fields/s; m36's has neither the deep
  crowd nor the light relief. Per-invocation rates (µs/run,
  µs/callback) are compared head-to-head as usual, and the crowd
  subsets (fields/s < 50, 8 windows in each session) are pooled
  separately wherever a gate says "comparable crowd windows"; raw
  fields/s averages and frame-time averages carry the mix caveat.

## 2. The decomposition, m36 vs m35 (pooled gameplay)

Method validation: the parser run over the m35 session in this same
appended file reproduces the published M35-LOG-ANALYSIS pooled numbers
exactly (cb 7,203,522; worker 157,752.7 ms; gap 3.524; residue 3.333;
callback 21.90). The m36 numbers below are the same arithmetic.

m36 pool: cb = 4,092,757 worker runs; vu1worker total 66,529.6 ms;
vu1 total 55,156.9 ms (82.9 % of worker; m35: 83.9 %).

| component | m36 ms | m36 µs/run | m35 µs/run | Δ |
|---|---|---|---|---|
| pre (callback entry → timer start) | 372.6 | 0.091 | 0.101 | −0.010 |
| post (execute return → finish return) | 341.8 | 0.084 | 0.089 | −0.005 |
| **residue (timed span, outside the program)** | **10,658.2** | **2.604** | **3.333** | **−0.729 (−21.9 %)** |
| gap total | 11,372.6 | 2.779 | 3.524 | −0.745 (−21.1 %) |
| vu1worker total per callback | 66,529.6 | 16.26 | 21.90 | −5.64 (−25.7 %) |
| vu1 program time per run | 55,156.9 | 13.48 | 18.38 | −4.90 (−26.7 %) |

- **Conservation: pre + post ≤ gap in all 27 gameplay windows, zero
  violations** (also in all 30 windows overall). The split is real.
- Per-window residue: 1.99–4.14 µs/run, median 2.57 (m35: 2.04–4.62,
  median 3.27). The whole distribution shifted down; the top of the
  range is one light window (54.0 fields/s, residue 3.67) where the
  thread does the most non-VU work between invocations — the re-entry
  signature behaving as expected, at a lower price.
- Gap share of worker *rose* slightly (17.09 % vs 16.09 %) — a share,
  not a rate: the program time fell even faster than the gap did, so
  the gap is a larger slice of a much smaller callback. The gates
  judge rates, and every rate fell.
- pre and post remain dead as suspects (0.175 µs/run combined, 6.3 %
  of the gap).

## 3. Why the gain is bigger than the experiment predicted — and the honest caveat

The M36 report priced the bet narrowly: if residency hit its target,
~0.8 µs/run off the callback (~4 %). The measured callback fell
**5.64 µs/run**, and most of that fall is *not* in the gap — it is in
the vu1 program time itself: **10.23 → 7.70 ns per VU cycle (−24.7 %)**
at essentially unchanged work per invocation (1,751 vs 1,796 vcyc/run;
the same two wall images dominate both sessions' program mix —
0x0358-cd8f at 20.17 → 14.26 µs/call and 0x0520-2cfd at 16.63 → 12.31
µs/call in the pooled top-lists).

The mechanism the log supports: parts 1–2 (arena, prefetch) price the
gap, and the gap did fall by roughly the predicted amount and more
(−0.73 µs/run of residue). Part 3 (the pin) prices something the
earlier analyses never isolated: in m35 the unpinned VUThread woke
wherever Android's scheduler placed it, including slower cores and the
other cluster — where the *program itself* then ran at a higher
ns/vcyc. Pinned to cpu 7, the prime core, the worker ran the same
cycles at the fastest core's price for the entire session. A
whole-worker speedup of this shape — program time and residue falling
together, vcyc/run unchanged — is exactly what "the thread stopped
migrating to slower cores" predicts.

The caveat, stated plainly: the log cannot separate "the pin kept the
thread on the prime core" from "this session's device state (thermals,
background load, governor behaviour) was kinder" with certainty. Two
things argue for the pin being the main term: the effect is uniform
across all 27 windows and both program walls (a thermal story usually
degrades *within* a session, and this session's late windows are as
fast as its early ones — crowd windows 18–21 sit at callback
15.4–15.8 µs), and the [vu-resident] line proves the pin engaged
before gameplay started. But the honest label for the −25.7 % callback
figure is **measured, mechanism strongly suggested, not isolated** —
one session, no A/B. The residue figure (−21.9 %) is the term the
experiment was designed to move, and it moved in both pools and in the
per-window median.

## 4. The pre-named pin risk: EE-side inflation — NOT observed

M36-REPORT §3 named the risk in advance: pinning the VUThread to the
prime core puts it beside the game thread's preferred core, and the
tell would be EE-side inflation — native ms per window up at equal
fields. Measured native/guest: **0.813 pooled** (m35: 0.832);
**crowd-pooled 0.811** (m35 crowd: 0.792). Per crowd window the ratio
spans 0.65–0.90 in both sessions with no systematic shift; the pooled
figure is slightly *down*, the crowd figure up by 0.019 — inside the
mix noise of two differently-composed sessions, and nowhere near an
inflation signature (the report's remedy — moving the pin to the
second performance core — is therefore **not** triggered). The EE was
not taxed to pay for the VU's gain, as far as one session can show.

## 5. Feel metrics vs m35 — and why the owner did not say "better"

- **Fields/s (gameplay, n = 27):** floor **48.2**, ceiling **54.0**,
  median **50.9**, mean 51.1. m35 (n = 52): 42.3 / 58.7 / 53.6 / 53.0.
  The floor rose **+5.9** — his worst moments got materially less bad.
  But the ceiling *fell* 4.7 and the median 2.7, because this session
  had no light stretches at all: every single window ran under the 60
  cadence. In m35's session he got near-60 relief between crowds; in
  this one he did not.
- **Frame-stats (per-process final):** presents 6,083, avg frame
  **19.7 ms** (m35 session: 18.7), worst **1,269 ms** (923). Bands:
  ≤16.7 ms 41.2 %, ≤25 ms 37.4 %, ≤33 ms 19.5 %, ≤50 ms 1.6 %,
  ≤100 ms 0.2 %, >100 ms 0.08 % (m35: 38.9 / 50.7 / 9.5 / 0.55 / 0.23 /
  0.05). The >50 ms tail shrank; the mass moved into the 25–33 ms
  band — the fingerprint of a session parked at ~50 fields/s rather
  than m35's bimodal heavy/light mix. The avg-frame and worst-frame
  figures are mix artefacts (the 1,269 ms worst coincides with the
  session's 1,269.7 ms memslow load spike), not a regression: at the
  window level the worker was cheaper everywhere.
- **Joins:** per-window join_wait median **36.3 ms** (m35 gameplay
  median 218.8), worst window 312.5 ms — again mix-flavoured (shorter
  session, fewer stage loads), but the direction matches a worker that
  finishes each job sooner: the EE waits less because there is less to
  wait for.
- **Memslow:** pooled 8,515.2 ms over 27 windows (13 windows >100 ms,
  max 1,269.7 ms) — proportionally heavier than m35's session (10,532
  over 52), tracking area streaming in this session's areas. It still
  does not price the floor: the floor window (48.2 fields/s) has
  memslow 10.3 ms, and two windows with >870 ms of memslow run at
  49.0–49.6 fields/s.
- **Audio:** the owner's "suara masih patah patah banget" stands
  unrefuted and is consistent with the log: ADMA never starves at the
  50 ms granularity the [stall] summary measures, but a session whose
  *every* window runs 48–54 fields/s against a 60-fields cadence has
  constant field-time judder for the audio path to hear. m35 gave him
  near-cadence stretches where the choppiness would ease; this session
  gave him none. Audio remains a speed symptom: it improves when the
  *typical* window approaches 60, not only when the floor rises.
- **Reconciliation with the owner's verdict:** his m35 "better" and
  his m36 silence are both consistent with the numbers. m35's gain was
  spread across a session that included relief stretches, so the
  improvement was *felt*. m36's larger gain landed in a uniformly
  sub-cadence session: the worst got better, the typical stayed
  visibly short of smooth, and nothing about 50 fields/s feels like a
  fix. The rates say the build worked; the session he played it in
  could not show him a transformation, because at 48–54 fields/s there
  isn't one yet.

## 6. Follow-up design (the report's success follow-up, adapted to a partial verdict)

The kill gate did not fire, so the mandated call-count attack is
**not** this build's prescription — it stays on the table (§7). The
M36-REPORT §4 success follow-up is, with one adaptation: residency
stays on as the shipped default under its existing kill switch (the
gate withheld the unconditional-default label over 0.104 µs/run; the
measured −21.9 % residue and −25.7 % callback do not justify reverting
a working, fail-soft, bench-neutral change while the follow-up aims at
exactly that remainder).

**PATH-RESIDENT-2 — image-pair prefetch at the funnel mouth.** What
the working set does inside a job, from this session's numbers:
~390 invocations per job (m35: ~345) alternating between the two wall
images — 0x0358-cd8f (stage) and 0x0520 in its two images (character) —
which together are ~78 % of all run time, plus the smaller 0x2378 /
0x0000 / 0x00c8 entries of the stage image. The m36 prefetch warms the
interpreter state, the *entry* image's code line, and the data base —
the image about to run. Between two invocations of the same wall, the
other wall runs (~13–14 µs), the VIF packet processing runs, and the
first wall's lines are the coldest thing in the timed span when its
turn comes again. PATH-RESIDENT-2 widens the funnel-mouth prefetch set
from "the image about to run" to "the working set of the job": both
wall images' entry-PC lines and data-image heads (the arena makes both
images' addresses known constants relative to the arena base — the
prefetch targets are computable without a registry lookup), issued at
the same head-of-runFused point. Expected mechanism: the refill for
the *alternating* image starts one invocation early, overlapping the
current program's execution instead of stalling the next invocation's
prologue; the residue's remaining 2.6 µs/run is the only term it can
move, and the distance to the success line is 0.104.

- **Scope discipline:** prefetch-set change only — no arena change, no
  pin change, no program change. Same kill switch family
  (PS2X_VU1_RESIDENT=0 still reverts all of residency; the pair
  prefetch rides with part 2 as it does today).
- **Bench gates:** identical to m36's — per-image fixture arbitration
  must reproduce m35/m36 digests and cycle counts exactly in every
  on/off combination (prefetch is side-effect-free by construction;
  any divergence is an automatic fail), arena test re-run unchanged.
- **Pre-registered device gates (fixed now, against this report's m36
  pooled baselines: residue 2.604, gap 2.779, callback 16.26, crowd
  floor 48.2):** success = residue ≤ 2.5 µs/run pooled **and**
  crowd-pooled, with conservation intact and crowd floor ≥ 48.2 — the
  line this build missed by 0.104, re-registered once because the
  front is still open by the kill gate's own arithmetic, not moved
  after any log. Kill = residue within ±0.15 of 2.604 **and** callback
  within ±2 % of 16.26 at comparable crowd windows → the prefetch
  tuning is exhausted; residency stays as-is (it earned its keep) and
  the call-count attack (§7) becomes the front.
- **Honest expectation:** this is a 0.1–0.3 µs/run hunt, worth ~1–2 %
  of callback if it lands — a gate-closing build, not a feel build.
  Nobody should promise the owner a perceptible change from it; its
  value is finishing the residency question so the next real front is
  chosen on closed books.

## 7. The remaining lever, kept on the table (not this build's prescription)

The residue still scales with invocation count: ~592 invocations per
field in this session (m35: ~522), each paying pre + post + residue ≈
2.78 µs of gap. If the §6 kill gate ever fires, the call-count attack
is the front, and its shape in this codebase is already visible from
the logs: the walls are not called once per object — the MSCAL/MSCNT
callback fires per VIF packet segment (~390 per job), so consecutive
invocations of the same entry with only packet-processing between them
are the batching candidates (coalescing same-entry continuations at
the callback layer in `ps2_runtime.cpp`'s onWorker path, without
changing a single VU cycle). Its risk class is different — it touches
*when* work is submitted, not where memory sits — which is exactly why
it waits behind a gate instead of riding along now.
