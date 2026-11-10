# m37 device log analysis — PARTIAL: neither gate fires; the session's whole-worker price level drifted +11–14 %, swamping the 0.1–0.3 µs/run the build was hunting, so the prefetch question is gate-inconclusive and residency tuning closes administratively, not by gate

**Session: the last session in `device-logs/2026-11-08-m37/files/godhand-boot.log`
(lines 15878–16654 of the appended file), booted 2026-11-08 01:41:42 WITA.
Build under test: 0.38-m37-resident2 (versionCode 45) — m36 plus
PATH-RESIDENT-2, the image-pair prefetch at the funnel mouth
(M37-REPORT.md). Owner's verdict: still laggy and frame-dropping,
audio still very choppy — and again no "better". The log agrees with
him completely: this session's typical window was slower than m36's
typical window. What the log does *not* support is blaming the
prefetch change for that: every measured rate in the session —
including two components the change has no mechanism to touch —
inflated by a near-uniform 10–14 %, the signature of a global
price-level shift (core clock / governor / thermal state), and a
same-build control session earlier the same night drifted far worse
in the same direction. §3 does that arbitration in full.**

## Gate verdict (pre-registered in M37-REPORT §4 — applied exactly, not moved)

Baselines (m36 pooled, cb = 4,092,757): residue 2.604 µs/run, total
callback 16.26 µs/callback, crowd fields/s floor 48.2.

- **Success gate: NOT met.** It required residue ≤ 2.5 µs/run pooled
  **and** crowd-pooled, crowd floor ≥ 48.2, conservation intact,
  dropped_captures = 0. Measured: residue **2.979** pooled /
  **2.997** crowd-pooled (misses the line by ~0.48–0.50); crowd floor
  **43.1** (misses by 5.1). Conservation intact ✓ and
  dropped_captures = 0 ✓ — the two correctness terms pass, the two
  speed terms fail.
- **Kill gate: NOT hit either.** It required residue within ±0.15 of
  2.604 (band 2.454–2.754) **and** callback within ±2 % of 16.26
  (band 15.93–16.59). Measured residue **2.979** is 0.225 *above the
  top* of the band and callback **18.60** is 2.01 above the band top —
  outside in the worsening direction. The kill gate's meaning is "no
  change at comparable windows"; this session changed plenty, in the
  wrong direction, so its condition is not met and the mandated
  consequence (residency tuning closes *by gate*, call-count becomes
  the front *by gate*) does not trigger on its own terms.
- **Verdict: PARTIAL**, with the explicit rider that on the prefetch
  question the run is **gate-inconclusive**: the band the gates were
  written against is ±0.15 µs/run wide, and the session's baseline
  itself moved ~0.37 µs/run (residue) / ~2.3 µs (callback) for
  reasons §3 attributes to device price level, not to the change
  under test. A gate cannot arbitrate an effect smaller than the
  drift of its own baseline.
- **Automatic fail: none.** dropped_captures = 0 in all 23 [pathb]
  pipeline summaries; missfn 0, interp 0 / budget 0 / fb 0 across all
  gameplay; conservation (pre + post ≤ gap) holds in all 28 profile
  windows, zero violations; ADMA never starved (all 11 [stall]
  summaries 0 late / 0 starved); no crash or capture anomaly in the
  session; the owner reports no new missing geometry or corruption.

**Headline numbers (gameplay pooled, cb = 2,752,334): residue 2.604 →
2.979 µs/run (+14.4 %); gap 2.779 → 3.174; total callback 16.26 →
18.60 µs/callback (+14.4 %). Crowd-pooled (fields/s < 50; 18 of 24
windows here vs 8 of 27 in m36): residue 2.618 → 2.997; callback
16.00 → 18.93. Program unit price 7.70 → 8.53 ns/vcyc (+10.8 %).
Fields/s floor 48.2 → 43.1, median 50.9 → 48.6. Every one of those
deltas is inside the range the same build produced across two
sessions in one night (§3) — which is precisely the problem.**

---

## 1. Session identification and shape

