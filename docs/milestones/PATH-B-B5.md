# Path B, stage B5 — VU codegen diet, the EE audit, and true 16:9

Build: **0.25-m24-pathb6**, versionCode **30** — installs over pathb5 in place,
same signer. Three work items: (1) an AOT codegen optimisation for the hot VU1
programs, proven behaviour-identical on a differential bench; (2) an audit of
EE-throughput knobs, which concludes there is nothing safe left to flip in the
bridge — the remaining wall is real guest work; (3) the owner's 16:9 request,
implemented as a true widescreen patch (game-side aspect constant patched at
boot, PCSX2 aspect set to 16:9, harvest canvas exactly 16:9), default ON in the
path B variant with an ini kill switch.

## Profile evidence (the owner's pathb5 run, 2026-11-05 03:35)

From the last session of `godhand-boot.log` in the pathb5 log drop (session
starts at the second boot banner, line 499):

- MTVU is device-proven: `vu1ee` collapsed to 0.0 ms per window; the
  `vu1worker` thread carries all VU1 work (callbacks 1.87-2.57 s per
  ~4.3-5 s guest window).
- Menus/title run full speed (55.6-59.9 fields/s, EE 273-295 MHz). Gameplay
  runs 44-52 fields/s (up from 25-39 in pathb4), EE 216-256 MHz, presents
  track fields 1:1.
- EE core "native" execution is the largest single serialized cost
  (2,757-3,577 ms per window in gameplay). VU1 worker time plus the vblank
  joins are next: `[vu1]` join_wait totals 965-1,643 ms per gameplay window
  with queue_hw only 4/64 — the joins wait for in-flight work to finish, not
  for queue space. One queueWait max spike of 1184.5 ms (one-off).
- The GPU is not the wall: gsIdle ~65-88%, gs-fetch 1.8-4.8 ms/present.
- Hot VU programs from that session's `[ee-prof:top]`: pc **0x0520** (up to
  1,020 ms, ~70k calls, ~14.6 µs/call), **0x0358** (498-795 ms), **0x2378**
  (118-240 ms), 0x00C8 (121 ms once). Interpreter runs: 0 — everything hot is
  compiled code.
- Audio choppiness is the known half-speed symptom: the audio follows the
  field rate, so it clears as fields approach 59.94/s. It is not an audio bug
  and B5 does not touch the audio path.

Target remains a locked ~59.94 fields/s in gameplay. B5 attacks the VU side
of the serialized remainder; the honest expectation is a few fields, not a
lock — the EE-side margin is tight (see the audit below).

## Work item 1 — VU1 AOT: dead-normalization elimination + -O3 for the VU library

Diagnosis. The compiled VU code keeps two copies of every vector register: the
raw value the interpreter model uses, and a "normalized" shadow (NaN/Inf and
range-canonicalized) that only some operations read. In the pair-emitted
(non-segment) code, **every** vector write eagerly computed the normalized
copy (`vnorm`, ~10 vector ops), even in long streaming stretches
(load-quad -> store-quad / float-to-int) where nothing ever reads the
normalized lanes before the register is overwritten. The hot 0x0520 image is
full of exactly those stretches.

Change (translator + one runtime helper, same pattern as the existing
dead-flag elimination):

- `vu1_recomp` gained a norm-liveness pass over the static block graph: a
  write's normalized copy is dead when no successor path reads that
  register's normalized lanes before overwriting them. Reads are probed from
  the emitter's own output; any block that can fall back to the interpreter
  (fallback/reserved/generic/dynamic-branch) is a barrier that reads all
  registers and kills none. Fixed point over the successor graph; a block
  with no successors is a program exit (norms dead there — the runtime
  re-syncs at the boundary).
- Dead writes are emitted as `writeVfRaw`/`writeVfRawFull`: raw store only,
  plus a dirty bit in the fast context. `runFast` re-materializes any dirty
  normalized copies (`fixupNorm`) after every block dispatch and before
  returning, so the persistent shadow is whole at every point another engine
  (interpreter, another image, a resume) can observe. Segment (register
  cached) emission is untouched.
- Result on the five captured images: **353 writes** across the images no
  longer compute a normalized copy. The generated diff versus the previous
  generation is substitution-only (each changed line is a
  `writeVf(..., vnorm(x), ...)` becoming `writeVfRaw(..., ...)`; verified by
  diff inspection).
