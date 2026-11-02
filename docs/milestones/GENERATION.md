# Milestone 1c: real code generation and the full build

Date: 2026-11-01. Scope: take the owner's verified generation files
(SLUS_215.03 + GODHAND.FST + 81 SNR2 .rel overlays, ~9 MB total), run the
whole generation pipeline, and compile the LasVola tree with the real
generated game code all the way to a linked binary. The game files are
the owner's own disc files, used only inside this local tree (gitignored
build paths); nothing was published, uploaded or committed anywhere.
Logs are the `m1c-*.log` files in this folder.

## 0. Inputs, placed where the pipeline expects them

From the uploaded zip (84 entries), verified before use:

- `build/SLUS_215.03` — 3,607,008 bytes, SHA-256
  `1742F95BEF65BDB2AA57B7A77DF4AC7619A092E9646E1EA325BC32EC8A64F3CD`,
  the exact US SLUS-21503 boot ELF the pipeline requires.
- `build/GODHAND.FST` — the disc's name tree (32,944 bytes).
- `build/snr2/rel/` — `manifest.json` + the 81 loose `.rel` overlays
  (1,072,241 bytes total), the exact output shape of `snr2.py extract`.

The pipeline's two disc-reading steps were kept disc-free without
changing their logic:

- `build.py generate` wants an ISO only to pull SYSTEM.CNF and the boot
  ELF out of it (and hash-check the ELF). A minimal synthetic ISO9660
  image holding exactly those files (plus the FST and a `_DATA.AFS`
  whose 81 `.rel` entries carry the real payloads; the other 1,364 AFS
  entries are zero-length because generation never reads them) was
  built locally and passed via `--iso`. build.py's own SHA-256 check
  ran and passed inside `extract_boot_elf`. The image lives at the
  snr2 tools' default disc path, `~/workspace/godhand-android/disc/`.
- Cross-check: `snr2.py list` against that synthetic disc reproduces
  the phone-produced manifest exactly — all 81 entries, same FST paths,
  AFS indices (706..1428 of 1445), sizes and SHA-256 hashes, zero
  mismatches.

## 1. Module emit (snr2.py via gen_modules.py) — 81/81

Command: `python3 -I tools/snr2/gen_modules.py` (log: `m1c-emit.log`).

- All 81 modules emitted, 0 failed: 79 room DLLs at base 0x00799080,
  `event` at 0x007A8080, `mcport` at 0x007C0080.
- Totals from the 81 `<mod>_link.json` reports: 2,379 module functions;
  linked images total 1,072,241 bytes (byte-for-byte the .rel payload
  total, as expected).
- Side validation: gen_modules regenerated the `recomp/modules/*.toml`
  files and every one matched the shipped file byte-for-byte (git shows
  no change), so the on-phone extract, the emit math and the upstream
  Windows-generated tomls all agree.
- One patch was needed before this could run ISO-free — see patch 0003
  in section 4. Note the emit path turned out to touch the disc in one
  more place than M1A recorded: `cmd_emit`'s cross-module export index
  enumerates module names/indices from GODHAND.FST (payloads are read
  from the loose rel dir). That is what the synthetic disc's FST/AFS
  satisfies; no snr2.py code was changed.

## 2. Boot-ELF recompilation (build.py generate)

Command: `GODHAND_RECOMP=<vendored ps2_recomp> python3 build.py generate
--iso build/godhand-synth.iso` (logs: `m1c-generate-vendored.log`,
`godhand-recomp/build/recomp.log`).

- Output: `generated/` — 10,602 files (10,600 `.cpp` + the two headers),
  193 MB, for the config's 10,554 functions. `register_functions.cpp`
  alone is 12.6 MB.
- `ps2_recompiled_stubs.h` holds exactly 46 declarations — precisely the
  46 `.data` addresses on godhand.toml's skip list, which become
  TODO_NAMED stubs by design. No name-based stubbing happened.
- Recompiler log: 1,554 warnings (mostly unresolved JR/JALR sites with
  promoted fallback entries) and 1 error line (an unhandled SPECIAL
  instruction encoding, raw 0x1, inside func_003BB058); the run still
  completed successfully and the function is carried by the fallback
  machinery. Recorded here as a known generation wart, not hidden.

### The recompiler matters: standalone HEAD vs the vendored copy

