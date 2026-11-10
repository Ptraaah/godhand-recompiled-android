# PATH-STAGEPROG step 0 — cost decomposition of pc 0x0358, image cd8f1fc2068bd71d

Date: 2026-11-07 (night). Author: Empu (God Hand smith). Step 0 only: no build, no APK.

**Gate verdict: STOP.** No single removable class prices at roughly 15% or more of
this entry's cost, in either fixture regime. The largest sound class is dead-norm
elimination at −6.5% in the long-run regime and ~0% in the device-like short-call
regime. Even the *unsound* upper bound for all load normalizations combined is
only −8 to −9%. This entry's cost is diffuse: it is the sum of the work itself,
not a ceremony tax. Details, method, and what the data suggests instead are below.

Target recap (M33-LOG-ANALYSIS): entry pc 0x0358 in image cd8f1fc2068bd71d costs
21.11 µs/call over 566,263 device calls, 11,955.4 ms, 27.5% of gameplay run time.
It gained only −6.5% from L1 on device while a fixture reading had the other
0x0358 image at −33.5%. Capture binary:
m33-device-return/files/vu-capture/vu1_cd8f1fc2068bd71d.bin (md5
c9068fa6e0103cd99eeedc0e02f60215, identical in the m32 return).

## 1. Method

Three instruments, all offline, shipped sources untouched (every experiment ran
on copies under stageprog-work/; the shipped tree's file timestamps predate this
session):

