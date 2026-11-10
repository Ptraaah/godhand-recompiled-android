# PATH-CALLBACK (m33) — build report

Build: **0.34-m33-callback** (versionCode 41), package `dev.ptraaa.godhand`, signed with the
same probe key as every path-B build, installs over 0.33-m32-throughput with no data loss.

What this build is: the **L2 callback diet** from `M32-LOG-ANALYSIS.md` / `PATH-THROUGHPUT.md`
section L2, on top of the shipped m32 tree. L1 (fast FMACs) stays ON as the new baseline.
Two parts, both inside the VU1 run callback path:

- **(a) Lean execute** — `execute()` no longer pays the full `resetScheduler()` (~3.5 KB of
  scheduler memsets) on every run. A run that ended clean (program end reached, no
  interpreter pair executed, no stop requested) leaves the scheduler in exactly the state a
  full reset produces, apart from the free-running cycle counter; the next `execute()`
  detects that with a conservative dirty bit and only does the small partial clear.
- **(b) Capture bitset** — the capture hook no longer does a per-run `unordered_map`
  increment; a per-image 2048-bit entry bitset answers "entry already recorded" with one
  atomic load, and the map/file append happens only on the first sight of an (image, entry).

Plus one behaviour-neutral passenger the m32 analysis asked for: the `[ee-prof:top]` VU
section now splits by **image hash**, so the two device copies of pc 0x0358 stop merging.

Kill switch: **PS2X_VU1_LEAN_EXECUTE=0** restores the exact m32 callback behaviour in the
same binary (full reset every run, observability outputs unchanged). Boot announces the
mode next to the fmac line:

```
[vu1-aot] lean execute: on (PS2X_VU1_LEAN_EXECUTE=0 restores the per-run scheduler reset)
[vu1-aot] lean execute: off (PS2X_VU1_LEAN_EXECUTE=0 is set; unset it to restore lean)
```

Variant seeding follows the established pattern: with `GODHAND_PATHB_DEFAULT_PCSX2`
compiled in, the ini key `ps2x_vu1_lean_execute` defaults to `1` (on) when the device ini
does not set it, and the compiled-in variant default wins over the device ini state.

## The correctness question, answered up front

The risk of this lever is scheduler-state staleness: if a skipped reset ever leaves residue
the next run can observe, the failure mode is wrong values / intermittent visual
corruption. The design rests on an induction, and the soak tests it three independent ways
(all in the bench section below):

1. **The invariant.** Generated code writes the vf/vi/acc ready arrays only at +1/+4
   pipeline offsets relative to the writing pair (verified by grep over all generated
   sources: 13,134 ready-write sites, offsets 1 and 4 only) and never writes the
   latest-write arrays at all (0 sites — those are written only by the interpreter paths).
   At a clean compiled end the only residue is ready entries at most +4 cycles beyond the
   final cycle. The lean skip therefore first advances the cycle counter by a gap of 16
   (4x margin, the VU0 lean precedent uses 64), which makes every residue entry lie
   strictly in the past; the consumer side only ever asks "ready cycle <= now", so residue
   is indistinguishable from the zeroed state a full reset leaves. The interpreter-pair
   case is handled by construction: any run that executes an interpreter pair (or is
   budget-cut, or is stop-requested, or follows a state load) marks the dirty bit, and the
   next run pays the full reset. A full `reset()` produces exactly the cleared state, so
   it *sets* the clean bit (the device MSCAL pattern and the bench pattern both rely on
   this); a savestate **load** clears it.
2. **The audit checker** — the analysis named the VU0 audit checker as the model; it
   exists in the tree (`runtime/ps2xTest/tools/vu0_audit.cpp`, offline, VU0-only). The L2
   port runs **in-engine** as a third mode, `PS2X_VU1_LEAN_EXECUTE=check`: every skipped
   clear first verifies the scheduler residue really is inert (latest ready across the
   vf/vi/acc arrays <= cycle after the gap, latest-write arrays all zero, write sequence
   zero) and reports any violation. Check mode computes identically to lean mode, so the
   audit and the behaviour are measured on the same runs.
