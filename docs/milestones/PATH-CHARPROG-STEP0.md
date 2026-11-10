# PATH-CHARPROG step 0 — offline decomposition of pc 0x0520: gate STOP

Step 0 only, per the M33 arbitration. No build, no APK, no zip. Method and honesty
rules are the PATH-STAGEPROG step-0 ones, reused deliberately: shipped emission
reproduced byte-identically as the control before any variant is trusted, alternating
A/B invocations with medians, the code-layout noise caution, and the discarded-variant
discipline (a reading whose mechanism cannot explain its delta is thrown out, in the
open, with its rerun next to it).

## Verdict

**STOP.** No single sound removable class prices at roughly 15 percent or more of
this entry's cost in the device-relevant (8k short-call) regime. The best sound
candidate, dead-norm elimination, prices at **about 0 percent** in both regimes
(+1.0 / −1.7 percent at 8k, +2.4 / −1.2 percent at 400k, per image). The unsound
raw-load family — priced only as an upper bound, round-9 rule standing — reaches
only +7.1 / +2.3 percent at 8k and changes the result digest, so even its bound
cannot clear the bar and cannot be trusted as a clean price (below). Dead-flag
residue and sticky-accumulation forcing both price at ~0. The two large numbers in
the table are inverse prices of value the shipped code already captures (ready-time
elision ~11–14 percent; L1 fast FMACs, priced at fixture −27 to −31 percent back in
the m32 step 0) — headroom already spent, not headroom remaining.

There is no m34 from PATH-CHARPROG. The entry is dense real work, the same shape
the stageprog entry turned out to be: about three quarters of its cost is the work
itself (per-iteration mix in §3), and the removable-ceremony layer around it has
already been taken by L1, the segment ready-time elision, dead-flag elimination,
and the sticky screen.

## 1. Target and device weight

From M33-LOG-ANALYSIS.md (m33 device session, gameplay windows): pc 0x0520 is the
largest single remaining block of VU1 run time.

| image | µs/call (device) | share |
|---|---|---|
| img 2cfd7f04d22a4c7f | 16.70 | — |
| img 4dfef01657b65f6a | 17.03 | — |
| combined | **17,828.9 ms / 1,058,722 calls = 16.84 µs/call** | **41.0% of gameplay run time** |

Captures: `m33-device-return/files/vu-capture/` (md5-identical in m32-device-return):
2cfd md5 `20f381f4c504ef62f2e962e69d7c066b`, 4dfe md5 `75078f6a20dde65c1980baf7590ba4c5`.
Workbench: `charprog-work/` (cap2 = the two target bins only; gen/, logs/, bench* per
variant; census logs under `charprog-work/logs/census-*`).

## 2. The twins question first: the two copies run the same stream

Stageprog's warning applied here in full: do not trust any fixture difference
between the two images until the streams are compared directly. Compared three
ways, in both fixture regimes:

1. **Interpreter census, whole output.** `vu_census` runs (budget 400,016 cycles
   and budget 8,192) for the two images produce **byte-identical output except the
   image name**: same RESULT digest (400k: `b0c0d20f50a274d7`; 8k:
   `3a14b059b4fbd036`), same pair total (399,681 / 8,168), same class aggregates,
   same per-PC pair counts at all 69 hot PCs, same stall PCs. The digest is a
   state+data hash over the whole run, so a different executed instruction
   anywhere in the stream would have to collide with it and with every count.
2. **Raw pair words over the executed range.** The two 16,384 B images differ in
   391 bytes, all inside 0x0500–0x07FF; as aligned pair slots, **59 slots differ,
   the first at 0x0530**. The fixture-executed range — entry pair 0x0520, setup
   0x00B8–0x02D8, loop 0x02E0–0x0500 — contains **zero differing pair slots**
   (the entry pair itself, `72070040 ff020000`, is byte-identical). Everything
   that differs is the 0x0528 fallthrough region and beyond, which this entry's
   stream never enters: pc_0520 is a two-pair trampoline that branches to 0x00B8.
3. **Compiled-mode digest.** In the bench (compiled path), both images return the
   identical 0x0520 RESULT digest in both regimes (400k `7b532b464ce4e31f`, 8k
   `d6d2955e7584a711`).

