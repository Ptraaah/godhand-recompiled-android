# Milestone 1a: Linux host toolchain for our own God Hand port

Date: 2026-11-01. Scope: build the recompiler on this Linux VM, compile the
LasVola runtime + game layer on Linux without the generated game code, and
pin down exactly which files generation needs from the owner's game copy.
No game ISO or game files were used anywhere in this milestone. Build logs
are the `m1a-*.log` files in this folder; the two source patches are in
`patches/`.

## 1. ps2_recomp builds and runs on Linux — no source patches

Binary: `PS2Recomp/out/linux/ps2xRecomp/ps2_recomp` (2,198,104 bytes).
Running it with no arguments prints its banner and usage
(`PS2Recomp - A static recompiler for PlayStation 2 ELF files`).

Exact steps (gcc 13.3, CMake 3.28, this VM):

1. Pre-seed the five FetchContent dependencies as shallow clones at the
   exact tags `ps2xRecomp/CMakeLists.txt` pins, into
   `~/workspace/godhand-android/deps-src/`:
   elfio `Release_3.12`, toml11 `v4.4.0`, fmt `12.1.0`, libdwarf `v2.2.0`,
   rabbitizer `1.14.3`. (19 seconds total.)
   Why: the stock configure does full, unshallowed git clones — toml11
   alone passed 160 MB and 7 minutes without finishing through this VM's
   network. This is a network workaround, not a code change; it uses the
   same `FETCHCONTENT_SOURCE_DIR_*` mechanism `build.py` itself uses.
2. Configure with the runtime, analyzer, test and studio parts off:
   `cmake -S PS2Recomp -B PS2Recomp/out/linux -DCMAKE_BUILD_TYPE=Release
   -DPS2X_BUILD_RUNTIME=OFF -DPS2X_BUILD_ANALYZER=OFF
   -DPS2X_BUILD_TEST=OFF -DPS2X_BUILD_STUDIO=OFF` plus one
   `-DFETCHCONTENT_SOURCE_DIR_<NAME>=...` per dependency above.
3. `cmake --build PS2Recomp/out/linux --target ps2_recomp -j4` — clean,
   first try, about 75 seconds.

Wiring into the game build later costs nothing: `build.py`'s
`find_recompiler()` checks the `GODHAND_RECOMP` environment variable
first, so the Linux binary plugs in by path — the `.exe` candidates it
otherwise looks for are only fallbacks. (LasVola also vendors the same
tool at `godhand-recomp/runtime/ps2xRecomp/` behind
`GODHAND_BUILD_RECOMP`; building that copy instead is equivalent.)

## 2. The LasVola runtime + game layer compile and link on Linux

Result: **`godhand-recomp/build/linux-m1a/bin/godhand` — a working Linux
ELF (88 MB) of the whole port with an empty game.** A smoke run with no
disc prints its boot banner and refuses exactly as designed:
`ERROR no disc image found`, exit code 2. Everything except the game
itself is proven to build: `libps2_runtime.a`, `libps2_iop.a`, raylib,
ImGui, rlImGui, the game layer, and the (stub) game-code library —
152 object files.

### How the missing game code was gated

`generated/` is gitignored build output, and the game layer never calls
generated functions by name — it binds them by guest address through the
function table (the `func_XXXX` strings in `src/godhand/` are comments
and address constants). So the least invasive stub is three small files
in `generated/`, shaped exactly like the real emitter's output
(`runtime/ps2xRecomp/src/lib/function_table_emitter.cpp`):

- `generated/ps2_recompiled_functions.h` and
  `generated/ps2_recompiled_stubs.h`: `#pragma once` only (the real ones
  declare the ~10.5k functions; only the game-code library's precompiled
  header includes them).
- `generated/register_functions.cpp`: the emitted file's skeleton with
  an empty table — `g_ps2RecompiledFunctionTableBase/End/SlotCount` all
  zero and a one-slot zeroed `g_ps2RecompiledFunctionTable[]`.

`generated_modules/` was left empty, which the CMake already handles
(it reports "0 recompiled SNR2 module(s)"). No tracked source was gated
or commented out.

### The blocker map — two real code fixes, everything else was scenery

Round 1 (no patches): 40 of 158 build steps failed. All 40 traced back
to exactly two root causes:

| # | Blocker | Where | Fix | Size |
|---|---------|-------|-----|------|
| 1 | SSE4.1 intrinsics (`_mm_extract_epi32`, `_mm_cvtepu8_epi32`, …) used by headers all over the runtime; MSVC accepts them bare, GCC refuses without the target enabled ("target specific option mismatch"). ~34 objects: most of `ps2xRuntime/src/lib/Kernel/`, `gs_cpu_raster.cpp`, `ps2_memory.cpp`, the VU interpreter, and every game-layer file. | Missing arch flag for non-MSVC (the project's own `GODHAND_ARCH` defaults to SSE41, but the flag was only ever passed in `if(MSVC)` branches) | `patches/0001-cmake-sse41-nonmsvc.patch`: one guarded `add_compile_options(-msse4.1)` block in the top-level `CMakeLists.txt` | ~7 lines of CMake |
| 2 | `runtime/ps2xIOP/include/ps2x/iop/iop_pad.h` uses bare `size_t` in the `PadInputState::pressure` array declaration without including `<cstddef>`; MSVC's headers leak `::size_t`, libstdc++ does not. 6 objects (all of ps2xIOP's pad/module files, `ps2_host_input.cpp`). The confusing "has no member named `pressure`" errors in `iop_dualshock2.cpp` and `ps2_host_input.cpp` are this same bug downstream: the failed declaration drops the member. | `iop_pad.h` | `patches/0002-iop_pad-cstddef.patch`: add `#include <cstddef>` | 1 line |

Round 2 (both patches applied): **all 157 compile steps passed.** The
only remaining failure was the final link: `cannot find -lGL` — a
broken symlink inside this VM's local header prefix (see below), not a
code issue. With that pointed at the system libraries, `bin/godhand`
linked immediately.

Feared Windows-isms that turned out to need **no work at all**:

- MSVC flags everywhere (`/arch:`, `/GL`, `/LTCG`, `/FORCE:MULTIPLE`,
  `/STACK:`, linking `dbghelp`/`winmm`): every one sits inside
  `if(MSVC)` in the CMake files and is inert under GCC.
- `windows.h` includes (9 files: `host_vsync.cpp`, `gs/gpu/gs_gl.cpp`,
  `gs/gs_pcsx2_backend.cpp`, `ps2xIOP/src/iop_subsystem.cpp`, and
  game-layer `call_trace.cpp`, `hang_dump.cpp`, `prompt_art.cpp`,
  `thread_policy.cpp`, `main.cpp`): all behind platform guards; every
  file compiled unmodified. The WGL present path in `gs_gl.cpp` is
  inside `#if defined(_WIN32)` (line 11).
- The IOP/kernel sync code (the spike flagged `WaitOnAddress`-style
  sync): there are no Win32 sync call sites in the compiled path — the
  scheduler, thread and IOP kernel code compiled as-is.
- SDL3: fetched only `AND WIN32`, and the God Hand top level forces
  `PS2X_ENABLE_SDL_INPUT` off by default, so the Linux build uses
  raylib/GLFW gamepads and `ps2_host_input.cpp` compiles clean.
  Lifting the `WIN32` gate is only needed if SDL controllers are ever
  wanted on Linux — a small CMake change, not a blocker.
- The GS GPU back end's `#version 460 core` GLSL compiled fine against
  desktop GL headers.

Environment gaps on this VM (not code blockers; on a normal dev machine
these are one `apt install`): Ninja (installed via pip), and the X11/GL
development headers GLFW needs (`Xrandr.h`, `Xinerama.h`, `Xcursor.h`,
`XInput2.h`, `Xfixes.h`, `GL/gl.h`, `GL/glx.h`, EGL) — the runtime
libraries were already installed; the headers were fetched as Ubuntu
`.deb`s and extracted with `dpkg-deb -x` into
`~/workspace/godhand-android/x11prefix/`, then passed via
`CMAKE_INCLUDE_PATH`/`CMAKE_C_FLAGS`/`-L`. One honest limit: the Linux
build is compile- and smoke-proven only. The window/GL present path
has not run — this VM has no display and there is no disc to boot.

Not yet exercised at all: the ~10k generated game sources. They are
plain C++ from the same emitter family, but any surprises live there,
not in the host — they are the next milestone's compile, not this one's.

## 3. What generation actually needs from the owner's game copy

`ps2_recomp` never reads an ISO. It consumes exactly one ELF per run.
The ISO (1,567,686,656 bytes per `DISC_HASHES.md`) is only an envelope
that two convenience steps open:

**Main program** (`build.py generate`):
- `build.py` pulls one file out of the ISO9660 root: **`SLUS_215.03`**,
  the boot ELF — **3,607,008 bytes**, SHA-256
  `1742F95BEF65BDB2AA57B7A77DF4AC7619A092E9646E1EA325BC32EC8A64F3CD`
  (checked by `build.py` and again by the port at startup; any other
  disc is refused). It rewrites `recomp/godhand.toml`'s `input` to that
  ELF and runs `ps2_recomp`. Nothing else on the disc is read.