3. **Sequence equality** — back-to-back `execute()` sequences (the device pattern) produce
   byte-identical result digests **and** identical per-run cycle counts in lean, off, and
   check modes over all 22 real entries.

## What changed, file by file

### `runtime/ps2xRuntime/include/runtime/ps2_vu1.h`
- New member `bool m_vu1LeanClean = false;` on `VU1Interpreter` with the contract comment:
  set only when the scheduler arrays are known to equal the full-reset state; consumed by
  the next `execute()`; VU1 only (VU0 keeps its own lean path).

### `runtime/ps2xRuntime/src/lib/vu/ps2_vu1_core.cpp`
- `vu1LeanExecuteMode()` (env `PS2X_VU1_LEAN_EXECUTE`: `0` = off, `check` = audit, else
  on) and `kVu1LeanCycleGap = 16` next to the m32 fmac gate.
- **Capture bitset**: `Vu1ProgramCapture` gains `EntryBits entryBits[8]` — per image slot a
  CAS-claimed fnv1a64 tag plus 32 atomic 64-bit words (bit index `pc >> 3`, 2048 entries).
  `captureVu1Program` sets the bit with one atomic fetch_or; only a 0-to-1 flip appends to
  `entries.txt` (exactly once per (image, entry), same bytes as before). Images beyond 8
  slots or pcs >= 0x4000 keep the old map path (renamed `entryOverflow`). The per-image
  `entryKinds` count replaces the map size in `calls.txt`; outputs are byte-identical
  (verified below).
- `reset()` sets `m_vu1LeanClean = true` (a reset *is* the cleared state).
- `execute()` (VU1 only): when the mode is not off, the clean bit is set, and all valid
  masks / divider / EFU / XGkick state are idle — advance `m_cycle` by the gap, run the
  audit in check mode, then the partial clear mirrored from `executeVu0Lean` (divider/EFU
  valids off, flag valid mask 0, next-commit cycle to max, `resetXgkick()`, write sequence
  0, EFU resource clock 0, working clip refreshed, branch backup cleared, stop/halt flags
  cleared) and skip `resetScheduler()`. Otherwise the full reset runs, exactly as m32.
  The clean bit is consumed either way.
- `runLoopWith`: tracks whether any pair ran through the interpreter; at the end of a VU1
  run it re-arms the clean bit only when the program ended (`programEnded`) with no
  interpreter pair and no stop request.
- `EeProfVuRun` computes the image hash for the split tops through a thread-local cache
  keyed on (code pointer, memory, generation, unit) — the same generation contract the
  compiled-program registry already relies on — so profiling costs no per-run hashing.
- Registration announces the lean mode at boot (lines above).

### `runtime/ps2xRuntime/src/lib/vu/ps2_vu1_impl.inl`
- `serializeState` loading branch: a state load marks VU1 dirty (`m_vu1LeanClean = false`).

### `runtime/ps2xRuntime/include/runtime/ee_prof_stats.h`
- `recordVuRun` gains a `uint64_t imageHash` parameter (0 = unattributed).

### `runtime/ps2xRuntime/src/lib/ps2_runtime.cpp`
- Per-image top storage: 16 tag slots (last one a shared overflow bucket) mirroring the
  existing per-pc arrays; `recordVuRun` updates both. `reportTopLists` prints the VU top
  as the best 6 (pc, image) rows in the same line format with an `img %016llx` tag. The EE
  and VU0 sections and the merged per-pc VU arrays are unchanged.

### `android/glue/godhand_android_main.cpp`
- `ps2x_vu1_lean_execute` ini key read into `PS2X_VU1_LEAN_EXECUTE` beside the m32 fmac
  seeding; variant builds (`GODHAND_PATHB_DEFAULT_PCSX2`) seed the default `"1"` when the
  ini does not set it. Passthrough builds behave exactly like m32.

