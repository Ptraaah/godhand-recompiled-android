# PATH-RESIDENT build: m36 (0.37-m36-resident, versionCode 44)

**Verdict: built, bench-gated, packaged. m36 = m35 (0.36-m35-funnel,
versionCode 43) plus exactly the residency experiment prescribed by
M35-LOG-ANALYSIS §5: the two 16 KB VU1 memory images co-located in one
64 KB-aligned arena, a prefetch of the VU working set at the head of the
fused dispatch, and a fail-soft pin of the VUThread to one performance
core — all three parts behind the single kill switch
PS2X_VU1_RESIDENT=0, which restores m35 behaviour in this same binary.
The m34 rider counters are untouched and still print, so this build's
device log arbitrates itself the same way m34's and m35's did. Nothing
is published; delivery is the zip below.**

Artifacts:

- APK: `godhand-0.37-m36-resident.apk`, 140,366,453 bytes (equal to
  m35's APK to the byte: the +2,328 B core growth was absorbed by
  zipalign page-alignment padding on the stored .so entries; the
  per-file arbitration in §5 proves the content delta is real),
  md5 `b8e67a4d26efb4385066a135a13f7330`.
- Zip: `godhand-0.37-m36-resident.zip` (the APK alone, deflated),
  35,758,472 bytes, md5 `bfa633537f8484f05ad0b1b2d11671dc`.
- Installs over m35 as an update: same package `dev.ptraaa.godhand`, same
  probe signer (CN=Probe, cert SHA-256
  9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e),
  apksigner v2+v3 verified, aapt badging: versionCode 44,
  versionName 0.37-m36-resident.
- Workbench: `resident-build-work/` (resident.patch, orig/ pre-edit copies
  of the 4 touched files, arena_test.cpp, micro-bench binaries, replay/
  bench outputs, packaging scripts, libgodhand-stage.so /
  libgodhand-variant.so).

## 1. The change set (nothing beyond the analysis's prescription)

Complete delta: `resident-build-work/resident.patch` (255 lines, 4 files).

1. **Co-locate the images** (`runtime/ps2xRuntime/include/runtime/ps2_memory.h`,
   `runtime/ps2xRuntime/src/lib/ps2_memory.cpp`). New member
   `m_vu1Arena` (ps2_memory.h:413). At `PS2Memory::initialize`
   (ps2_memory.cpp:719-731), when the resident layout is on, one
   `posix_memalign` allocation of PS2_VU1_CODE_SIZE + PS2_VU1_DATA_SIZE
   (32 KB) at 64 KB alignment (allocator at :54) backs both images:
   `m_vu1Code == m_vu1Arena`, `m_vu1Data == m_vu1Arena + 16384`. The
   destructor (:583) and the initialize cleanup lambda (:632) free the
   arena once via `free()` in that layout and `delete[]` each image in
   the classic layout. Accessors (`getVU1Code/Data`, `mapRange`) are
   unchanged, so DMA, VIF and save-state paths
   (`ps2_save_state.cpp` reads the same two pointers) are untouched
   semantically. If the arena allocation fails, initialize falls back
   to the classic layout — placement is an optimisation, never a boot
   dependency. The interpreter object itself is *not* moved, per the
   analysis (it is embedded by value in PS2Runtime).
2. **Prefetch at the funnel mouth** (`runtime/ps2xRuntime/src/lib/vu/ps2_vu1_core.cpp`).
   At the head of `VU1InterpreterAccess::runFused` (:1659-1673), before
   the capture gate: `__builtin_prefetch` of the interpreter's state
   block (:1667), the code-image line containing the entry PC (:1669),
   and the data image's first line (:1671) — issued while the remaining
   prologue executes, so the refills overlap the work that currently
   pays for them serially. Prefetch is side-effect-free by construction.
3. **Pin the thread** (`runtime/ps2xRuntime/src/lib/ps2_async_vif.cpp`).
   At VUThread start (`run(Worker&)`, call at :348-349),
   `pinVuThreadToPerformanceCore()` (:314) `sched_getaffinity`s the
   allowed set, picks its highest-index CPU (the prime/performance core
   on the target SoCs), and `sched_setaffinity`s the thread to it.
   Fail-soft: any failure logs one `[vu-resident]` line to stderr and
   the thread runs unpinned — a refused pin is never a boot failure.

Kill switch: **PS2X_VU1_RESIDENT=0** reverts all three parts (classic
`new[]` allocations, no prefetches, no affinity call), read once into a
static in each translation unit like every other PS2X_ switch in the
tree (ps2_memory.cpp:41, ps2_vu1_core.cpp:129, ps2_async_vif.cpp:298).

Two honest deviations from the analysis's letter, neither of which
changes what the device measures:

- The analysis lists the arena as "first-touched by the VUThread". The
  arena is allocated and zeroed in `initialize()` on the constructing
  thread, as the classic images were: the images must read as zero to
  DMA/EE readers that can touch them before the worker's first job, so
  the zeroing cannot move to the worker without racing them. The
  placement term of the experiment (scattered heap allocations → one
  known aligned arena) is implemented exactly; the first-touch thread
  is not.
- The prefetch rides the fused dispatch, as the analysis specifies
  ("at the head of the fused dispatch"). With PS2X_VU1_SLIM_FUNNEL=0
  there is no fused dispatch and therefore no prefetch; the arena and
  the pin are independent of the slim funnel. The shipped
  configuration runs the fused dispatch, so all three parts are live.

Not changed: the m34 rider (counters, stamps, [ee-prof] print), the m35
slim funnel, the lean execute and fast-FMAC paths, any generated VU
code (the tree's `generated/vu1` is byte-for-byte what m35 shipped),
scheduling, or any game-visible behaviour. `libpcsx2gs.so` untouched:
byte-identical in the shipped APK, md5 `0fc367db8b63cd733d1bccd8c19b8574`,
as in every build since m27 (per-file arbitration in §5). Renderer
default: the shipped core is the variant build
(GODHAND_PATHB_DEFAULT_PCSX2=ON at package time — the core carries the
path-B variant literals and defaults PS2X_GS_RENDERER to pcsx2 in the
compiled-in layer), so the device ini cannot poison the test.

## 2. Bench gates

### 2a. Fixture bench — behaviour neutrality (the gate that arbitrates the code)

Fixture bench over the m17-input captures (5 images, 22 entries),
composition as for m35 (stageprog vu_bench.o + libvu1_ship.a compiled
from the tree's generated/vu1 + the host libps2_runtime.a under test),
plus a reference bench linked against the pristine m35 host library
snapshotted before the edit.

- **Whole-process runs cannot arbitrate in this environment — proven,
  not assumed.** The m36 binary run twice under identical env diverges
  from itself (28 diff lines); the pristine m35 reference binary run
  today diverges from the funnel workbench's published m35 files on the
  same image-after-the-first lines with the same digest-swap
  fingerprint the M35 report documented (the bench swamp: cross-image
  state in one process, address-layout sensitive). One pairing today
  (ref35 vs m36-resident-off) happened to agree on all 29 lines; the
  pairing is luck, not signal.
- **Per-image runs are the arbitration, and they are exact.** Each
  image run alone in its own process (the funnel workbench's perimg
  dirs): resident-on ≡ resident-off in *every* combination — lean
  on/off × slim on/off — for all 5 images, and resident-on ≡ the
  pristine m35 reference binary under the shipped configuration. The
  concatenated resident-on per-image set is byte-identical to the
  funnel workbench's published per-image m35 results
  (pi-lean-slimon-results.txt, 29 RESULT/TRIVIAL lines, digests and
  cycle counts). Nothing here alters a single VU cycle, in either
  layout — any divergence would have been an automatic fail.
- **Kill switch verified at the layout level** by the arena unit test
  below (resident-off constructs the classic two-allocation layout in
  the same binary) and at the behaviour level by the per-image
  resident-off runs above.

### 2b. Arena unit test (the layout contract the bench cannot see)

`resident-build-work/arena_test.cpp`, linked against the host runtime
lib, constructs a real PS2Memory in both modes. ALL PASS in both:

- Resident: arena allocated, 64 KB-aligned; code == arena base;
  data == arena + 16 KB; images zero-initialised; boundary pattern
  (last code byte / first + last data byte) intact; accessors agree
  with the raw pointers — the exact contract `ps2_save_state.cpp`'s
  `ar.raw(mem.m_vu1Code, …)` / `ar.raw(mem.m_vu1Data, …)` relies on, so
  the save-state round-trip through the arena is covered at the pointer
  level (its code is unmodified).
- Re-initialize (the cleanup lambda against the live allocation) and
  destruction both survive in both layouts; classic mode shows no
  arena and two independent images.

### 2c. Per-call overhead micro-bench (expected blindness, confirmed)

The funnel micro-bench (one persistent VU1Interpreter, the trivial
39-cycle entry in a tight execute() loop; it constructs its PS2Memory
through the arena path when resident is on), interleaved runs, median
ns/call: m36 resident-on 318.7 / 320.6 / 317.3; m36 resident-off
332.9 / 327.4 / 317.4; pristine m35 reference 317.2 / 317.7 (and one
noise-hit 407.7, discarded as the m35 runs also discarded theirs).
Everything sits inside the m35 report's published ~316-332 ns/call
band. As the analysis stated in advance: the host loop is already
resident, so it *cannot* price this change and must show ~no delta —
a large host delta would itself have been a red flag. There is none;
the device log is the only arbiter, same as for m35.

## 3. Expected device effect (not inflated)

No instruction is removed and no program changes; the entire bet is
that the 3.33 µs/run residue is per-invocation cache/TLB refill of the
VU working set, and that placement (part 1), earlier refill (part 2)
and no migration (part 3) cut its price. The analysis's realistic
device target is **residue ≤ 2.5 µs/run**; the full 3.33 is not
promised — the generated code's own pages also cycle between the stage
and character images inside a job, and this build does not touch that.
Translated to feel *if* the target is hit: ~0.8 µs/run off the callback
is ~4 % of the 21.90 µs callback — the crowd fields/s floor moving a
few points, not a transformation. If the pin is refused by Android's
cpuset policy the log shows the `[vu-resident] ... running unpinned`
line and parts 1+2 still stand on their own.

One risk is pre-named by the analysis: pinning the VUThread to the
prime core puts it beside the game thread's preferred core. If the log
shows EE-side inflation (native ms per window up at equal fields), the
pin target moves to the second performance core in a rider — it is not
a reason to rebuild the experiment.

## 4. Device test (owner) and pre-registered gates

Install `godhand-0.37-m36-resident.apk` over m35 (same signer; no
uninstall). Play normally for a few minutes, then into a crowd/fight,
as for m34/m35. Return the boot log (`files/godhand-boot.log`, zipped
as last time). The boot log should also show one `[vu-resident]` line
near VUThread start (pinned, or the fail-soft unpinned line).

Read from the new log's [ee-prof] lines exactly as for m35: per window,
gap µs/run = gap ms ÷ cb; residue = gap − pre − post, likewise. Judge
by **total callback time** and the crowd fields/s floor, not by gap
share. The gates below are registered now, against the M35-LOG-ANALYSIS
pooled baselines (52 gameplay windows, cb = 7,203,522): **residue
3.333 µs/run, gap 3.524 µs/run, total callback 21.90 µs/callback,
fields/s floor 42.3 / median 53.6**. They do not move after the log
arrives.

- **Kill:** residue within ±0.15 µs/run of 3.333 **and** total callback
  time within ±2 % of 21.90 µs/callback at comparable crowd windows →
  re-entry is not priced by placement/pinning either; the residency
  front closes and the residue is accepted as the cost of the async
  architecture, with effort returning to the program walls' *call
  count* (fewer, larger invocations) as the only remaining lever on
  this term.
- **Success:** residue ≤ 2.5 µs/run with conservation intact and the
  crowd fields/s floor above 42.3 → residency becomes the default and
  part 2's prefetch set is tuned (image-pair prefetch for the
  alternating 0x0358/0x0520 pattern) as a follow-up.
- Automatic fail, as always: any new missing geometry or visual
  corruption; dropped_captures ≠ 0; conservation (pre + post ≤ gap)
  violated in any window.

## 5. Packaging arbitration (vs the shipped m35 APK)

Per-file, excluding nothing: 9 entries in both APKs, identical name
sets. Differing: `AndroidManifest.xml` (versionCode 43 → 44,
versionName 0.36-m35-funnel → 0.37-m36-resident; pool rebuilt
2,452 → 2,456 B), `lib/arm64-v8a/libgodhand.so` (the m36 variant core,
md5 `f9329c7a7b3694672045830e6730ff7f`, 101,470,480 B, +2,328 B over
m35's in-APK variant — the arena/prefetch/pin code), and the three META-INF
signature files (regenerated by signing, same sizes). The remaining 4
payload entries — including `lib/arm64-v8a/libpcsx2gs.so` at md5
`0fc367db8b63cd733d1bccd8c19b8574` — are byte-identical to m35's.

Build discipline, same as m35: stage core (option OFF) md5
`65d5ed462eea383274c4af4abfb9b7d7` (101,463,328 B, zero path-B variant
literals, resident strings present); variant core built by flipping
GODHAND_PATHB_DEFAULT_PCSX2=ON in the same tree (path-B variant
literals present, as in m35's variant); the option was then flipped
back OFF and the stage core rebuilt to `65d5ed46…`, leaving the tree
in stage state. (The pipeline is deterministic — the variant md5 was
produced identically by two separate links during this build — so the
flip-back stage core is the stage core of record.) All three
PS2X_VU1_RESIDENT read sites and the `[vu-resident]` pin lines are
present in the shipped core. Two build notes: the ps2_memory.h change
invalidated the Android PCH, so the first Android build recompiled
the generated game code in full — no generated source was modified,
and the stage/variant arbitration above is unaffected; and a VM
reboot mid-build cost one build session, which ninja resumed from
its persisted state with no source impact.