Conclusion: for this entry the two copies are one program. Their 2 percent
device µs/call difference (16.70 vs 17.03) is measurement texture, not
composition. This is the opposite of the stageprog twins artefact and it is why
every variant below is reported per image and required to move both images the
same way before it is believed. (Scope limit, stated plainly: this proves the
fixture streams identical. Whether a device stream could ever take the 0x0528
fallthrough — where the copies genuinely diverge — is not established by the
fixture; the near-equal device per-call costs say the divergent region is not a
material part of device time either.)

## 3. Dynamic census: what one call is made of

Interpreter census (`stageprog-work/census/vu_census`), per image — identical for
both images (§2), so one table serves both.

### Long regime (budget 400,016 cycles)

Totals: 400,016 cycles, 399,681 pairs, **6,060 loop iterations** (pairs at loop
head pc 0x02E0), stall cycles 319 (0.08% of cycles; 304 of them at the loop head),
waitQ 0, waitP 0, iBit 12,123, eBit 0.

| class | count | % of pairs | per iteration |
|---|---|---|---|
| upper FMAC | 393,621 | 98.5% | 64.95 |
| upper CLIP | 6,060 | 1.5% | 1.00 |
| upper FDIV / EFU / LSU / IALU / branch | 0 | 0% | 0 |
| lower load | 187,875 | 47.0% | 31.00 |
| lower store | 36,051 | 9.0% | 5.95 |
| lower IALU | 72,715 | 18.2% | 12.00 |
| lower branch | 22,261 | 5.6% | 3.67 |
| lower FMAC | 6,061 | 1.5% | 1.00 |
| lower FDIV/EFU | 6,061 | 1.5% | 1.00 |
| lower none | 68,657 | 17.2% | 11.33 |

Per-iteration figures are fixture averages over data-dependent traversals
(65.95 pairs/iteration), not a fixed body: the tail routes split — pcs
0x0488–0x0498 execute on 4,083 of 6,060 iterations (67.4%), pcs 0x04A0–0x04B0 on
1,976 (32.6%), and 4,083 + 1,976 = 6,059, so the two routes are mutually
exclusive and rejoin at 0x04B8.

### Device-like regime (budget 8,192 cycles)

Totals: 8,208 cycles, 8,168 pairs, 123 iterations, stall cycles 24, waitQ 0,
waitP 0. Class shares are the long regime's to within noise (upper FMAC 8,045 =
98.5%; lower load 3,842 = 47.0%; lower IALU 1,476 = 18.1%; lower store 728 =
8.9%; lower branch 489 = 6.0%; lower FMAC 124; lower FDIV/EFU 124). The mix does
not change at device-like call lengths; there is no short-regime-only class
hiding here.

### Divider and EFU, settled

The census bucket "lower fdiv/efu" had to be pinned down before anyone wrote
"divider absent" — it is not absent. Decoding the loop with the interpreter's own
field rules (direct opcode gate, then the special field):

- **Exactly one DIV executes per loop iteration**: guest `DIV Q, vf0.w, vf26.w`
  (the perspective divide), at **pc 0x0498** on the 67.4% route or at **pc
  0x04B0** on the 32.6% route. The 400k accounting closes exactly: 4,083 + 1,976
  = 6,059 loop divides, + 1 setup DIV at 0x0290 + 1 setup EFU at 0x0238 = the
  census total 6,061.
- **EFU in the loop: none.** The only EFU-class op in the executed stream is the
  single setup op at 0x0238 (once per call — nothing). No EFU segment code exists
  in the generated loop (no startEfu/commitEfu sites).
- The DIV is real per-vertex work already in its cheapest host form (one scalar
  divide inside `divOp`, its latency hidden — waitQ is 0 and no stall PC sits in
  the divider sub-blocks). It is not a removable class; it is part of the
  three-quarters that is the work itself.

### Static shape (shipped generated code, identical method to stageprog)

Translator totals for the image pair (per image: 1,139 pairs, 9 blocks, 3 parts;
580 known segments and 2,046 dead-flag pairs and 318 dead-norm writes are totals
over the 4 emitted copies — primary + screened twin per bin — so ≈159 dead-norm
writes and ≈1,023 dead-flag pairs per image):

- Loop head segment seg_02E0 (36 pairs, pcs 0x02E0–0x03F8): 31 FMAC sites —
  27 fastV, 1 F, 3 full — 20 vnorm, 20 ldq + 4 ldw, 1 stq, 1 commitClip,
  1 pushFmacFlags, 17 rmax.
