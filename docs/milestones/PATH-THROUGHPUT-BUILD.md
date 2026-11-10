# PATH-THROUGHPUT build report — 0.33-m32-throughput (versionCode 40)

Status: COMPLETE — build shipped, soak passed, tree restored to the stage state.

## Step 0 — pricing the range screen (GATE: stop if under ~3%)

Rig note: the round-9 chained replay file (port_prep/round9, rec/r9g/vu1trig_7322.bin)
does not exist anywhere on this machine (full-workspace search). The surviving desktop
A/B rig is the Path B stage B5 fixture bench (m24f-fixture/vu_bench.cpp): every real
(image, entry) pair from the m17 captures over deterministic synthetic fixtures,
digest-checked against the interpreter. Step 0 regenerated the five images once with
VU1_RECOMP_EXACT_FMAC=1 (all other knobs identical; verified the only emission delta is
143 fmacFastN -> fmacV and 692 fmacV stay fmacV) and benched shipped vs exact.

Raw single-block runs on this VM swing wildly (CPU drift between blocks), so the
reported numbers come from 8 strictly alternating invocations of each binary,
per-entry medians, cycles identical between variants:

- pooled (cycle-weighted) ns per 1000 VU cycles: shipped 7180.3, exact 7457.7
  -> the range screen costs +3.86% of compiled run time.
- every one of the 15 timed entries is slower exact (+0.03% .. +9.37%);
  per-entry median delta +2.39%, mean +2.79%.
- the two device-dominant entries: 0x0520 +2.25% / +2.96% (two images),
  0x0358 +0.03% / +1.09%.

Verdict: 3.86% is above the ~3% stop line — PROCEED.

Correction recorded after the fact (see "L1 measured" below): the shipped-vs-exact
metric prices only the 143 dead-flag sites (fmacFastN -> fmacV); the folded sites
already pay fmacV in BOTH variants, so their screen cost cancels out of this
difference. The metric is a valid lower bound for the gate (the screen is worth at
least ~3.9%), and the gate passed — but it underprices L1's actual target, which the
same-binary A/B later measured directly at ~19.6% pooled on this rig.

## Translator (done, verified)

- vu1_recomp.cpp: new knob VU1_RECOMP_NO_FOLDED_FAST (default: folded fast on);
  Emitter::fastFolded; in a collapsed segment, a non-last FMAC whose flags are not
  dead emits fmacFastV instead of fmacV when fastFolded; main() additionally emits
  every non-VU0-sized image under hash ^ kVu1ScreenedTag (0x5655315343524E44,
  "VU1SCRND") with fastFolded off.
- Regenerated with VU1_RECOMP_NO_DEAD_NORM=1 (required to reproduce the shipped
  emission for the twin). Verification, per image: the screened twin is byte-identical
  to the shipped source modulo the hash literals; the fast primary flips exactly the
  692 folded fmacV sites to fmacFastV (160+188+201+60+83 per image), with fmacFastN
  (143), fmacF (91) and plain fmac (1242) counts unchanged.
- Loads, vnorm and the normalized shadow untouched (round-9 rule).

## Runtime (done)

- ps2_vu1_core.cpp: kVu1ScreenedTag (same value as the translator);
  vu1FastFmacEnabled() (PS2X_VU1_FAST_FMAC=0 -> screened); findCompiledVu1Image
  prefers hash (fast) or hash^tag (screened) and falls back to the other variant;
  registerVu1CompiledProgram counts distinct images (a twin is not a new image, so
  the boot count stays 5) and prints once, at first registration:
  "[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)" (or the screened
  wording when the switch is set).
- L3 counters (behaviour-neutral): ps2_async_vif WorkStats gains vifProcessNs /
  arbiterDrainNs / publishGifNs, timed inside the VU worker's job loop and printed
  beside [ee-work] as "[vu1] job split: ..."; gscoupling Snapshot gains consumeBytes
  and queued-ahead depth (total/max/samples) recorded in
  GSThreadedBackend::ConsumeLocalToHostBytes (serving path records bytes only;
  drain path samples m_published - m_completed under the queue mutex), printed on
  the [ee-work] line as "consumeBytes %.2f MB ahead avg %.1f max %llu".

## L1 measured (same-binary A/B, the honest number)

bench-new (the new generated code + new runtime, one binary) run in both switch
modes, 8 strictly alternating rounds, per-entry medians, cycles identical:

- pooled: screened 7350.2 ns/kcyc, fast 5911.2 ns/kcyc -> L1 = -19.6% of compiled
  run time on the fixture rig.
- 0x0520 (49% of device VU time): -27.1% / -30.7% (two images).
- 0x0358 (29%): -33.5% / -5.4% (two images; the cd8f copy of the stage program is
  dominated by non-FMAC work).
- build drift check: bench-new screened (7350) vs the independently built
  bench-shipped (7102) = -3.5%, so the -19.6% is the emission change, not the
  toolchain.

Fixture caveat: the fixture operands keep the fast path out of its saturation
diversion (fmacFastImpl falls back to fmacV per component when a product leaves
the representable range). Real crowd vertex data is in-range by the same argument
PATH-THROUGHPUT.md §3 makes, so the device should keep most of this; the device
run is the arbiter (section 7 lines below). Even a third of the fixture number
beats the report's predicted band.

