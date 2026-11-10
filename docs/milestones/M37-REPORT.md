# PATH-RESIDENT-2 build: m37 (0.38-m37-resident2, versionCode 45)

**Verdict: built, bench-gated, packaged. m37 = m36 (0.37-m36-resident,
versionCode 44) plus exactly the follow-up prescribed by
M36-LOG-ANALYSIS §6: the funnel-mouth prefetch set is widened from "the
image about to run" to the job's whole alternating working set — the
stage wall's entry line at 0x0358 and the character wall's entry line
at 0x0520 are prefetched at every `runFused` head, alongside m36's
state block, current-entry line and data-image head. The arena
co-location, the VUThread prime-core pin, the m35 slim funnel and the
m34 rider counters are all untouched, and the single kill switch
PS2X_VU1_RESIDENT=0 still reverts all of residency in this same
binary. Nothing is published; delivery is the zip below.**

Artifacts:

- APK: `godhand-0.38-m37-resident2.apk`, 140,366,453 bytes (equal to
  m36's APK to the byte: the variant core's +16 B growth was absorbed
  by zipalign page-alignment padding on the stored .so entries; the
  per-file arbitration in §5 proves the content delta is real),
  md5 `e556a492d98f789a51168a6e5193bc4d`.
- Zip: `godhand-0.38-m37-resident2.zip` (the APK alone, deflated),
  35,465,659 bytes, md5 `d688171e32f64cc1e19919e262f29b7f`.
- Installs over m36 as an update: same package `dev.ptraaa.godhand`,
  same probe signer (CN=Probe, cert SHA-256
  9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e),
  apksigner v2+v3 verified, aapt badging: versionCode 45,
  versionName 0.38-m37-resident2.
- Workbench: `resident2-build-work/` (resident2.patch, orig/ pre-edit
  copy of the one touched file, arena_test + outputs, bench-m37,
  replay/ bench outputs, packaging scripts, libgodhand-stage.so /
  libgodhand-variant.so, libps2_runtime-m36.a snapshot).

## 1. The change set (nothing beyond the analysis's prescription)

Complete delta: `resident2-build-work/resident2.patch` — one hunk in
one file, `runtime/ps2xRuntime/src/lib/vu/ps2_vu1_core.cpp`, inside
the existing m36 prefetch block at the head of
`VU1InterpreterAccess::runFused`:

- The m36 block prefetched three things under
  `vu1ResidentEnabled()`: the interpreter state block, the code-image
  line at the *current* entry PC, and the data image's first line.
- m37 keeps all three and adds two prefetches, still under the same
  gate: the code-image line at byte offset **0x0358** (the stage wall's
  entry PC) and the code-image line at byte offset **0x0520** (the
  character wall's entry PC — two captured images, 2cfd… and 4dfe…,
  share that entry, and both are covered by the one line). Each is
  issued only when the offset lies inside the handed-in code image
  (`0x0358u < codeSize`, `0x0520u < codeSize`).
- Why these addresses are the right lines: entry PCs in this
  interpreter are byte offsets into the code image (the fetch loop
  bounds-checks `pc + 8 > codeSize` before each step, and the bench
  entries / [ee-prof:top] lists name the walls by exactly these
  offsets). In the resident layout the code image is the arena base,
  so both targets are constant offsets from the `vuCode` pointer
  already handed to runFused — computable with no registry lookup, as
  §6 specifies. Between two invocations of the same wall, the other
  wall runs (~13–14 µs) plus the VIF packet processing; the point of
  the widening is that the alternating image's refill now starts one
  invocation early, under the current prologue and program, instead of
  stalling the next invocation's prologue.

Not changed: the arena (ps2_memory.*), the pin (ps2_async_vif.cpp),
the slim funnel, the lean execute and fast-FMAC paths, any generated
VU code (the tree's `generated/vu1` is byte-for-byte what m36
shipped), the m34 rider, scheduling, or any game-visible behaviour.
`libpcsx2gs.so` untouched: byte-identical in the shipped APK, md5
`0fc367db8b63cd733d1bccd8c19b8574`, as in every build since m27
(per-file arbitration in §5). Renderer default: the shipped core is
the variant build (GODHAND_PATHB_DEFAULT_PCSX2=ON at package time —
`path B variant` literals present in the shipped core, absent from
the stage core), so the device ini cannot poison the test.