- Boot banner `[2026-11-08 01:41:42.724] GameActivity onCreate` →
  `===== godhand android 0.19-m18 boot =====` at line 15890 (shell
  version; the core is identified by its instruments, below). This is
  the last of the file's 28 appended sessions and the only one
  analysed, per brief.
- **Build proof:** the session carries the m34-rider segment
  (`gap %.1f ms (pre %.1f post %.1f, cb %llu)`) in all 28 [ee-prof]
  windows and one `[vu-resident]` line — strings that exist only in
  the m36+ core. PATH-RESIDENT-2 adds no banner of its own (by
  design, M37-REPORT §2: a prefetch-set change only), so no log string
  distinguishes m37's core from m36's; the build identity rests on
  the delivery chain (m37 installed over m36, zip md5
  d688171e32f64cc1e19919e262f29b7f verified at delivery) and on the
  session postdating that delivery. The device ini did not poison
  the test: `PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)`
  in the [BOOT] settings dump, with `PS2X_GS_SCALE=2`,
  `PS2X_THREAD_POLICY=off`, fast FMAC and lean execute on via the
  variant defaults. PS2X_VU1_RESIDENT appears nowhere in the env
  dump, so residency (arena + pin + pair prefetch) ran at its shipped
  default, on.
- **Pin check (required by the brief): ENGAGED.** Line 16092:
  `[vu-resident] VUThread pinned to cpu 7` — the highest-index
  allowed CPU, the prime core. Not the fail-soft unpinned line. The
  arena and prefetch parts are not separately bannered; they are in
  the same shipped core the packaging arbitration verified
  (M37-REPORT §5).
- Shape: **28 profile windows ≈ 120 s of guest time; 5,852 fields by
  [ee-work] (gameplay), 5,751 presents by [frame-stats].** Windows
  1–4 are boot/menu (cb = 0). **Gameplay = the 24 windows with
  cb > 0.**
- **Comparability warnings (two, both material):** (1) this session
  is *much heavier* than m36's — 18 of 24 gameplay windows are crowd
  windows (fields/s < 50) vs 8 of 27 in m36, and its content mix
  differs (vcyc/run 1,808 vs 1,751; the two 0x0520 images swap
  dominance). The µs/run rates remain the comparison basis, and the
  crowd subsets are pooled separately as the gates require. (2) The
  file contains a second post-delivery session (booted 2026-11-08
  00:22:30, 8 gameplay windows) on the same installed build; §3 uses
  it as a same-build control for session-to-session variance. It is
  not otherwise analysed.

## 2. The decomposition, m37 vs m36 (pooled gameplay)

Method validation: the parser run over the m36 session in this same
appended file reproduces the published M36-LOG-ANALYSIS numbers
exactly (cb 4,092,757; worker 66,529.6 ms; gap 2.779; residue 2.604;
callback 16.26; crowd residue 2.618 / callback 16.00; floor 48.2;
7.70 ns/vcyc). The m37 numbers below are the same arithmetic.

m37 pool: cb = 2,752,334 worker runs; vu1worker total 51,187.3 ms;
vu1 total 42,452.0 ms (82.9 % of worker; m36: 82.9 %).

| component | m37 ms | m37 µs/run | m36 µs/run | Δ |
|---|---|---|---|---|
| pre (callback entry → timer start) | 284.4 | 0.103 | 0.091 | +0.012 |
| post (execute return → finish return) | 253.9 | 0.092 | 0.084 | +0.008 |
| **residue (timed span, outside the program)** | **8,200.4** | **2.979** | **2.604** | **+0.375 (+14.4 %)** |
| gap total | 8,738.7 | 3.174 | 2.779 | +0.395 (+14.2 %) |
| vu1worker total per callback | 51,187.3 | 18.60 | 16.26 | +2.34 (+14.4 %) |
| vu1 program time per run | 42,452.0 | 15.42 | 13.48 | +1.94 (+14.4 %) |

- **Conservation: pre + post ≤ gap in all 28 windows, zero
  violations** (gameplay and boot alike). The split is real.