### Harnesses (bench-only, not shipped)
- `callback-build-work/vu_fixbench.cpp` — tight-loop fixed-cost micro-bench (one chrono
  around N back-to-back executes of a clean-ending entry).
- `callback-build-work/vu_seqbench.cpp` — sequence soak: per (image, entry) a fresh-reset
  reference run, then back-to-back executes printing per-rep digests and cycle counts,
  plus the eeprof gap decomposition.

No generated VU code changed. No loads/vnorm change, no join change, no second worker.

## Bench gates (this VM, linux-m1a runtime, same discipline as m32)

VM CPU drift is severe (documented in the m32 report), so every A/B alternates modes
round by round and reports medians.

### 1. Baseline reproduction (fixture bench, all 22 entries)
- Kill-switch off: RESULT digests **and** cycle counts identical to m32's shipped fast
  bench, entry for entry (`OFF-MODE-IDENTICAL-TO-M32`).
- Lean on: all 22 RESULT **digests identical** to m32 fast; cycle counts differ by exactly
  **+16 per execute** — the lean gap itself, visible only in the absolute cycle counter.
  Per-run vcyc from the profiler (which is what the game and the device log account) is
  unchanged: the cycle origin is taken after the gap. This is the same accepted difference
  class as the VU0 lean precedent (+64 there).

### 2. Verify soak — PS2X_VU1_VERIFY=obs, both modes, before packaging
- Lean on: 50 mismatch reports. Kill-switch off: 50. m32 fast reference: 50.
- The report sets are **identical** after normalising run numbers: 42 on entry 0x0520 (both
  images, sign-of-zero class only — `00000000` vs `80000000` lanes) and 8 on entry 0x00C8
  (the 5dce synthetic-fixture artefact already present in m32's profile).
- Nothing beyond the accepted class in either mode: no wrong values, no status-bit
  divergence any image reads, no cycle-count divergence (verify's own pc/cycle equality
  held everywhere — mismatches are reported only at equal pc).

### 3. Sequence soak — back-to-back executes, all 22 entries, 8 reps each
- 176 sequence runs: digests and per-run vcyc **byte-identical** across lean, kill-switch,
  and check modes (`SEQ-IDENTICAL-LEAN-VS-OFF`, `SEQ-IDENTICAL-LEAN-VS-CHECK`).
- 6 entries diverge at rep 0 from their fresh-reset reference — the architectural-state
  carry any back-to-back sequence has (clip/branch state a fresh reset zeroes and the
  game never zeroes either). The count is **6 in both modes** and the sequences agree with
  each other exactly, so the carry is mode-independent, as designed.
- The pre-existing fixture noise (a reserved-instruction path some fixture sequences fall
  into after the first run) appears identically in m32's own bench logs (1075 occurrences
  there, 1075 in m33 kill-switch) — not an m33 effect.

### 4. Audit checker (check mode)
- Full seqbench suite in check mode: **zero** `[vu1-lean]` violation reports.
- At scale (fixbench, clean-ending entry, 100,200 executes):
  `[vu1-lean] scheduler check: 65536 skipped clears, 0 not clean` — the invariant held on
  every audited skip.

### 5. A/B — the gap, priced
The device m32 log priced the (vu1worker - vu1) gap at 3.77 us/run, 17.1% of worker time.
The bench equivalent is the per-execute fixed stack around a clean-ending run:

- fixbench (20,000 back-to-back executes of the 23-cycle prologue entry, 6 alternating
  rounds per mode): wall per execute **lean 0.521 us vs off 0.711 us** (medians; one drift
  outlier each side, discarded by the median). Loop memcpy measured separately at
  0.225 us, profiler run time for the entry ~0.12 us, so the fixed stack is:
  **off ~0.37 us -> lean ~0.18 us, a ~0.19 us saving per execute, ~51% of the fixed
  stack** — inside the 40-70% recovery band the m32 analysis priced for this lever.
