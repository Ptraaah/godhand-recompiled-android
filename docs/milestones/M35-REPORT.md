# PATH-FUNNEL build: m35 (0.36-m35-funnel, versionCode 43)

**Verdict: built, gated, packaged. m35 = m34 (0.35-m34-rider, versionCode 42)
plus exactly the funnel slimming prescribed by the m34 log analysis: the
execute()/resume() dispatch collapsed into one fused step, captureVu1Program's
entry-bitset resolution cached to O(1), and the EeProfVuRun clock/record block
env-gated — all behind the kill switch PS2X_VU1_SLIM_FUNNEL=0, which restores
the m34 funnel in this same binary. The m34 rider counters are untouched and
still print, so this build's device log arbitrates itself the same way m34's
did. Nothing is published; delivery is the zip below.**

Artifacts:

- APK: `godhand-0.36-m35-funnel.apk`, 140,366,453 bytes,
  md5 `cacaed78aaadc1160afe5541db9acb07`.
- Zip: `godhand-0.36-m35-funnel.zip` (the APK alone, deflated),
  35,465,521 bytes, md5 `6eb90187ca0e5fe7faa51bca1a37d180`.
- Installs over m34 as an update: same package `dev.ptraaa.godhand`, same
  probe signer (CN=Probe, cert SHA-256
  9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e),
  apksigner v2+v3 verified, aapt badging: versionCode 43,
  versionName 0.36-m35-funnel.
- Workbench: `funnel-build-work/` (funnel.patch, orig/ pre-edit copies of the
  2 touched files, micro_bench.cpp + bench binaries, replay/ gate outputs,
  packaging scripts, libgodhand-stage.so / libgodhand-variant.so).

## 1. The change set (nothing beyond the analysis's prescription)

All in `runtime/ps2xRuntime/src/lib/vu/ps2_vu1_core.cpp`, plus one declaration
in `ps2_vu1_impl.inl`. Complete delta: `funnel-build-work/funnel.patch`
(262 lines, 2 files).

1. **Collapse.** `execute()` (:1563-1568) and `resume()` (:1581-1584) now
   dispatch VU1 runs to the new `VU1InterpreterAccess::runFused()` (:1640)
   instead of `run()` when the slim funnel is on. runFused is run()'s VU1
   branch verbatim — capture, trace, the verify double-run gate, the
   frame-stats sink branch, in the same order — except the final step looks
   the compiled program up **once, there** and calls `runLoopWith()` directly.
   The m34 path execute → run → runLoop → runLoopWith (two dispatch layers,
   the second re-deriving the unit and re-checking allowCompiled) is gone from
   the hot path. run() itself is untouched: the kill switch, and every
   non-VU1 unit, still use it.
2. **Capture bitset cache.** captureVu1Program resolved the current image's
   EntryBits slot with a scan (atomic loads + a possible CAS claim) on every
   run. The resolution cannot change while (memory, generation) — and
   therefore imageHash — are unchanged, because slots are claimed once and
   never released. The slim funnel caches it in the capture object
   (`entryBitsForImage` / `entryBitsResolved`, struct fields :70-71), reset
   exactly where imageHash is recomputed (:732), and the hot path reads the
   cached pointer (:753-785). A cached nullptr is an answer too (the image
   lives on the overflow map). First-sight behavior — the scan, the claim,
   the entries.txt append, the calls.txt counter — is bit-for-bit the m34
   code, just executed once per image instead of once per run.
3. **EeProfVuRun gate.** The per-run profiling guard in runLoopWith (two
   steady_clock reads, the image-hash cache probe, ~11 relaxed atomic adds in
   recordVuRun, plus recordVuFallback in the loop) gains an `active` flag
   (:1758-1759 construction, :1778 fallback gate). In the slim funnel it is
   constructed active only when PS2X_VU1_PROF_RUNS is not 0 (default: on —
   see §3 for why the shipped configuration keeps it on). Inactive, it pays
   zero: no clock read, no hash probe, an early-return destructor. The
   counters feed nothing but the [ee-prof] report, so skipping them cannot
   change execution. With the slim funnel off, the guard is constructed
   active unconditionally — m34 behavior, bit for bit.