- Per-window residue: 2.12–4.07 µs/run, median 2.99 (m36: 1.99–4.14,
  median 2.57).
- Crowd-pooled (18 windows, cb = 2,121,400): gap 3.194, pre 0.104,
  post 0.094, residue **2.997**, callback **18.93**, program
  8.70 ns/vcyc. Per-window crowd residue 2.17–3.53, median 2.99.
- Gap share of worker is unchanged: 17.1 % (m36: 17.09 %). A share
  that holds while every rate rises ~14 % is itself a clue — §3.
- Wall programs (pooled top-lists): 0x0358-cd8f **16.13 µs/call**
  (743,577 calls; m36: 14.26), 0x0520-4dfe **14.42** (608,026;
  m36: 12.51), 0x0520-2cfd **13.80** (624,763; m36: 12.31),
  0x2378-cd8f 9.59 (m36: 8.96). Both walls and the minor entries all
  rose 8–15 % per call at near-identical call structure.
- Invocation density *fell*: 470 runs/field (m36: 592) and
  309 runs/job (m36: 390) — this session already ran fewer, larger
  invocations, and still paid more per invocation. Count did not
  move the wrong way; unit price did.

### Four-build comparison (pooled gameplay)

| build | residue µs/run | gap | pre | post | callback µs | ns/vcyc | fps floor | fps median |
|---|---|---|---|---|---|---|---|---|
| m34 (0.35) | 3.72 | 3.93 | 0.111 | 0.098 | 24.49 | — | 40.7 | 49.8 |
| m35 (0.36) | 3.33 | 3.52 | 0.101 | 0.089 | 21.90 | 10.23 | 42.3 | 53.6 |
| m36 (0.37) | 2.604 | 2.779 | 0.091 | 0.084 | 16.26 | 7.70 | 48.2 | 50.9 |
| **m37 (0.38)** | **2.979** | **3.174** | **0.103** | **0.092** | **18.60** | **8.53** | **43.1** | **48.6** |

## 3. Why everything rose together — the price-level arbitration

The m37 change is two prefetch instructions at the head of runFused.
Its plausible cost/benefit envelope is the residue term only, and
the honest expectation registered in advance was 0.1–0.3 µs/run.
Measured against that, the session is anomalous in a specific way:
**the inflation is uniform across components the change cannot
reach.** Post — the execute-return path, which no prefetch at the
funnel *mouth* can tax — rose +9.5 %. Pre rose +13.2 %. The program
itself, priced per cycle, rose +10.8 % (7.70 → 8.53 ns/vcyc) at only
+3.3 % more cycles per run. Gap, residue, program-per-run and total
callback all rose within a point of +14.4 %. A cache-pollution story
for the pair prefetch would be expected to land disproportionately
on the residue and the walls' first lines; it has no mechanism to
raise pre, post, and the per-cycle price of the whole interpreter in
lockstep. A global price-level shift — the worker's core running at
a lower effective clock for this session (governor, thermals,
background state) — predicts exactly this shape, and it is the same
confound the M36 analysis declared for its own outsized gain, with
the sign flipped: **measured, mechanism strongly suggested, not
isolated.**

Three corroborations from the log itself:

- **Within-session trend.** The session's coolest windows are its
  early ones: window 7 runs residue 2.12 / 7.37 ns/vcyc, window 6
  2.52 / 7.75 — at or below m36's pooled rates. The warm middle
  (windows 16–18) runs 3.31–3.53 / 8.87–9.44. Halves: residue
  2.925 → 3.025, ns/vcyc 8.37 → 8.67. The build did not start slow;
  the device got slower as the session ran.
- **Same-build control.** The 00:22 session in this file — same
  installed build, same night — pooled residue **3.796**, callback
  **21.95**, **10.28 ns/vcyc** over its 8 gameplay windows, with
  early windows at 6.1–6.5 µs/run residue and 12.8–14.3 ns/vcyc
  alongside 1,400+ ms memslow loads. Nobody would read that session
  as "m37 is 46 % worse than m36"; it is a load-heavy session on a
  device in a different state. But it fixes the scale of same-build
  session variance: ±0.8 µs/run of residue and ±2 ns/vcyc of program
  price — roughly four times the entire effect the pair prefetch was
  hunting.