The first pass used the standalone PS2Recomp HEAD binary built in M1a
(it generated 10,556 files and looked fine). `build.py modules` then
failed on mcport: the standalone recompiler stubbed mcport's 21 `sceMc*`
library functions, and the module pipeline (by design) refuses stubs in
modules.

Root cause, found in the two recompiler sources:

- Standalone HEAD (`PS2Recomp/ps2xRecomp/src/lib/ps2_recompiler.cpp`,
  `isStubFunction`): falls through to
  `ps2_runtime_calls::isStubName(name)` unconditionally.
- LasVola's vendored copy (`godhand-recomp/runtime/ps2xRecomp`, same
  function): `return m_config.autoStubByName &&
  ps2_runtime_calls::isStubName(...)` — name stubbing is gated by the
  config, and every God Hand toml sets `auto_stub_by_name = false` on
  purpose (the game layer binds SDK functions by address at startup
  instead).

So M1a's "building that copy instead is equivalent" does not hold in
this spot. Fix, at the generator layer: build the project's own vendored
recompiler on Linux and generate everything with it.

- Vendored build: `cmake -S godhand-recomp/runtime/ps2xRecomp -B
  vendored-recomp-build -DCMAKE_BUILD_TYPE=Release` with the same five
  pre-seeded `FETCHCONTENT_SOURCE_DIR_*` deps as M1a; built clean in
  ~68 s, **zero source patches**; binary 2,218,288 bytes.
- With it: boot generate re-run (10,602 files; 3,030 differ from the
  standalone pass), mcport passes its gate (365 sources, its `sceMc*`
  functions as real code), and the full module step below completes.
  No generated output was edited by hand anywhere in this milestone.

## 3. Module recompilation (build.py modules)

Command: `GODHAND_RECOMP=<vendored ps2_recomp> python3 build.py modules`
(log: `m1c-modules-vendored.log`).

- All 81 packs produced into `generated_modules/<mod>/`: 80 regenerated
  in the full run + mcport from the gate test, every offline gate green
  (no name clashes with the boot code, all definitions inside
  `gh_<mod>`, per-jr-site jump-table coverage).
- Totals: 2,460 `.cpp` files, 40 MB across the 81 module folders.

## 4. Patches