## 2. Bench gates

### 2a. Fixture bench — behaviour neutrality (the gate that arbitrates the code)

Fixture bench over the m17-input captures (5 images, 22 entries),
same composition as m35/m36 (stageprog vu_bench.o + libvu1_ship.a from
the tree's generated/vu1 + the host libps2_runtime.a under test), run
per-image — each image alone in its own process — because
whole-process runs are proven nondeterministic in this environment
(the bench swamp documented in M35/M36 reports).

- **Per-image arbitration is exact, in every combination.** For all
  5 images × 6 switch combinations (lean on/off × slim on/off ×
  resident on/off, as in m36's matrix): the m37 bench's RESULT/TRIVIAL
  sets are byte-identical to the same-day m36 bench's, and the
  same-day m36 bench in turn reproduces the published m36 per-image
  files (`resident-build-work/replay/pi/`) byte-for-byte — so
  m37 ≡ m36 ≡ the arbitrated m35 sets (29 RESULT/TRIVIAL lines in the
  shipped configuration: lean on, slim on, resident on). Resident-on
  ≡ resident-off within the m37 binary in every combination.
  Prefetch is side-effect-free by construction; any divergence would
  have been an automatic fail. There is none.
- **Kill switch verified at the behaviour level** by the resident-off
  runs above and at the layout level by the arena test below.

### 2b. Arena unit test (unchanged code, re-run against the m37 runtime)

`resident2-build-work/arena_test.cpp` (the m36 test, unmodified),
linked against the m37 host runtime: **ALL PASS in both layouts** —
resident (arena allocated, 64 KB-aligned, code == arena base, data ==
arena + 16 KB, boundary/accessor/save-state pointer contract,
re-initialize, destruction) and classic (PS2X_VU1_RESIDENT=0
constructs the two-allocation layout in the same binary). The m37
change does not touch ps2_memory, so this is a regression check, and
it holds.

### 2c. Per-call overhead micro-bench (expected blindness, confirmed)

The funnel micro-bench (one persistent VU1Interpreter, the trivial
39-cycle entry in a tight execute() loop), capture dir
`resident-build-work/cap/micro`. SANITY line exact (cycles=39,
digest 0bcf54455b193756, as expected). Medians across interleaved
runs: m37 resident-on 321 / 380 / 396 ns/call, m37 resident-off
304.8, same-day m36 reference 315 / 322 / 722 — the host on this
shared VM swings widely run to run (the m36 binary itself produced
both 315 and 722 today), and everything sits in the band the m35/m36
reports published (~316–332 ns/call plus noise). As stated in advance
for m36: the host loop is already resident, so it *cannot* price two
extra prefetches; a large host delta would itself have been a red
flag. There is none; the device log is the only arbiter, same as for
m35 and m36.

## 3. Expected device effect (not inflated)

This is the gate-closing build M36-LOG-ANALYSIS §6 prescribed, and
its honest sizing is §6's own: a **0.1–0.3 µs/run hunt** on the one
term it can move — the residue, 2.604 µs/run in m36 — worth **~1–2 %
of the 16.26 µs callback if it lands**. Nobody should promise the
owner a perceptible change from it: at m36's uniformly sub-cadence
48–54 fields/s, a percent or two does not cross the smoothness line,
and the audio (a speed symptom) should not be expected to clear. Its
value is finishing the residency question on closed books: either the
image-pair prefetch closes the last 0.104 µs/run to the success line
and residency's tuning is done, or the kill gate fires and the front
moves, with the books shut, to the call-count attack (fewer, larger
invocations of the wall programs — M36-LOG-ANALYSIS §7).

## 4. Device test (owner) and pre-registered gates

Install `godhand-0.38-m37-resident2.apk` over m36 (same signer; no
uninstall). Play normally for a few minutes, then into a crowd/fight,
as for m34–m36. Return the boot log (`files/godhand-boot.log`, zipped
as last time). The boot log should again show the `[vu-resident]`
pin line near VUThread start.

Read from the new log's [ee-prof] lines exactly as for m35/m36: per
window, gap µs/run = gap ms ÷ cb; residue = gap − pre − post,
likewise. Judge by **total callback time** and the crowd fields/s
floor, not by gap share. The gates below are registered now, against
the M36-LOG-ANALYSIS pooled baselines (27 gameplay windows,
cb = 4,092,757): **residue 2.604 µs/run, total callback
16.26 µs/callback, crowd fields/s floor 48.2**. They do not move
after the log arrives.

- **Success:** residue ≤ 2.5 µs/run pooled **and** crowd-pooled,
  crowd fields/s floor ≥ 48.2, conservation (pre + post ≤ gap)
  intact in every window, dropped_captures = 0 → the image-pair
  prefetch closed the residency question; residency stands as the
  default under its kill switch and the front moves to the next
  lever with this book shut.
- **Kill:** residue within ±0.15 µs/run of 2.604 **and** total
  callback within ±2 % of 16.26 µs/callback at comparable crowd
  windows → residency tuning closes (residency itself stays — it
  earned its keep in m36) and the call-count attack (fewer, larger
  invocations of the wall programs, M36-LOG-ANALYSIS §7) becomes
  the front.
- Automatic fail, as always: any new missing geometry or visual
  corruption; dropped_captures ≠ 0; conservation violated in any
  window.

## 5. Packaging arbitration (vs the shipped m36 APK)

Per-file, excluding nothing: 9 entries in both APKs, identical name
sets. Differing: `AndroidManifest.xml` (versionCode 44 → 45,
versionName 0.37-m36-resident → 0.38-m37-resident2; pool rebuilt at
the same 2,456 B), `lib/arm64-v8a/libgodhand.so` (the m37 variant
core, md5 `1a5c6f4210a1de764cb0642e5bd54961`, 101,470,496 B, +16 B
over m36's in-APK variant — the two added prefetches and their
guards), and the three META-INF signature files (regenerated by
signing, same sizes). The remaining 4 payload entries — including
`lib/arm64-v8a/libpcsx2gs.so` at md5
`0fc367db8b63cd733d1bccd8c19b8574` — are byte-identical to m36's.
Signer certificate identical to m36's (SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e), so
the APK installs over m36 as an update.

Build discipline, same as m36: stage core (option OFF) md5
`3970169a33352d8c460255eb65d29b33` (101,463,360 B, +32 B over m36's
stage core, zero `path B variant` literals, resident strings
present); variant core built by flipping GODHAND_PATHB_DEFAULT_PCSX2
ON in the same tree (12 `path B variant` literals, as in m36's
variant); the option was then flipped back OFF and the stage core
rebuilt to `3970169a…`, leaving the tree in stage state — the
flip-back stage core's md5 reproduces the pre-flip stage core
exactly, so the pipeline's determinism holds for this build too.
All PS2X_VU1_RESIDENT read sites and the `[vu-resident]` pin lines
are present in the shipped core (grep counts match m36's: 4
`vu-resident` literals).

Build notes (no source impact): the Android builds in this session
each paid a full generated-code recompile (~10.6k ninja steps,
roughly 35 minutes per configuration on this VM) — the build graph
arrived dirty (a glob/configure invalidation of the same family as
m36's PCH note) and each option flip re-dirtied the closure, where
m36's flips were incremental. One flip attempt's ninja was also
killed by a wrapper timeout before it started compiling (a pipeline
masked its exit code; the step was simply re-run unwrapped), and the
flip-back build lost one session to an external kill at step
11,530/13,229 and was resumed from ninja's persisted state to
completion. None of this touches the arbitrated content: the bench
(§2) arbitrates the source change, the stage-md5 reproduction
arbitrates the pipeline, and the per-file comparison above
arbitrates the package.