- Divider sub-blocks seg_0498 / seg_04A0: 7 FMAC sites each, **all 7 full
  (screened)** — the Q-consuming FMACs cannot fold — with 14 / 13 rmax around the
  fdiv ready chains. Small, dense, and priced by the allready inverse below.
- The entry's FMAC flavour mix is the L1-normal one (contrast stageprog's cd8f,
  whose folded flavour was anomalously weak): this entry took L1's normal
  fixture gain (−27.1% / −30.7% per image in the m32 step 0) for the same reason.

## 4. Variant pricing

### Control

Shipped emission reproduced first: regenerating with
`VU1_RECOMP_NO_DEAD_NORM=1` produces **byte-identical files to the shipped
generated tree for both target images (all 8 files: registry + 3 shards each)**.
Every "ship" bench below runs that regeneration, so the control is the shipped
code, not a paraphrase of it. The dn variant additionally needs the bench-only
`writeVfRaw` / `writeVfRawFull` helpers (`charprog-work/dnimpl/`, a copy of the
stageprog dnimpl plus the full-lane sibling these images emit and cd8f did not);
the helper changes no shipped code and exists only so the dn emission can run.

### Method

Alternating invocations (A/B/A/B…), 10 rounds per pair (15 for the decisive dn
rerun), medians reported, ns_per_kcyc of entry 0x0520 per image. Digest gate:
a sound variant must return the ship digest (400k `7b532b464ce4e31f`, 8k
`d6d2955e7584a711`) for both images; every variant below passes except rawloads,
which is the expected fingerprint of its unsoundness. Two sets were discarded
under the standing discipline and are documented, not buried (a third, the exact
sets, is documented after the table): (a) the first 8k
ship-vs-dn set, taken while the VM was compiling the remaining variants —
same-binary values swung 7,406–89,885 ns/kcyc, 12×, unusable; the clean rerun
below replaces it. (b) the first 400k ship-vs-nodeadflags set, which showed
+20.7 / +24.4% for turning an optimization OFF — a 144-line emission diff cannot
explain that, the ship binary's medians in that set (10,788 / 11,315) sat ~35%
above its medians in every other set while the variant sat in the normal band,
and the rerun (§5 table) reads +1.1 / −3.5%. Discarded as layout/machine-state
noise, exactly the stageprog nodeadflags precedent.

### Prices (delta vs ship, percent; positive = variant faster)

| variant | mechanism | 8k 2cfd | 8k 4dfe | 400k 2cfd | 400k 4dfe | reading |
|---|---|---|---|---|---|---|
| dn | dead-norm elimination ON (163 writeVfRaw sites, both images) | +1.0 | −1.7 | +2.4 | −1.2 | **~0. Not a class.** Digest-clean both regimes. |
| rawloads | raw loads (UNSOUND upper bound) | +7.1 | +2.3 | −24.5 | −38.1 | Digest differs → the variant's stream itself diverges; the 400k figure is not a price at all, and the 8k figure is at best a soft bound. **Under the bar even taken at face value.** Round-9 rule stands: never shippable. |
| nodeadflags | dead-flag elimination OFF (inverse) | +2.8 | −3.4 | +1.1 | −3.5 | ~0. The shipped dead-flag elimination is worth ~nothing on this entry; there is no flag residue to hunt. (First 400k set discarded, above.) |
| allready | ready-time elision OFF (inverse) | −12.0 | −14.1 | −13.9 | −11.1 | Forcing naive ready chains **costs** 11–14% → the shipped elision already captures that much. Inverse price of captured value, not residue. |
| sticky | sticky accumulation forced ON (inverse) | +3.9 | +0.0 | +4.6 | −4.6 | ~0, signs disagree. The translator already screens this entry out of sticky accumulation; forcing it back is free within noise. Nothing there. |
| exact | all FMACs screened (inverse; total L1 value) | not priced here — sets discarded (below); L1's value on this entry is already measured: fixture **−27.1% / −30.7%** per image, m32 step 0, same-binary A/B. Captured value, not residue. |