Four tracked files differ from upstream in the whole tree — exactly the
four patches in `patches/` (0001 and 0002 are M1a's, unchanged):

- `patches/0003-gen_modules-boot-elf.patch` — `tools/snr2/gen_modules.py`
  now passes snr2.py's existing `--boot-elf build/SLUS_215.03` global
  option (placed before the `emit` subcommand, as argparse requires) so
  import resolution reads the loose verified ELF instead of a disc
  image. This is the one-line pass-through M1A section 3 predicted.
- `patches/0004-register-functions-gcc-o0.patch` — see section 5.
- The vendored-recompiler switch is a build choice, not a source change.

## 5. Full compile — all 13,068 steps, zero compile errors

Configure: the M1a build dir `godhand-recomp/build/linux-m1a` re-run
(same flags; log `m1c-configure.log`). CMake reports 10,600 generated
sources, 3 huge sources (>900 KB: func_002022D0, func_0021A538,
func_00262AA8) and 81 SNR2 modules.

Build: `cmake --build build/linux-m1a --target godhand -j4`
(log `m1c-build.log`). **Every one of the 13,068 compile/archive steps
passed; not a single generated file failed to compile.** Per-file times
from `.ninja_log`: median 2.6 s, mean 4.3 s, slowest completed TU
362 s. Two environmental fights, neither of them a code error:

1. **register_functions.cpp vs the optimizer.** The 12.6 MB table
   initializer (upstream compiles it `/Od` + no PCH under MSVC for this
   exact reason; the CMake only had the MSVC rule) was still grinding
   under GCC `-O2` after 51 minutes when the build was stopped.
   Patch 0004 adds the mirror-image non-MSVC rule in
   `src/CMakeLists.txt` (`-O0`, `SKIP_PRECOMPILE_HEADERS`). The file
   then compiled in ~2.5 minutes. It is a startup-only pointer table;
   optimization changes nothing else.
2. **The final link vs this VM's RAM (7.9 GB).** At RelWithDebInfo the
   three generated-code archives total ~21 GB on disk, ~99% of it DWARF
   (the stripped objects measure 141 MB + 27 MB + 2.3 MB). Both bfd ld
   and gold were OOM-killed (signal 9) linking `bin/godhand`; a 16 GB
   swapfile was refused by the container. Accommodation, applied to
   build artifacts only: the three archives in this build tree were
   rebuilt from the same objects with debug sections stripped
   (`strip --strip-debug`; member counts verified 10,597 / 2,460 / 4 —
   the modules archive needed care, since 374 member names repeat
   across room packs at the shared slot base, so it was rebuilt from
   the build tree's per-module object files in ninja-edge order). The
   unstripped archives are regenerable from the untouched object files
   at any time, and on a normal 16 GB+ machine the stock link works as
   upstream ships it. The link then completed in under two minutes
   (gold, via the build dir's cached `-fuse-ld=gold`).

Result: **`build/linux-m1a/bin/godhand` — 188,136,888 bytes** (M1a's
empty-game binary was 88,008,216), ELF 64-bit x86-64, and a follow-up
`cmake --build` reports "ninja: no work to do". Wall-clock note: the
compile ran in several sessions over ~5 hours because this VM's CPUs
were host-throttled to ~30% for long stretches (measured; a fresh busy
loop got the same cap, so it is the VM, not the build) and two
supervisor restarts killed early build processes — ninja's incremental
resume absorbed all of it.

## 6. Smoke runs (logs `m1c-smoke-*.log`)

Headless, no display on this VM, as in M1a:

1. **No disc:** `./godhand --headless` loads settings/controls, prints
   the boot banner lines and refuses cleanly — `ERROR no disc image
   found`, **exit code 2**, exactly as designed.
2. **The synthetic generation disc** (`--iso`, 60 s limit, scratch
   controls/memcard files): the disc layer mounts it (4 entries),
   reads SYSTEM.CNF (57 bytes) and finds SLUS_215.03 (3,607,008 bytes),
   then stops at the port's own disc-verification gate —
   `ERROR MODULES/IRX.IMS (IRX module archive) not found on the disc;
   expected SLUS-21503`, **exit code 3** (wrong disc). That is the
   honest end of the extracted pieces: IRX.IMS is runtime data that was
   never part of the generation file set, and the port refuses a disc
   that lacks it before any game code runs. No crash, no hang.

## 7. Proven vs unproven, and Milestone 2 blockers

Now proven:

- The complete generation pipeline runs end to end on Linux from the
  ~9 MB file set — boot ELF (10,554 functions) and all 81 SNR2 overlays
  (2,379 functions) — with the project's own vendored recompiler.
- The full LasVola tree + real generated code compiles with **zero**
  errors and links into a 188 MB binary on this VM.
- The binary boots through settings/controls/disc-mount and applies its
  disc verification exactly as designed.

Still unproven (needs the full disc data or a display — neither exists
on this VM):

- An actual game boot: EE start, IOP module load from IRX.IMS, GS
  first frame. The runtime data (the full 1.57 GB disc image, or the
  extracted cache tree) stays on the owner's phone by design.
- The GL present path (no display here), input, audio, and any
  gameplay. Upstream's own caveat stands: stages 1–3 verified on
  Windows, stages 4–8 unverified anywhere.

Updated blocker list for Milestone 2 (Android skeleton):

- Carried from the spike, unchanged: no Android-capable renderer in
  the open tree (the GS decision — GLES port of the runtime GS vs
  PCSX2-Vulkan — is still the hard kernel); the Android shell is a
  stub; SDL3 is WIN32-gated in CMake; IOP sync needs the futex fix on
  Android; audio/input backends need Android implementations.
- New, from this milestone: use the **vendored** recompiler for any
  regeneration (the standalone HEAD tool silently changes stubbing
  semantics); keep patch 0004 in mind for the NDK clang build of
  register_functions.cpp (same optimizer trap, same fix shape);
  budget the Android link for the generated archives' size (on-device
  debug info is not an option — strip or split from the start);
  generation itself is cheap (~1 minute total) and fully scripted, so
  regenerating after any config change is a non-event.
- The one disc-data question for the Android build is already answered
  by the port's design: the data stays on the phone; the skeleton
  needs a disc/cache picker, not the data itself.