**SNR2 overlay modules** (`tools/snr2/` + `build.py modules`):
- `snr2.py extract` writes 81 loose `.rel` files + `manifest.json` to
  `build/snr2/rel/`. To find them it reads two more root files from the
  ISO: **`GODHAND.FST`** (the name tree; small — a table over the AFS's
  1,445 entries) and the 81 `.rel` entries inside **`_DATA.AFS`** (the
  `.rel` DLLs live under `dll/debugoptimize/`; the other ~1,364 AFS
  entries are never touched by generation).
- `gen_modules.py` then runs `snr2.py emit <mod>` per module; its
  inputs are the loose `.rel` plus the boot ELF again (the import
  registry). As shipped, emit re-reads the ELF from the ISO root
  because `gen_modules.py` does not pass `snr2.py`'s `--boot-elf`
  option — passing it through is a one-line change and makes the whole
  module step ISO-free once `extract` has run.
- Module sizes are small by construction: every room DLL must fit its
  0xF000 (61,440-byte) slot (checked in `gen_modules.py`), `event.rel`
  its 0x18000 slot, `mcport.rel` its 0x20000 slot. Upper bound for all
  81 raw `.rel` files together ≈ **5.3 MB** (bound from the slot
  geometry; `manifest.json` records the exact sizes after a real
  extract).

**Total generation input from the owner's copy: the 3.6 MB boot ELF,
`GODHAND.FST`, and ≲5.3 MB of `.rel` — about 9 MB, not 1.57 GB.**
Everything else on the disc is runtime data, which generation never
reads. (Running the finished port does need the disc image as data —
that is a separate, later concern; for the Android build the data
stays on the phone.)

### Does the phone's `godhand_cache` satisfy this?

Yes — it is the right file set. Per the private app's anatomy
(`~/workspace/launcher-upgrade/INSPECT.md`), the cache is the ISO's
file tree extracted and verified on the phone (`files/godhand_cache`,
marker `.cache_verified_v1`, ~1.5 GB total): it contains `SLUS_215.03`,
`SYSTEM.CNF`, `GODHAND.FST`, `MODULES/IRX.IMS`, `_DATA.AFS`, etc.

- `SLUS_215.03` from the cache is generation input #1, byte for byte
  (the same SHA-256 check applies). Uploadable as-is: 3.6 MB.
- `GODHAND.FST` from the cache is exactly what the SNR2 tools parse.
  Small; uploadable.
- `_DATA.AFS` in the cache is still a single ~1.4 GB file — too big to
  move, and unnecessary: only its 81 `.rel` entries are needed. The
  extraction has to happen where the AFS is, i.e. on the phone.
  `snr2.py` is pure standard-library Python, so it can run under
  Termux; the one thing it cannot do today is read a folder instead of
  an ISO image — `tools/snr2/snr2disc.py`'s `Disc` class opens an
  ISO9660 image and reads the three root files out of it. A folder
  variant of that class is a small, contained patch (~30–40 lines:
  root-file reads become loose-file reads, AFS offsets apply to the
  loose `_DATA.AFS` directly). With it, `snr2.py extract` runs against
  the cache folder on the phone and produces `build/snr2/rel/`.
- Practical upload from the owner, total ≈ 9 MB: `SLUS_215.03` +
  `GODHAND.FST` (only needed if extraction has to be re-run or
  verified here) + the extracted `build/snr2/rel/` folder (81 `.rel` +
  `manifest.json`).
- One caveat to verify at extraction time: the cache's `_DATA.AFS`
  must be the complete file. Its presence in the cache listing and the
  cache's total size (~1.5 GB against the ISO's 1.57 GB) suggest a
  near-complete mirror; `snr2.py list`/`extract` on the phone proves
  it — all 81 `.rel` must enumerate and read without overrun errors.

## 4. Next step

1. Owner side: run the folder-mode `snr2.py extract` against
   `godhand_cache` on the phone (Termux), upload `SLUS_215.03` and
   `build/snr2/rel/` (≈9 MB).
2. Here: `GODHAND_RECOMP=<built ps2_recomp>` + the `--boot-elf`
   pass-through in `gen_modules.py`, then `build.py generate` and
   `build.py modules` equivalents under Linux cmake (build.py itself
   is a Windows driver — vsenv/cmd/.exe names — but every step it runs
   is a plain command that works here directly).
3. Compile the ~10k generated sources into the already-working Linux
   host; that build, not the host, is where remaining risk sits.