Kill switch and gates are read once into statics, like every other PS2X_
switch in the file: `vu1SlimFunnelEnabled()` (:99) — PS2X_VU1_SLIM_FUNNEL=0
reverts; `vu1ProfRunsEnabled()` (:113) — PS2X_VU1_PROF_RUNS=0 gates the prof
block (slim funnel only).

Not changed: the m34 rider (counters, stamps, [ee-prof] print), the lean
execute and fast-FMAC paths, any generated VU code (the tree's `generated/vu1`
is byte-for-byte what m33/m34 shipped), finishVu1Program, scheduling, or any
game-visible behavior. `libpcsx2gs.so` untouched: byte-identical in the
shipped APK, md5 `0fc367db8b63cd733d1bccd8c19b8574`, as in every build since
m27. Renderer default: the shipped core is the variant build
(GODHAND_PATHB_DEFAULT_PCSX2=ON at package time), so the compiled-in pcsx2
default governs and the device ini cannot poison the test.

## 2. Bench gates

### 2a. Fixture bench — behavior neutrality (the gate that arbitrates the code)

Fixture bench over the m17-input captures (5 images, 22 entries), the m34
pipeline's composition (stageprog vu_bench.o + libvu1_ship.a compiled from the
tree's generated/vu1 + the host libps2_runtime.a under test). RESULT/TRIVIAL
lines (digests + cycle counts) were compared in six environment combinations:
lean on/off × slim on/off, plus capture-on and prof-off runs.

- **Slim on ≡ slim off, exactly, in every combination** — the collapsed path
  and the cached bitset produce bit-identical runs to the m34 funnel they
  replace, and the kill switch is a true revert, not an approximation.
- **m35 ≡ m34 code, exactly**: a reference bench linked against the pristine
  m34 host library (snapshotted before the edit) produces byte-identical
  RESULT/TRIVIAL output to the m35 bench, whole-directory and per-image,
  lean on and lean off.
- **Against the published m34 result files, an honest caveat** — the same
  swamp the m34 report recorded for its own first bench links. The published
  digest set is **not reproducible in this environment by any reference
  binary**: the shipped bench-m33 binary and the m34 worker's own bench-m34
  binary, run today, diverge from the published files on exactly the lines
  bench-m35 diverges on (my output is byte-identical to bench-m34's output
  today, 0 diff lines), and their cycle counts agree with bench-m35's on
  every line except the three the divergence touches. The divergence is
  confined to images after the first and has the fingerprint of the bench
  process's cross-image state (per-image runs move it: run alone, image
  5dce334b68799d95 reproduces image cd8f1fc2068bd71d's published lines
  exactly, and its own published lines are reproduced by no run today).
  First-image lines (2cfd — including the 0x0520 wall) reproduce the
  published digests and cycle counts exactly in every combination. This is a
  property of the bench harness in this environment, not of any core change:
  it appears with zero code change, in the reference binaries themselves.
  The controlled comparison above (same harness, same link, same day,
  m34-lib vs m35-lib) is the arbitration this gate can actually give, and it
  is exact.

### 2b. Per-call overhead micro-bench (the funnel, isolated)

New harness (`funnel-build-work/micro_bench.cpp`, fixture/prime/digest code
mirroring vu_bench8k.cpp; its sanity line reproduces the fixture bench's
entry-0 digest 0bcf54455b193756 / 39 cycles): one persistent VU1Interpreter,
the trivial 39-cycle compiled entry in a tight execute() loop, capture on,
lean on — ns per execute() call, median of 12 blocks × 3,000 calls, runs
interleaved to cancel machine drift. Host (x86-64) numbers:

| configuration | median ns/call |
|---|---|
| m34 funnel (pristine m34 host lib) | ~318 (runs: 318.5 / 323.8 / 316.2) |
| m35, slim on (shipped configuration) | ~316-332 (315.9 / 315.0 / 318.4; one noise-hit run discarded) |
| m35, slim off (kill switch) | 319.0 |
| m35, slim on + PS2X_VU1_PROF_RUNS=0 | ~136 (135.9 / 137.6 / 135.0) |
| m35, slim off + PS2X_VU1_PROF_RUNS=0 | 318.6 (gate inert with slim off, as designed) |