- Kill switch: `VU1_RECOMP_NO_DEAD_NORM=1` at translation time restores the
  previous codegen byte-for-byte (verified: the regenerated tree with the
  switch set is identical to the pre-B5 generated tree). The translator's
  "writes with dead norm" stat reports analysis candidates either way; the
  switch gates emission.
- Additionally, the `godhand_vu1_code` library (only the generated VU
  programs) now compiles at **-O3** instead of the project-wide -O2
  (`src/CMakeLists.txt`; no fast-math — IEEE float semantics are unchanged).
  Revert by deleting that block.

Proof (differential bench, `m24f-fixture/vu_bench.cpp`, kept in the project
dir). For every captured image and every entry point in the capture's
`calls.txt` (22 entries), the bench runs the program from an identical
synthetic fixture (deterministic data memory + register file) and digests the
interpreter's effective state plus the full data memory:

- old compiled vs new compiled vs new compiled at -O3: **identical digests,
  identical cycle counts, identical fixture selection on all 22 entries.**
- Interpreter vs compiled (old and new agree with each other here): exact
  agreement on the two images that carry the hottest entries (0x0520/0x00B8
  run bit-exact for the full 400k-cycle budget) and on most others. A few
  entries on synthetic fixtures diverge between interpreter and compiled code
  (both generations identically) — e.g. 5dce 0x00C8 ends at 4,866 cycles
  interpreted but runs to budget compiled, and one exit differs by a single
  cycle with identical final state. These are pre-existing,
  fixture-dependent divergences (the fixture presents VIF/register state the
  game never does); they are unchanged by B5, and the compiled code remains
  the device-proven path. They are recorded here so nobody rediscovers them
  as a B5 regression.

Host timing honesty: this VM was heavily contended during B5 (load ~5 on 4
cores from parallel work). Interleaved A/B runs of the bench (17 rounds,
per-entry minimums and medians) show the new code **indistinguishable from
the old within noise** on the x86 host (medians for the hottest entry:
25.32k vs 25.29k ns per 1000 cycles). The change removes real work — 353
`vnorm` evaluations across the images plus better -O3 scheduling headroom —
and is proven free of behavioural risk, but no host-side speed claim is
made. The device run is the arbiter, as usual; check the `[ee-prof:top]`
per-program times for 0x0520/0x0358 against pathb5's numbers above.

## Work item 2 — EE throughput audit: nothing safe left to flip

Every candidate the bridge/core exposes was checked against the profile:

- The EE block-lookup fast path (`PS2X_EE_SINGLE_LOOKUP`) already defaults
  ON; the bridge leaves nothing conservative there.
- `PS2X_EE_LOOP_CYCLES` (default 32) is a guest-cycle *charge* per backward
  branch — a timing-model constant, not a speed knob. Changing it would
  distort guest timing, not accelerate anything.
- Scheduler checkpoint batching (defer-budget) already exists.
- memslow is only ~44-250 ms per gameplay window (the one 1,437 ms window
  was a full-speed menu window) — not the wall.
- Gameplay "native" time is spread across the whole game; there is no
  single hot guest function to attack (menu windows show the menu idle loop
  at 96-97%, which is expected). The EE is executing real game code at
  ~0.87 host:guest ratio in gameplay windows; the remaining lever on the
  serialized path is the VU time inside the vblank joins, which is work
  item 1.
- `PS2X_THREAD_POLICY` is the owner's own shell toggle (the pathb5 run had
  threads off — his setting, left alone). It stays available as an A/B.

**No EE change ships in B5.** Anything else would be a guess dressed as a
tuning win, against the profile's evidence.

## Work item 3 — 16:9: true widescreen, default on, ini kill switch

Route. The canonical community widescreen hack for this title (SLUS-21503)
patches the aspect constant the game builds its projection with, from
0x3F6EEEEF (4:3) to 0x3F9F49F1 (16:9). In the recompiled code that constant
is stored to a fixed global (0x586B88) that the clip-matrix setup reads
back — so a continuous data patch has exactly the hack's effect, with no
code patching of recompiled functions. That makes the whole thing
switchable at runtime, which is why it ships:

