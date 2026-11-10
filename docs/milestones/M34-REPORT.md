# PATH-CALLBACK rider build: m34 (0.35-m34-rider, versionCode 42)

**Verdict: built, bench-gated, packaged. m34 = m33 (0.34-m33-callback,
versionCode 41) plus exactly one thing: the gap-decomposition rider designed in
PATH-CHARPROG-STEP0 §6. No game code path changes; the fixture bench reproduces
bench-m33 digest-for-digest in both lean regimes. This build exists to answer
one question on the device: where does the 15.0 % vu1worker-minus-vu1 "gap"
that survived m33 actually live — before the run, after the run, or inside
execute()'s own prologue (the residue)? Nothing is published; delivery is the
zip below.**

Artifacts:

- APK: `godhand-0.35-m34-rider.apk`, 140,350,069 bytes,
  md5 `a7dd5ffb55396e20a39c16a1de9c60e8`. (Total size equals m33's APK to the
  byte: the +712 B core growth was absorbed by zipalign page-alignment padding
  on the stored .so entries; the per-file arbitration in §4 proves the content
  delta is real.)
- Zip: `godhand-0.35-m34-rider.zip` (the APK alone, deflated),
  35,463,494 bytes, md5 `0e166fb97ba5b839826cb5d21cbb532d`.
- Installs over m33 as an update: same package `dev.ptraaa.godhand`, same
  probe signer (CN=Probe, cert SHA-256
  9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e),
  apksigner v2+v3 verified, aapt badging: versionCode 42,
  versionName 0.35-m34-rider.
- Workbench: `rider-build-work/` (rider.patch, orig/ pre-edit copies of the 3
  touched files, build logs, replay/ bench outputs, packaging scripts,
  libgodhand-stage.so / libgodhand-variant.so).

## 1. What the rider measures

The m33 device log priced the callback gap at 15.0 % of EE worker time
(3.26 µs per VU1 run): the `vu1worker` span (the wall time of the whole
MSCAL/MSCNT callback body, worker-thread runs only) minus the `vu1` span (the
generated program itself). m33's lean-execute diet attacked that gap and moved
worker µs/run by only −0.9 % — the gap is not scheduler-reset overhead. §6 of
PATH-CHARPROG-STEP0 designed the decomposition; this build ships it verbatim:

- Two new cumulative nanosecond counters, accumulated **only under the same
  condition as the existing vu1CbWorkerNs** (the run happened on a worker
  thread, i.e. `!onEeThread`), so pre/post/cb are directly comparable to the
  vu1worker and cb figures already in the log:
  - `pre` — callback entry → the instant the shipped vu1worker timer starts
    (t1−t0). Covers the callback's own setup before the timed span: the
    onWorker check, the vector-unit context fetch, the fbrst fetch and the
    d/t-bit writes into VU1 control registers.
  - `post` — finishVu1Program's return path: the instant execute()/resume()
    returns → finishVu1Program's return (t3−t2). Covers XGKICK processing and
    the post-run notification.
  - residue = gap − pre − post (computed by the log reader, not stored):
    whatever is left is inside the timed run call but outside the generated
    program — execute()'s own prologue (program-registry lookup, recorder
    construction, the lean dirty check) and epilogue.
- Four `steady_clock` stamps per callback (t0 entry, t1 = the pre-existing
  vu1worker start stamp, t2 = the pre-existing post-run stamp already taken
  for recordVu1Callback, t3 after finishVu1Program). Only t0 and t3 are new
  clock reads; accumulation is two relaxed atomic adds. By construction the
  added cost is two clock reads + two atomic adds per run, far under the
  design's 0.1 µs/run ceiling; the device log's worker µs/run against m33's
  baseline is the empirical check.
- Conservation check for the reader: per [ee-prof] window, pre + post ≤ gap
  must hold (both are sub-spans of the callback body the gap is carved from).

The [ee-prof] line gains one segment, appended immediately after the existing
vu1worker figure (EeScheduler.cpp:531 format, args at :550):