- **The pin was engaged throughout** (line 16092), so the "thread
  migrated to a slow core" explanation available for m35 is *not*
  available here; what remains is the clock the prime core itself
  was granted, which the log does not instrument.

Conclusion of the arbitration: this session **cannot** convict or
acquit the pair prefetch. Its expected value (±0.1–0.3 µs/run) is
inside the demonstrated noise floor of the measurement setup. That
is a finding about the hunt, not about the hardware: below ~0.3
µs/run, single-session device tests on this device stop being
evidence.

## 4. The pre-named pin risk: EE-side inflation — watch item, remedy NOT triggered

native/guest: **0.833 pooled** (m36: 0.813); **crowd-pooled 0.858**
(m36 crowd: 0.811). The crowd delta (+0.047) is the largest EE-side
move in the series, and it is recorded here plainly. It is not,
however, measured at equal work: this session's crowd windows carry
+7.5 % more VU cycles per invocation (1,809 vs 1,682 vcyc/run) in
heavier content, and §3's price-level drift taxes the EE's native
time by the same mechanism it taxes the worker's. The M36 report's
remedy — moving the pin to the second performance core — stays
**untriggered**: spending the proven prime-core placement to chase a
ratio that moved inside a globally inflated, differently-composed
session would be trading a known good for a mix artefact. It
becomes a real question only if a future session at m36-like price
levels still shows crowd native/guest ≥ ~0.85.

## 5. Feel metrics vs m36 — and why the owner is right

- **Fields/s (gameplay, n = 24):** floor **43.1**, ceiling **55.4**,
  median **48.6**, mean 48.7. m36 (n = 27): 48.2 / 54.0 / 50.9 /
  51.1. The floor fell 5.1 and the median 2.3 — partly mix (three
  quarters of this session is crowd; the floor window, 43.1 fields/s,
  carries 637.5 ms of memslow streaming), partly the §3 price level.
  No window reached the high 50s m35 once offered; the ceiling's
  55.4 is a single early window.
- **Frame-stats (per-process final, line 16588):** presents 5,751,
  avg frame **20.8 ms** (m36 session: 19.7), worst **2,654 ms**
  (m36: 1,269). Bands: ≤16.7 ms 39.6 %, ≤25 ms 37.8 %, ≤33 ms
  18.8 %, ≤50 ms 3.3 %, ≤100 ms 0.38 %, >100 ms 0.10 % (m36: 41.2 /
  37.4 / 19.5 / 1.6 / 0.21 / 0.08). The >33 ms tail roughly doubled —
  the fingerprint of a session parked at ~48 fields/s with heavier
  streaming. The 2,654 ms worst sits in the session's load region
  (the first frame-split segment averages 35.7 ms with swap at
  19.90 ms; memslow peaks at 1,189.9 ms in window 18), a load
  artefact of the same class as m36's memslow-coincident worst, not
  a worker regression.
- **Joins:** per-window join_wait median **16.7 ms** (m36: 35.3),
  worst window 241.2 ms. Shorter, cheaper waits — the EE blocks less
  because each field carries fewer invocations (470/field vs 592),
  even though each one costs more.
- **Memslow:** pooled 5,610.7 ms over 24 windows (7 windows
  >100 ms, max 1,189.9 ms) — proportionally heavier than m36's
  session, tracking this session's areas. It still does not price
  the typical window: windows 12–14 carry ≤10 ms of memslow and run
  48.5–48.8 fields/s at residue 3.13–3.19.
- **Audio:** the owner's "suara masih patah patah" stands unrefuted
  and is consistent with the log, as before: ADMA never starves at
  the 50 ms granularity the [stall] summary measures, but a session
  whose median window runs 48.6 fields/s against a 60-fields cadence
  gives the audio path constant judder to
...[truncated 7370 chars]