Read plainly: changes 1+2 shave the dispatch, but at host scale the whole
funnel is only ~0.32 µs/call and the delta is inside the run-to-run noise
band — the host cannot price them more precisely than "not slower, a few ns
leaner". The one large, exactly measurable funnel cost on host is the
EeProfVuRun block itself: **~180 of the ~318 ns/call (~57%)**, and change 3
takes it to zero for non-profiling runs. The shipped configuration keeps
profiling on (§3), so that saving is available to any future profiling-off
build, not claimed for this one's device verdict. The device numbers — where
the residue is 3.72 µs/run, not 0.32 — are the arbiter, per the analysis.

## 3. Expected device effect (not inflated)

The analysis priced this change set at **worker busy −2 to −4 %** in crowd
windows, and that remains the honest expectation for m35 as shipped: the
collapse and the bitset cache remove real per-invocation work, but the
residue they attack is mostly re-entry cost that no dispatch diet reaches.
Anything beyond −4 % would mean the re-entry reading was wrong (see the
confirmation rule in §4) — a result to welcome from the log, not to promise
in advance. The prof gate is deliberately **not** part of the shipped
expectation: PS2X_VU1_PROF_RUNS defaults on because the [ee-prof] vu1 figure
is half of the rider arithmetic (gap = vu1worker − vu1) — turning profiling
off on the test device would blind the very instrument that judges this
build.

## 4. Device test (owner)

Install `godhand-0.36-m35-funnel.apk` over m34 (same signer; no uninstall).
Play normally for a few minutes, then into a crowd/fight, as for m33/m34.
Return the boot log (`files/godhand-boot.log`, zipped as last time).

Arbiter readings, against m34's pooled gameplay baselines (windows 6–23,
cb = 2,390,007): **gap 3.93 µs/run, residue 3.72 µs/run** (pre 0.111,
post 0.098). Read from the new log's [ee-prof] lines: per window,
gap µs/run = gap ms ÷ cb; residue = gap − pre − post, likewise. Judge by
**total callback time** (vu1worker ms per window at a given field count, and
the fields/s floor in crowd windows), not by gap share alone — shares move
when the session's mix moves, µs/run against a comparable crowd is the
figure that convicts or clears.

Confirmation rule (from the analysis): if device residue falls **below
0.3 µs/run**, the residue really was instruction work in the funnel and the
re-entry reading is **confirmed wrong** — the funnel held ~3.4 µs/run of
removable cost and further funnel work is the front. If residue stands at
~3.7 µs/run while total callback time is unchanged, the re-entry reading
stands and the funnel is done as a front. Conservation must hold in every
window (pre + post ≤ gap); automatic fail: any new missing geometry or
visual corruption (none is expected — no game code path changed).

## 5. Packaging arbitration (vs the shipped m34 APK)

Per-file, excluding nothing: 9 entries in both APKs, identical name sets.
Differing: `AndroidManifest.xml` (versionCode 42 → 43, versionName
0.35-m34-rider → 0.36-m35-funnel; pool rebuilt 2,448 → 2,452 B),
`lib/arm64-v8a/libgodhand.so` (the m35 variant core, md5
`25674ec907300890e2c7ab37febae013`, +5,080 B over m34's variant — the fused
dispatch and the gates), and the three META-INF signature files
(regenerated by signing, same sizes). The remaining 4 payload entries —
including `lib/arm64-v8a/libpcsx2gs.so` at md5
`0fc367db8b63cd733d1bccd8c19b8574` — are byte-identical to m34's.

Build discipline, same as m34: stage core (option OFF) md5
`6f1935f240a2957ec5ec655d35b34765`; variant core built by flipping
GODHAND_PATHB_DEFAULT_PCSX2=ON in the same tree; after packaging the option
was flipped back OFF and the stage core reproduced byte-exactly
(`6f1935f2…`), leaving the tree in stage state. Both new env strings
(PS2X_VU1_SLIM_FUNNEL, PS2X_VU1_PROF_RUNS) are present in the shipped core.