Note on CLIP/flag commits: no translator knob isolates them, and none was
invented for step 0. The static count bounds them: 1 commitClip and 1
pushFmacFlags per loop-head traversal against ~65 FMACs, and the allready inverse
price caps the entire ready/flag ceremony complex at the 11–14% the shipped code
*already avoids*. A residue claim inside that complex would still have to clear
15% on its own; nothing in the counts supports one.

A third discard, for completeness: both ship-vs-exact sets and a ship-vs-ship
control set were taken during a later load burst on this VM (load average ~6;
ship against *itself* swung −25% / −46% with a 6,682–65,611 ns/kcyc range), so
no exact price is quoted from them. The variant still produced one hard fact:
the fully screened emission's RESULT digest equals the *interpreter* digest
exactly in both regimes (8k `3a14b059b4fbd036`, 400k `b0c0d20f50a274d7`) — i.e.
the shipped compiled digest differs from the interpreter's precisely by the
fast flavours' accepted flag semantics, and screening removes exactly that
difference. L1's total worth on this entry does not need re-pricing: the m32
step 0 measured it directly on the shipped binary (−27.1% / −30.7% fixture).
The gate never depended on this row: exact is an inverse price of shipped value.

Every price the gate *does* rest on was taken in the quiet window after the VM
redeploy (load ~2.4 and falling, one spike round in fifteen, medians robust),
and each was required to agree across both images and, where the regime matters,
both regimes: dn reads ~0 four independent times (+1.0 / −1.7 at 8k, +2.4 / −1.2
at 400k); allready reads −11 to −14% four times; the nodeadflags rerun agrees
with its 8k set. Four conspiring coincidences is not a noise model.

## 5. Why the gate stops

Add the per-iteration mix up: ~65 FMACs (already in their cheapest sound
flavours — L1 folded-fast where legal, screened only where Q or flags force it),
31 loads whose values feed those FMACs directly (the normalization the raw-load
experiment prices is the sound barrier the round-9 incident bought), 12 IALU,
6 stores, 1 CLIP, 1 perspective DIV, plus the branch machinery of a two-route
tail. Every ceremony layer around that work that a translator knob can touch has
now been priced on this entry: dead-norm ~0, dead-flag residue ~0, sticky ~0,
ready-time residue already captured (its forced-off price is the proof), raw
loads unsound and under the bar anyway. The remaining cost is the arithmetic,
the data movement, and the divides — the work itself. A fast path for this entry
would be a hand-rewrite of correct vertex math, which is a different risk class
than a translator screen and is not what step 0 was asked to price.

For the record, the bar itself was worth setting: a hypothetical sound class at
exactly 15% of this entry would have been worth 0.15 × 41.0% (entry share of
run) × ~85% (run share of worker busy, M33 accounting) ≈ **worker busy −5.2%**,
mid crowds roughly +1–3 fields/s by the shape of the earlier levers — and could
not have promised 60 fields/s, a sub-900 ms worst join, or any movement on the
15% gap or the late-session inflation. No class reached the bar, so no step 1 is
priced and no switch is named; `PS2X_VU1_FAST_` gains no new member from this
entry.

One cross-entry note, to keep the books straight: dead-norm elimination, priced
here at ~0, was the stageprog entry's best sound class at −6.5% (long regime).
Its value is image-dependent, and on the largest remaining entry it is worth
nothing. The image-wide polish estimate from stageprog step 0 (−1 to −2% worker
busy across all images) does not improve with this entry's evidence.

## 6. Rider (design only): the two gap counters

M33 priced the vu1worker − vu1 gap at 15.0% of worker time (3.26 µs/run) and
showed the fixed callback stack is only ~1/18 of it, with the dominant term
proportional to run work. The next build that touches this needs the gap split
**before vs after the run callback**, per window. Design only — no code was
written.

### Definitions (per [ee-prof] window)

- `vu1worker` = `vu1CbWorkerNs` delta (already printed as `vu1worker N/X ms`):
  the whole MSCAL/MSCNT callback invocation, worker-thread callbacks only.
- `vu1` = `vuRunNs[1]` delta (already printed): the runLoop timer inside
  `VU1Interpreter::execute`.
- gap = vu1worker − vu1. The two new counters split the gap; a third quantity,
  the residue *inside* execute() but outside runLoop (resetScheduler remnant
  under lean, compiled-program dispatch), is obtained by subtraction —
  residue = gap − pre − post — so two counters suffice for a four-way split.

### Hooks