```
vu1worker %llu/%.1f ms gap %.1f ms (pre %.1f post %.1f, cb %llu)
```

`gap` is the same vu1worker − vu1 delta as before (now printed, previously
only derivable), `cb` is the window's worker-run count, so pre/post also read
as µs/run by dividing by cb.

### Stamp map (file / lines, final source)

`runtime/ps2xRuntime/src/lib/ps2_runtime.cpp`, both VU1 program-run callbacks
inside `Ps2Runtime::syncCoreSubsystems`:

- MSCAL callback: t0 `eeProfT0Entry` at :1136 (first statement); t1
  `eeProfT0` at :1149 (the pre-existing vu1worker stamp); `m_vu1.execute(...)`
  at :1150; `finishVu1Program(..., "MSCAL", ...)` at :1154; t2/t3 bracket it
  via the recordVu1Callback call at :1160-1163; gap accumulation at :1157-1159.
- MSCNT callback: t0 at :1177; t1 at :1191; `m_vu1.resume(...)` at :1192;
  `finishVu1Program(..., "MSCNT", ...)` at :1196; accumulation at :1199-1201.
- The on-EE-thread test was hoisted into a local `eeProfOnEeThread` holding
  the exact expression formerly inline in the recordVu1Callback call
  (`m_eeScheduler && std::this_thread::get_id() ==
  m_eeScheduler->executorThreadId()`); recordVu1Callback receives the same
  value as before — no behavior change, it only lets the rider test the same
  condition.
- Counters: `g_vu1CbWorkerPreNs` / `g_vu1CbWorkerPostNs` atomics at :2091-2092
  (beside `g_vu1CbWorkerNs`); `recordVu1CallbackGap` at :2238-2242 (two relaxed
  fetch_adds); snapshot loads at :2292-2293.
- `runtime/ps2xRuntime/include/runtime/ee_prof_stats.h`: Snapshot fields
  `vu1CbWorkerPreNs` / `vu1CbWorkerPostNs` at :43-44; `recordVu1CallbackGap`
  declared at :73, beside recordVu1Callback.
- `runtime/ps2xRuntime/src/lib/Kernel/EeScheduler.cpp`: window deltas
  `vu1GapPreMs` / `vu1GapPostMs` at :521-522 (beside `vu1GapMs` at :520);
  format + args as above.

One design nuance, stated plainly: the shipped vu1worker timer starts at t1,
not at callback entry — so `pre` sits just *outside* the vu1worker span by
construction. It is still inside the callback body and inside EE worker time,
which is what the decomposition needs: if `pre` eats the gap's share, the
money is in the callback layer before the timed span even starts.

## 2. Diff scope — what changed vs m33, and what did not

The complete source delta is `rider-build-work/rider.patch` (143 lines,
3 files: ee_prof_stats.h, ps2_runtime.cpp, EeScheduler.cpp) — pure
instrumentation: two counters, four stamps, one print segment. Pre-edit
originals are kept in `rider-build-work/orig/`; removal is reversing that one
patch. Nothing else:

- No generated VU code touched (the tree's `generated/vu1` is byte-for-byte
  what m33 shipped; the bench gate in §3 links it fresh from the tree).
- No change to execute()/resume(), finishVu1Program, the lean-execute or
  fast-FMAC paths, scheduling, or any game-visible behavior. The only
  executable-code edits are the stamps, the hoisted boolean (same value), and
  the two counter adds.
- `libpcsx2gs.so` untouched: byte-identical in the shipped APK,
  md5 `0fc367db8b63cd733d1bccd8c19b8574`, as in every build since m27.
- Renderer default: the shipped core is the variant build
  (GODHAND_PATHB_DEFAULT_PCSX2=ON at package time), so the compiled-in pcsx2
  default governs and the device ini's `PS2X_GS_RENDERER=native` cannot poison
  the test — same discipline as m31-m33.

## 3. Bench gates (local, vs m33's published numbers)

Fixture bench over the m17-input captures (5 images, 22 entries), linked as:
the
...[truncated 5836 chars]