1. **Dynamic census** — a scratch copy of the shipped interpreter core with
   counters hooked into `stepPair`: per-PC pair counts, per-PC stall cycles
   (the interpreter's own ready-cycle wait loop), and upper/lower operation
   classes taken from the decoder's own `Pipeline` classification. Run over the
   house B5 fixture stream for this entry at three budgets (2,048 / 20,000 /
   400,000 cycles). This counts what the stream *does*, independent of how the
   compiled code spells it.
2. **Static census** — site counts over the shipped generated sources for this
   image (both emission forms), plus translator statistics from regeneration.
3. **Variant A/B pricing** — the translator's own env knobs regenerated this
   image (single-image capture dir, cap1) into pricing variants; each variant's
   9 generated TUs were compiled into a bench binary against the stock runtime
   libraries and run on the same fixture bench as m32/m33 step 0, alternating
   invocations, per-binary medians (10–14 rounds per side). Two regimes: the
   standard 400,000-cycle budget (long-run, body-loop dominated) and an
   8,192-cycle budget (closer to a device call of ~1,970 cycles; the stream
   repeats back-to-back, which is also how the guest calls it).

Controls and honesty notes:

- The **shipped emission was reproduced byte-identically** before any variant
  was trusted: regeneration matches the shipped m32/m33 generated tree for this
  image only with `VU1_RECOMP_NO_DEAD_NORM=1`, i.e. the shipped build runs with
  dead-norm elimination OFF (translator statistic for this image: "writes with
  dead norm: 156"). The ship-variant bench reproduces the known fixture result
  exactly (400,016 cycles, digest 7eac063f3ba558a7, same as bench-m33).
- Same-binary A/B for L1 (env `PS2X_VU1_FAST_FMAC` on/off) removes build drift
  from that one price entirely.
- VM load was a live hazard: an early A/B run overlapped my own variant compiles
  and swung 4–8×; it was discarded. All reported rounds ran on a quiet machine,
  alternating, medians taken. One caution survives (§4): at this code size,
  layout alone can swing a variant by several percent.
- Rig substitution, stated plainly: the fixture stream is synthetic (the house
  B5 fixture: TOP selection and register seeds depend only on the entry). The
  class *mix* transfers (it is a property of the guest code); absolute times do
  not. The two-regime runs exist precisely because the mix shifts with call
  length.
- The interpreter census measures the interpreter's execution of the stream,
  not the compiled code's; it is used for composition (what ops, where, how
  many stalls), never for compiled prices. Prices come only from the compiled
  A/B pairs.

## 2. What this entry actually is (measured composition)

The 0x0358 entry is a trampoline into a stage-processing program. Dynamic census,
400,000-cycle run: 397,339 pairs, 2,661 stall cycles (**0.67%**), zero WAITQ /
WAITP stalls. The repeated body loop is PCs 0x01E8–0x0358, ~8,570 iterations of
40 pairs:

| Per body-loop iteration (dynamic) | Count | Share of pairs |
|---|---|---|
| Upper FMAC-class | 36.0 | 90% of uppers |
| Upper CLIP (writes clip flags) | 4.0 | 10% of uppers |
| Lower integer/ALU | 14.0 | 35% of lowers |
| Lower loads | 6.0 | 15% |
| Lower stores | 6.0 | 15% |
| Lower branches | 6.0 | 15% |
| Lower none (paired slot empty) | 5.0 | 12.5% |
| Lower FMAC-pipeline class | 2.0 | 5% |
| Lower FDIV/EFU class | 1.0 | 2.5% |
| Stall cycles in body | ~0 | — |

Short-call regime (2,048-cycle census, ≈ one device call): the stream is 51%
mid-region code (0x0438–0x2000, load/store heavy), 39% body, 10% clip epilogue;
class mix is the same family (94% FMAC uppers; loads 16.6% of lowers). The cost
per cycle is much lower in this regime on the fixture (3,832 vs ~6,100
ns/kcyc), because the mid region's code is cheaper per cycle than the body
loop's.

Two structural facts:

- **Divider/EFU is a non-class for this image.** Dynamic FDIV/EFU uppers in the
  stream: zero. WAITQ/WAITP: zero. The only divider artefact is a static entry
  guard in seg_00C8 (it checks for FDIV state left by *other* programs); this
  entry itself never divides.
- **The twins correction.** PATH-THROUGHPUT §1 described the two 0x0358 images
  as cost-diverging. Measured here: the interpreter streams of cd8f 0x0358 and
  5dce 0x0358 are *identical* (same census counts, same digest), and the images'
  low halves (0x0000–0x1FFF, all code this entry executes) are byte-identical;
  they differ only in the high half (5dce's is all zeros; no pair of this entry
  executes above 0x2000 in any census run). The fixture's "other image −33.5%"
  L1 figure was measured on a stream that terminates at 6,406 cycles in compiled
  mode — an artefact of the missing high half, not a different code composition.
  On device only the cd8f copy appears in the tops anyway; the correction
  matters because it removes the "why did the twin respond better" mystery:
  there is no second composition to learn from.

## 3. Priced classes (compiled A/B, entry 0x0358, medians)

| Class | Mechanism | 400k regime | 8k regime | Sound? |
|---|---|---|---|---|
| Dead-norm elimination (dn) | Translator-proven dead normalized shadows: 156 writes image-wide drop their `vnorm` + norm store (`writeVfRaw`, 75 sites in this image: 58 loads, 17 FMAC results) | **−6.5%** (6206→5803) | **~0%** (3928→3934) | Yes — digest identical in both regimes |
| All load vnorms (rawloads) | `VU1_RECOMP_RAW_LOADS`: loads keep raw bits, no normalization at all (318 sites) | −8.1% (6120→5626) | −8.9% (3967→3615) | **No** — upper bound only; digest changes; this is the round-9 forbidden direction |
| L1 FMAC fast path, total (already shipped in m32/m33) | Same-binary env A/B, screened twin vs fast twin | −4.5% (6396→6108) | — | Shipped |
| Ready-time ceremony, residual | Inverse price: forcing ready stores for *every* register a segment wrote (`ALL_READY`) costs +2.4% over the shipped reduced set | ≤ ~2.4–5% | — | Bound |
| Sticky-accumulation screen | Inverse price: forcing sticky accumulation (`VU1_RECOMP_STICKY`) costs +2.6%; the shipped no-sticky choice already avoids it | ~2.6% | — | Bound |
| Divider/EFU stalls | Census: 0.67% of guest cycles; zero dynamic FDIV/EFU ops | ~0% | ~0% | — |
| Flag ceremony, residual | `NO_DEAD_FLAGS` variant measured −9.9% — **rejected as a price**, see §4 | n/a | — | Unresolved, bounded |

Reading of the table:

- The dead-norm win concentrates in the body loop (long regime); in the
  short-call regime the stream runs mid-region code whose norms are mostly
  *live* (read by the FMACs that follow), so the sound subset is worth ~0%
  there and the unsound total is still only ~9%. Device calls are ~2,000
  cycles, so the device-weighted dead-norm value for this entry is ≈ −1 to
  −3%, not −6.5%.
- The vnorm family as a whole — every load normalization plus every dead write
  normalization, sound or not — is worth at most ~9% of this entry. There is no
  way to assemble a ≥15% class out of it.
- FMAC: L1's total measured value for this entry is −4.5% (same-binary), which
  caps what flag/screen removal at FMAC sites can still hold: the folded sites
  already run fast flavours, and the remaining full-flag sites are full because
  their flags are consumed mid-segment (four CLIP uppers per iteration read
  status between FMACs — that is the stage program's clip logic, not waste).
  The product-saturation screen kept by the fast flavours is correctness-bearing
  by the PATH-THROUGHPUT design decision, not a removable ceremony.
- Everything else the translator/runtime keeps per pair — ready-time stores,
  branch trampolines, segment bookkeeping — prices in low single digits each,
  and the shipped code already carries the reduced forms.

The remainder, roughly three quarters of the entry's cost, is the work itself:
~36 FMACs with full flag semantics where required, 4 CLIP commits, 12 memory
operations with address arithmetic, and 14 integer lowers per iteration, each
already a short SSE sequence (~6.1 ns per guest pair in the long regime,
~15–18 host cycles). No single removable class exists because there is no single
dominant tax; the stage program is simply dense.

## 4. A measurement caution (why one number was thrown out)

The `NO_DEAD_FLAGS` variant measured −9.9% — faster with dead-flag analysis
*off*. Its actual emission difference from shipped is 16 sites changing flavour
(fmacFastN → fmacFastV in the pc-chain form), which cannot mechanistically save
10%. This is a code-layout swing of the same family as m32 step 0's −3.5%
comment-only drift control, amplified. It is reported here as unresolved noise,
not a price: it also means small deltas (±3%) in the table above should be read
as "low single digits", not as exact figures. The verdict does not depend on
them: the two unambiguous, mechanism-clean prices (dead-norm −6.5%/0%, raw-load
bound −8.1%/−8.9%) are both far from the bar in both regimes.

## 5. Gate verdict and what the data suggests instead

**STOP for PATH-STAGEPROG as scoped.** No kill-switched fast path for a single
class in this image can honestly be priced at the analysis's anchor (a 20–30%
entry cut → worker busy −4.7 to −7.0%). The best sound single class prices at
roughly a quarter of the bar in its best regime and near zero in the device
regime.

What the data does support, in order of measured size:

1. **The gap decomposition (from the M33 analysis, unchanged by this step 0).**
   The unexplained 15.0% of worker time (3.26 µs/run) is larger than anything
   inside this entry. Its one rider stands: two counters splitting
   vu1worker − vu1 into before/after the run callback. Nothing in this
   decomposition contradicts it; everything here reinforces that the remaining
   money is *outside* the run code or spread too thin inside it to attack
   per-class.
2. **The late-session uniform inflation** (all programs +20% per-call late in
   the m33 session, EE clock sagging with it) is likewise larger than any
   single-entry lever and is a machine-state question, not a code one.
3. **Dead-norm elimination as polish, not as a stage.** It is real, sound
   (translator-proven, fixture digest identical), and image-wide (353 dead-norm
   writes across the five device images in the full-capture statistic). Priced
   honestly for this entry: −1 to −3% device-weighted → worker busy ≈ −0.3 to
   −0.8% via this entry, perhaps −1 to −2% across all images. That is below
   stage size; it belongs in a future build only as a passenger with its own
   full verify soak (it changes every image's emission and needs a new runtime
   helper, `writeVfRaw`, which the shipped runtime does not currently have —
   the translator already emits it). It must not be sold as m34.
4. **If a single entry is ever attacked again, 0x0520 is the larger prize**
   (17,828.9 ms vs 11,955.4 ms on device) and it responded to L1 like a normal
   FMAC-bound entry (−27 to −31% fixture), so its remaining cost may have more
   structure. That is a new step 0, not a continuation of this one.

Explicitly not suggested: raw loads (round-9 rule), removing FMAC saturation
screens, more gap dieting without the decomposition counters, or a combined
"everything small at once" build — the small classes overlap (dead-norm ⊂ load
vnorms), their sum is still under the bar, and a multi-change build would
destroy attribution for a gain the owner cannot feel (worker busy −2% at best
≈ +0–1 fields/s in crowds, worst join unmoved).

## 6. Artefacts

All under stageprog-work/: census/ (census instrument sources + binary + logs),
cap1/ (single-image capture dir), gen/ (variant emissions + translator logs),
bench-<variant> and bench8k-<variant> binaries, ab.sh / ab8k.sh drivers,
dnimpl/ (bench-only impl copy carrying the `writeVfRaw` helper used to price
the dn variant). Shipped tree, runtime, and generated sources were not modified.