All four stamps are taken on the VU worker thread inside the MSCAL/MSCNT
callback in `ps2_runtime.cpp` (the callback whose body calls
`m_vu1.execute(...)` at ~:1147 and `finishVu1Program(...)` at ~:1150):

- `t0` at callback entry (before any argument setup the callback itself does),
- `t1` immediately before the `m_vu1.execute(...)` call,
- `t2` immediately after `execute` returns,
- `t3` immediately after `finishVu1Program(...)` returns.

Accumulate `pre += t1 − t0` and `post += t3 − t2`, **only under the same
`t_onWorker` condition that routes a callback into `vu1CbWorkerNs` today**
(ps2_async_vif.cpp), so the buckets stay commensurate with vu1worker and
EE-thread callbacks (the `vu1ee` line) stay out of the gap's definition.

### Storage and print

- Two `std::atomic<uint64_t>` nanosecond accumulators plus one shared callback
  count, added to the eeprof accumulator set beside `vu1CbWorkerNs` /
  `vu1CbWorkerCount`, so the existing snapshot/delta machinery yields per-window
  values with no new plumbing.
- Print appended to the existing [ee-prof] line in EeScheduler.cpp, immediately
  after the `vu1worker %llu/%.1f ms` field, in the same units and style:
  `gap %.1f ms (pre %.1f post %.1f, cb %llu)` — gap recomputed from the two
  existing deltas so the line is self-checking.
- Conservation check for the log reader: pre + post ≤ gap in every window, and
  pre + post + residue = gap exactly. A window violating the inequality is a
  counter bug, not a result (all stamps are same-thread, so no clock-skew
  excuse exists).
- Explicit exclusion, to be written in the implementing commit message: the job
  layer's drain and publish times (`s_workArbiterDrainNs`, `s_workPublishGifNs`)
  happen after `processVIF1Data` returns, **outside** the callback. They are not
  part of vu1worker and must never be attributed to this gap; L3 already priced
  them at ~0.46% of job busy. Queue wait and idle are likewise outside.

### Overhead and passenger discipline

4 `steady_clock::now()` reads + 2 relaxed atomic adds per worker-thread VU1 run:
well under 0.1 µs against a ~22 µs average run — under 0.5% of the gap it
measures and ~0.01% of worker busy. No allocation, no locking, no branch the
compiler cannot predict. Always-on, following the milestone-14 precedent
(`s_workVuBusyNs` is always-on for the same reason): vu1worker itself is
always-on, and an env-gated bucket would silently stop being commensurate with
it. The counters change no behaviour and are removable in one commit; they ride
as a pure passenger on whatever build goes out next, exactly as the [ee-prof:top]
image split rode m33.

### How a returned log is read with them

Divide by the window's callback count for µs/run and compare against m33's
3.26 µs/run gap. Three cases, each naming its own next lever:

- **pre-dominant** → the cost sits before execute: callback argument setup,
  compiled-program registry lookup, the ee-prof hash cache in the run recorder's
  constructor. Lever: cache/lookup diet at the callback layer.
- **post-dominant** → finishVu1Program: final-state writeback, capture and
  framestats hooks. Lever: finish-path diet.
- **residue-dominant** (gap − pre − post is the big term) → inside execute but
  outside runLoop: the lean-skip's own dirty check, scheduler residue,
  compiled-block dispatch. Lever: execute() prologue work, and the M33 premise
  gets its final answer.

## 7. Method limits, stated

- Fixture data is the capture's data. Device streams are longer and
  data-dependent; the census mix is regime-stable (400k vs 8k shares match), and
  every price was taken in the device-like regime as well as the long one, which
  is the stageprog lesson applied — but a class that only exists under device
  data distributions the capture never produces would be invisible here. That is
  a limit of every step 0 so far, not a new one.
- Bench ns/kcyc is a same-host relative measure. Absolute values moved with VM
  load all night (one VM redeploy landed mid-work); only alternating medians
  were used, three contaminated sets were discarded in the open, and no delta in
  the table rests on a single set.
- The dn price runs the dn emission through a bench-only helper
  (writeVfRaw/writeVfRawFull) the shipped runtime does not have; digest equality
  in both regimes is the evidence the helper is faithful. If dead-norm were ever
  shipped, the helper plus the full soak from stageprog §5 would be its gate —
  this entry's ~0 price means that question does not arise from charprog.