- Game side (`src/main.cpp`, gated by `PS2X_WIDESCREEN=1`): a pnach group
  "Widescreen 16:9" continuously writes 0x3F9F49F1 to 0x586B88 (the pnach
  engine re-applies place-2 patches every vblank), and the host display
  aspect is set to 16:9. A `[widescreen]` line is logged at boot.
- Display side (PCSX2 bridge, `gs_pcsx2_backend.cpp`): with the switch on,
  the composed canvas height is 360 instead of 448, so the harvest canvas is
  exactly 16:9 (640x360; 1280x720 at scale 2), and the bridge sets PCSX2's
  aspect ratio to 16:9 at open. The host blit already honours the display
  aspect, so the picture lands as true 16:9 on the 2400x1080 panel
  (~1440x810) — wider view, no stretching of the 3D image.
- Variant default (`android/glue`): the path B variant seeds
  `PS2X_WIDESCREEN=1` — on by default, exactly like the renderer and VU1
  async seeds. The owner's escape hatch is the external `godhand.ini` line
  `PS2X_WIDESCREEN=0`, which restores the exact pre-B5 4:3 behaviour. An
  env var of the same name overrides both.
- The shell-settings toggle was judged too invasive for this round (the
  setting must exist before the ELF loads, which is the glue seed's job);
  the ini kill switch is the sanctioned fallback and the toggle can be added
  later without changing semantics.

Two honest caveats:

- The community hack also patches a font-draw constant so 2D text keeps its
  proportions. That constant is consumed inline by the font setup function
  and never lands in a fixed memory location, so a data patch cannot
  reproduce it. Under 16:9, menus/text render with the wider frame's
  proportions (the known, accepted trait of this hack family); the 3D world
  is correct.
- FMVs present in the 16:9 frame as well; no FMV-specific aspect switch is
  wired in this round.

## APK

- Package `dev.ptraaa.godhand`, versionCode **30**, versionName
  **0.25-m24-pathb6**, minSdk 26, targetSdk 35 — installs over pathb5
  in place.
- Signer: the shared ptraaa debug signer, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  (apksigner: v2 + v3 verified, 1 signer).
- `godhand-0.25-m24-pathb6.apk` — 136,712,917 B; the delivery zip
  `godhand-0.25-m24-pathb6.zip` — 34,838,593 B. `unzip -t` clean.
- Contents (`lib/arm64-v8a/`, md5-matched build tree ↔ APK):
  `libgodhand.so` **98,513,048 B** (variant; md5
  `efc5e88beceab19ceeedf61c6f77dcc0`), `libpcsx2gs.so` (md5
  `96a5efdea52b2b8d9b4543d8ca2a0db0` — **byte-identical to B4's**: the B5
  display-side change lives in the runtime half of the backend inside
  libgodhand.so, not in the bridge library), `libshaderc_shared.so`
  (md5 `2793f5b6394868783852325a4295e1f2`, unchanged). A copy of the
  variant library is kept at
  `~/workspace/godhand-android/libgodhand-0.25-m24-pathb6.so`.

## Verification done here

- Differential bench (work item 1): old compiled == new compiled ==
  new compiled at -O3 on all 22 entry fixtures — identical state/data
  digests, cycle counts and fixture selection. The kill-switch generation
  is byte-identical to the pre-B5 generated tree. Host wall-clock A/B was
  noise-bound (VM load ~5/4 cores); medians equal, no host speed claim.
- Variant build (`build/android-m2a`, `-DGODHAND_PATHB_DEFAULT_PCSX2=ON`):
  exit 0 (44 steps); the variant libgodhand.so carries the
  PS2X_WIDESCREEN seed line and the `[widescreen]` boot string alongside
  the B1B/B4 seeds.
- Host (linux) tree: full build including the touched runtime TUs
  (VU impl, GS backend, main.cpp) and the new VU library at -O3 — exit 0.
- Packaging with `GODHAND_PATHB=1`: exit 0, Path B staging confirmed,
  apksigner v2+v3 verified with the signer above, version 30 /
  0.25-m24-pathb6 confirmed via aapt; APK libraries md5-matched against
  the build trees (above).
- Mainline restoration, verified after packaging: option configured back
  to **OFF** and target `godhand` rebuilt (exit 0). The restored
  `build/android-m2a/src/libgodhand.so` is 98,507,424 bytes with zero
  variant-seed strings (the B5 runtime code — widescreen gate, VU fixup
  helpers — ships in mainline as well, gated off by default there; the
  variant delta remains only the glue seeds). The build tree is left in
  its normal state.
- Not done here (no Android device on this VM): executing pathb6. The
  owner run below is the proof; the markers exist so it convicts or
  clears each change in one log.

## Log markers for the owner run

Boot/glue (proves the variant seeds):

- `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; shell
  setting or ini can override)` (from B1B, unchanged)
- `native: path B variant: PS2X_VU1_ASYNC=1 (variant default; ...)` (B4,
  unchanged)
- `native: path B variant: PS2X_WIDESCREEN=1 (variant default; external ini
  PS2X_WIDESCREEN=0 restores 4:3)` (new in B5)

Runtime (proves the pieces are live):

- `[widescreen] PS2X_WIDESCREEN=1: aspect patch group on, host aspect 16:9`
  at boot, and `[pnach]` continuous writes counting up.
- Repeating `[vu1] async on: ...` window lines (B4 pipeline still on).
- `[ee-prof:top]`: compare the 0x0520 and 0x0358 program times against
  pathb5 (1,020 ms / 498-795 ms per window at ~70k calls).
- Gameplay fields/s against the pathb5 baseline of 44-52; harvest frames at
  1280x720 with `PS2X_GS_SCALE=2` while widescreen is on.

## Owner test steps

1. Install `godhand-0.25-m24-pathb6.apk` over pathb5 (same signer, in-place
   update). Do not edit any file first: for the clean B5 verdict the
   external `godhand.ini` should contain no PS2X_GS_RENDERER, no
   PS2X_VU1_ASYNC and no PS2X_WIDESCREEN lines.
2. Boot and play real gameplay (village + a fight). The picture should be
   true 16:9 — a wider view of the world, not a stretched one.
3. Send back the full boot log. It should show the three seed lines, the
   `[widescreen]` line, and the usual `[vu1]` / `[ee-prof]` windows.
4. A/B (optional, same install): add `PS2X_WIDESCREEN=0` to the external
   `godhand.ini` and reboot — exact 4:3 pathb5 framing; delete the line to
   return. `PS2X_VU1_ASYNC=0` still restores pre-B4 behaviour, and
   `PS2X_GS_RENDERER=native` remains the built-in-renderer escape hatch.

## Files changed

- `godhand-recomp/runtime/ps2xTest/tools/vu1_recomp.cpp` — norm-liveness
  pass + raw-write emission in pair mode; `VU1_RECOMP_NO_DEAD_NORM=1` kill
  switch.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/vu/ps2_vu1_impl.inl` —
  `Vu1FastCtx::normDirty`, `writeVfRaw`/`writeVfRawFull`, `fixupNorm` called
  from `runFast` at block boundaries and before return.
- `godhand-recomp/generated/vu1/*` — regenerated (game-derived, not in git).
- `godhand-recomp/src/CMakeLists.txt` — `godhand_vu1_code` at -O3.
- `godhand-recomp/src/main.cpp` — `PS2X_WIDESCREEN` gate: pnach aspect
  group + host aspect 16:9 + boot log line.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp` —
  widescreen gate: 640x360 canvas + bridge aspect 16:9 on Android.
- `godhand-recomp/android/glue/godhand_android_main.cpp` — variant seed for
  PS2X_WIDESCREEN (external ini key `ps2x_widescreen` wins), header note.
- `godhand-recomp/android/shell/AndroidManifest.xml` — 30 /
  0.25-m24-pathb6.
- `godhand-android/m24f-fixture/vu_bench.cpp` — the differential bench
  (host-only tooling).
- Not changed: the PCSX2 bridge binary interface, ARUS, the audio path,
  the synchronous VIF paths, VU0, every mainline default (the host/mainline
  build has PS2X_WIDESCREEN unset → exactly the old behaviour).

- Logs: `m24f-configure.log`, `m24f-build.log`, `m24f-package.log`,
  `m24f-restore-configure.log`, `m24f-restore-build.log`.