## Soak

Rig: the B5 fixture bench (vu1_recomp's m17 captures, all 22 real entries) plus the
runtime's own PS2X_VU1_VERIFY=obs comparison. The round-9 chained trace file no
longer exists on this machine and no retail ISO is present to capture a new one,
so "crowd scenes" here are the two crowd-dominant programs themselves (0x0520
character transform, 0x0358 stage program) over thousands of verified runs.

- Screened twin: bench digests AND cycle counts identical to the shipped code on
  all 22 entries; verify=obs mismatch profile identical to shipped (same 50
  reports, all on image 5dce's synthetic-fixture wild paths — a pre-existing
  fixture artefact of the shipped code, reproduced verbatim). The twin is
  today's behaviour, bit for bit.
- Fast variant: cycle counts and PCs identical everywhere. Every new divergence
  classifies into the accepted cancellation-boundary class, and nothing else:
  * 0x0520 (both images): verify=obs reports only 00000000 vs 80000000 — the sign
    of zero — in two VF lanes and the one data word storing them (mem@003f);
    MAC/status/clip resolve equal.
  * 0x00B8 / 0x0358 (bench digest, coarser than verify=obs): values bit-identical
    (including zero signs); only the aggregate status word differs by one sticky
    bit (0x1000 / 0x200). All five images contain no status reader (noSticky),
    so that bit is unobservable in-game; under verify=obs's resolved comparison
    these runs do not fire at all.
  * 5dce 0x00C8 at the dump TOP: fast dump == screened dump exactly; its bench
    digest difference sits on the same pre-existing wild path as shipped's.
- No round-9 signature anywhere: no missing-geometry pattern, no NaN spread, no
  magnitude changes, loads/vnorm/normalized shadow untouched.

Soak verdict: PASS.

## Kill switch (same binary, desktop rig)

- Default: "[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)" and
  "[bench] 5 compiled VU program(s) registered" (count stays 5 with 10 images
  registered).
- PS2X_VU1_FAST_FMAC=0: "[vu1-aot] fmac: screened (PS2X_VU1_FAST_FMAC=0 is set;
  unset it to restore fast)", count still 5, and the screened numbers/digests
  above come from that same binary.
- On device the switch is seeded by the variant glue from the external
  godhand.ini (new ps2x_vu1_fast_fmac passthrough, variant default 1), like
  every previous stage's switch.

## What changed (file list)

Translator (not shipped in the core):
- runtime/ps2xTest/tools/vu1_recomp.cpp — gFoldedFast knob
  (VU1_RECOMP_NO_FOLDED_FAST), Emitter::fastFolded, the collapsed-branch
  emission condition, dual emission under hash ^ kVu1ScreenedTag.
- generated/vu1/ — regenerated: 5 fast primaries + 5 screened twins +
  vu1_programs.cpp (10 registrations).

Runtime (in libgodhand.so):
- runtime/ps2xRuntime/src/lib/vu/ps2_vu1_core.cpp — kVu1ScreenedTag,
  vu1FastFmacEnabled(), the variant-preferring lookup in
  findCompiledVu1Image, distinct-image counting + the one-time fmac boot
  line in registerVu1CompiledProgram, ps2xVu1CompiledProgramCount now
  returns the image count.
- runtime/ps2xRuntime/include/runtime/ps2_async_vif.h +
  src/lib/ps2_async_vif.cpp — WorkStats phase fields, three atomics, the
  phase timers in the VU worker's job loop, workStats() fill.
- runtime/ps2xRuntime/include/runtime/gs/gs_coupling_stats.h +
  src/lib/gs/gs_threaded_backend.cpp — consume byte/depth counters,
  recordConsume(), both call sites in ConsumeLocalToHostBytes.
- runtime/ps2xRuntime/src/lib/Kernel/EeScheduler.cpp — the [vu1] job split
  line and the [ee-work] consume segment.
- android/glue/godhand_android_main.cpp — variant-only (#ifdef
  GODHAND_PATHB_DEFAULT_PCSX2): the ps2x_vu1_fast_fmac ini passthrough +
  seed paragraph (variant default 1).

Nothing else. Loads, vnorm and the normalized shadow untouched; no
job-layer behaviour change (L3 is counters only); no L2 in this build.

## Cores

- Stage core (tree left in this state, option OFF):
  md5 be59c6d131d228b6b6be23255062904a, 101,451,288 B.
- Shipped variant core (option ON; delta vs stage +6,496 B — the variant
  seed paragraphs incl. the new fmac one):
  md5 d6fd6774b6a97d289ba4deaf74d4a1d1, 101,457,784 B.
- Flip-back reproduction: after the variant core was copied out, the
  option was flipped OFF and the tree rebuilt (interrupted once by a VM
  restart, resumed incrementally). The rebuilt stage core is
  md5 be59c6d131d228b6b6be23255062904a — byte-identical to the stage
  artifact above. The tree is left in the stage (mainline) state.

## Binaries vs m31 (per-file, inside the APK)