- What remains in the ~0.18 us: the compiled-image lookup (a 16 KB compare in the
  registry's recent-image cache), the run() prologue, and the profiler clocks. None of
  that is L2 scope; it is the floor the *next* lever would have to attack.
- Capture on (bitset path): +~0.04 us per execute over capture off in this binary — the
  hook itself plus one atomic op. The old per-run map increment it replaces is not in this
  binary anymore; its cost class (hash + tree walk per run) is strictly above the bitset's
  single atomic load on any core, and on the device the capture hook only runs while
  capturing anyway. Capture outputs verified byte-identical in section 6.

### 6. Capture output + image split
- Bench capture run: `entries.txt` identical to the m32 binary's capture in the same
  environment. (In the bench environment both binaries capture only the first image and
  write no `calls.txt` — a pre-existing bench quirk of the pointer-keyed capture, identical
  before and after this change; production captures come from the game process, whose
  outputs the bitset preserves by construction: same append-once semantics, same text.)
- Image split proven in the same run's `[ee-prof:top]`: entry 0x0520 now appears as
  separate rows per image (`img 4dfef01657b65f6a`, `img 2cfd7f04d22a4c7f`, ...) instead of
  one merged row.

## Android builds

Both cores were built in `build/android-m2a` like m32. The header changes (ps2_vu1.h,
ee_prof_stats.h) invalidated the game-code PCH, so the stage build was a full-tree rebuild
(13,229 steps) on this contended VM; the variant build after the option flip was the glue
TU plus link (20 steps), as in m32.

Stage core (variant option OFF):
`md5 6dd69b008533ff8e7debe59847b679e0`, 101,455,208 bytes (+3,920 over the m32 stage core —
the L2 and split code).

Variant core (option ON, the one that ships):
`md5 b1357346d41c6b06a1cae2b4f62adc53`, 101,462,360 bytes (+7,152 over stage — the seed
block compiled into the glue, the same pattern and size class as m32's +6,496).

Packaging followed the m32 flow exactly (`callback-build-work/repackage_m33.py` +
`patch_axml_m33.py`): the shipped m32 APK as the donor, manifest version patched in the
binary XML (versionCode 40 -> 41, versionName string swapped to `0.34-m33-callback`, pool
rebuilt 2,460 -> 2,456 bytes), libgodhand.so swapped for the variant core, zipalign -p 4,
apksigner (build-tools 36.0.0) with the probe key.

Per-file arbitration against the shipped m32 APK (all payload entries, order preserved):

| entry | m32 md5 | m33 md5 | verdict |
|---|---|---|---|
| AndroidManifest.xml | bd5394854393ac2a4643cbb54dcc2d72 (2,460 B) | 1a3c22ddd9895b20746ce9f327b7fec1 (2,456 B) | version patch only |
| lib/arm64-v8a/libgodhand.so | d6fd6774b6a97d289ba4deaf74d4a1d1 | b1357346d41c6b06a1cae2b4f62adc53 | the new core |
| lib/arm64-v8a/libpcsx2gs.so | 0fc367db8b63cd733d1bccd8c19b8574 | 0fc367db8b63cd733d1bccd8c19b8574 | **byte-identical** |
| classes.dex, resources.arsc, libshaderc_shared.so | — | — | identical (not in the diff set) |

Only those two entries differ in the whole APK. Signature: v2+v3 verified, signer
CN=Probe, O=ptraaa, C=ID, SHA-256 9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e
— the same key as m31/m32, so the APK installs over m32 as an update. `aapt dump badging`
reads back package dev.ptraaa.godhand, versionCode 41, versionName 0.34-m33-callback.

Deliverables:

- `godhand-0.34-m33-callback.apk` — 140,350,069 bytes, md5 eaf4cb3ba015a2357c2dbae120f8f9db
- `godhand-0.34-m33-callback.zip` — 35,462,962 bytes, md5 347805cb2d73335ac406fcef2b82ef79
  (the APK alone, deflated; the APK inside the zip hashes to the same eaf4cb3b…)

## Arbitration — what Pablo's returned log must show

Baseline is the m32 return (17 windows, 4,469 fields): gap 17.1% of worker time
(6,444 ms / 3.77 us per run), pooled heavy ns/vcyc 10.28, worst join 1,040.9 ms, heavy
fields/s floor 41.6 / ceiling 55.7, 0x0358 at 22.57 us/call merged across two images.

- **Config lines (must all hold):** `[vu1-aot] fmac: fast`, `[vu1-aot] lean execute: on`,
  boot program count 5, renderer pcsx2 (variant default), `interp=0 fb=0 budget=0`,
  `dropped_captures=0`, fast-path fields all zero.
- **Premise arbiter (the lever itself):** gap share of worker time falls from 17.1% to
  **~5-10%** (per-run gap ~3.77 us -> ~1.1-2.2 us). If the gap share stays near 17%, the
  premise failed on device regardless of what fields/s does.
- **Attribution check:** pooled heavy ns/vcyc stays **~10.28** (the lever removes callback
  overhead, not program cost; if ns/vcyc moves materially, something else changed).
  Per-image 0x0358 rows appear for the first time: expect the two copies to split the old
  merged 22.57 us/call very unevenly — the split itself is information, either way it lands.
- **Expected effect, stated honestly:** worker busy in heavy windows down ~7-12%, heavy
  fields/s up ~1-3 in mid crowds, ceiling perhaps 57-59, worst join easing toward
  ~920-970 ms. **This build is not promised to reach 60 fields/s or a sub-950 ms worst
  join on its own.** The join is bimodal; this lever attacks the fixed per-run cost that
  feeds the bad mode, it does not remove the crowd's program cost (0x0358's copy split is
  the next diagnosis, now readable).
- **Automatic fail line:** any missing geometry, missing floor/walls, or visual corruption
  that appears with this build and was not in m32 — that is the staleness signature of
  this lever, and it outranks every number above. Same for a `[vu1-lean]` line in the log
  (check mode is not compiled into the default path, so any scheduler complaint at all is
  unexpected) or `dropped_captures > 0`.

## Watch signature for the owner

Play normally, then a crowd / fight. The thing to watch for is **geometry that is missing
or wrong only sometimes** — a floor, wall, or character part absent in one fight and fine
in the next. That specific pattern (intermittent, position-dependent) is this build's
failure signature; steady lag or steady low fps is not. The boot log return arbitrates the
rest.

## Reproduction / flip-back

The tree is left in the **m33 stage state**: `GODHAND_PATHB_DEFAULT_PCSX2=OFF` in
`build/android-m2a/CMakeCache.txt`, and the tree's `src/libgodhand.so` is the stage core.
Flip-back reproduction was verified the same way as m32: after the variant build, the
option was flipped back OFF and `ninja godhand` re-run; the resulting `src/libgodhand.so`
hashed to **6dd69b008533ff8e7debe59847b679e0**, byte-identical to the stage core built
before the flip. To reproduce the shipped variant: flip the option ON, `ninja godhand`,
copy `src/libgodhand.so` to `callback-build-work/libgodhand-variant.so`, flip back OFF,
rebuild, then run `callback-build-work/repackage_m33.py`, zipalign, and sign as above.

Bench harnesses and logs live in `callback-build-work/` (vu_fixbench.cpp, vu_seqbench.cpp,
vu_gapbench.cpp, replay/ with bench-m33 / gapbench-m33 / seqbench-m33 / fixbench-m33 and
their output logs, stage/variant/flip-back build logs). No game data is in any artifact,
report, or work directory — the fixtures are the same owner captures the m32 bench used.