| entry | m31 | m32 | verdict |
|---|---|---|---|
| AndroidManifest.xml | 69cdd01a441844a99dcd4fa78190a141 (2452 B) | bd5394854393ac2a4643cbb54dcc2d72 (2460 B) | version patch only (0.33-m32-throughput / code 40, read back via aapt) |
| classes.dex | 2d32e68be8c0eb911d80c700529277ee | same | identical |
| resources.arsc | 57ef0403aecfe9a84e3bee942fc1d080 | same | identical |
| lib/arm64-v8a/libgodhand.so | 17ba5dc9628185de05d3e9c2a83425b0 (99,723,032 B) | d6fd6774b6a97d289ba4deaf74d4a1d1 (101,457,784 B) | the one intended payload change |
| lib/arm64-v8a/libpcsx2gs.so | 0fc367db8b63cd733d1bccd8c19b8574 (28,791,224 B) | same | byte-identical, as required |
| lib/arm64-v8a/libshaderc_shared.so | 2793f5b6394868783852325a4295e1f2 | same | identical |
| META-INF/* | — | regenerated | same signer: CN=Probe, O=ptraaa, C=ID, cert SHA-256 9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e; v2 + v3 verified; zipalign -p 4 passes |

- APK: godhand-0.33-m32-throughput.apk, 140,350,069 B,
  md5 35bd82794e1fde3c31c505237afd0fb8.
- Delivery zip (m31 layout: the APK alone, deflated):
  godhand-0.33-m32-throughput.zip, 35,453,280 B,
  md5 a12988694efb10bda42b03a640807c68.

## Boot-line evidence

The desktop rig cannot boot the game on this machine (the only ISO
present is the synthetic generation disc, which the boot rejects for a
missing IRX archive), so the config lines are proven as follows:
- "[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)" —
  PROVEN on the fixture bench, which runs the same
  registerGeneratedVu1Programs + registry code: printed verbatim in fast
  mode; the screened wording printed with PS2X_VU1_FAST_FMAC=0.
- "[vu1-aot] 5 compiled VU program(s) registered" — the count function
  now returns distinct images; the bench (same function) prints 5 with
  all 10 variants registered. The line itself is src/main.cpp:805,
  unchanged.
- Renderer / pipeline-depth-2 / harvest-fastpath lines — code paths
  untouched by this stage; the bridge binary carrying the harvest line
  is byte-identical to m31's, and the variant seed paragraphs for the
  renderer and VU1 async are unchanged (the glue diff is purely the
  added fmac paragraph). Stated by code inspection, per the brief's
  fallback.

## Section 7 arbitration lines (restated for the device run)

From PATH-THROUGHPUT.md §7, against the m31 log as the comparison set:

0. Config proof first: `env: PS2X_GS_RENDERER=pcsx2 (wins over
   godhand.ini native)`, `[pcsx2-gs] pipeline on: ... depth 2 fields`,
   `[pcsx2-gs] harvest fastpath: on`, plus this stage's
   `[vu1-aot] fmac: fast (PS2X_VU1_FAST_FMAC=0 restores screened)` and
   `[vu1-aot] 5 compiled VU program(s) registered`, and the variant seed
   line `native: path B variant: PS2X_VU1_FAST_FMAC=1 (variant default;
   ...)`. Absent or wrong = the run measures nothing.
1. [ee-prof] vu1 ns/vcyc (heavy windows): target <= ~10.8 (m31: 11.45).
   >= 11.2 falsifies the screen-cost premise. (Fixture A/B suggests the
   device could land well under target; judge on matched windows.)
2. [ee-prof:top] pc 0x0520 / 0x0358 µs/call: targets <= ~19.5 / <= ~20
   (m31: 22.0 / 22.3); combined share stays ~78%.
3. [ee-prof] vu1worker − vu1 gap: expected ~unchanged (~14%).
4. [vu1] joins at matched VU load: worst-window total <= ~950 ms
   (m31: 1,007.4), falling in proportion to vu1worker.
5. [ee-work] heavy fields/s: floor >= ~52 in matched-join windows,
   mid-crowd touching 60 (m31: 47.8–58.7); light windows stay
   59.8–60.0 — any light regression is a fail.
6. Fast-path integrity: interp 0, fb 0, budget 0 in every window.
7. Correctness on device: no missing floor/walls near the camera (the
   round-9 signature), no new triangle popping or geometry shimmer in
   crowds, character models intact.
8. Entry windows: `parked: consume` expected unchanged (m31: 6/153.4 ms,
   9/265.3 ms); the new counters must appear on the same lines —
   `[ee-work] ... | consumeBytes %.2f MB ahead avg %.1f max %llu` and
   `[vu1] job split: vif ... drain ... publish ...` beside it.
9. Kill-switch A/B (same binary, external godhand.ini
   PS2X_VU1_FAST_FMAC=0): lines 1–2 must return to the m31 bands
   (ns/vcyc ~11.45, µs/call ~22) — proving the delta is the FMAC
   flavour, nothing else.
10. Owner feel outranks every line: crowds smoother at the same scenes,
    audio texture with them; any new visible defect is a fail
    regardless of the numbers.
