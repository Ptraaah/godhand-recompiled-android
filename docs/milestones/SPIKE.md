# Spike: porting the open-source God Hand recompilation to Android ourselves

Date: 2026-11-01. Scope: feasibility and mapping only — no porting work was
started. Everything below was checked against the actual source trees, cloned
fresh into `~/workspace/godhand-android/` (`godhand-recomp/`, `kamite/`,
`PS2Recomp/`), plus the earlier anatomy of the private Android build in
`~/workspace/launcher-upgrade/INSPECT.md` and `LASVOLA.md`.

## The short answer

A self-built Android port of LasVola/godhand-recomp is a **bounded project,
not a research swamp** — with one hard kernel: the graphics layer. The game
code is generated C++, the runtime is portable C++ whose platform seams are
already abstracted, and a private Android build of this exact lineage already
runs on a real phone today, which proves the endpoint is reachable. The one
place real engineering concentrates is the GS (graphics) renderer, because
**neither open source tree contains a renderer that can run on Android as
shipped**. The second risk is not ours at all: the game itself is unfinished
upstream — stages 4–8 are unverified even on Windows.

## 1. What the open source actually is

### Lineage

- **ran-j/PS2Recomp** (GPL-3.0, ~3,750 stars, last push 2026-10-23) — the
  toolchain: `ps2xAnalyzer` (finds functions in a PS2 ELF), `ps2xRecomp`
  (translates R5900 MIPS instructions 1:1 into C++), `ps2xRuntime` (the host
  runtime the generated code links against). Its own runtime is the simple
  one: Raylib graphics, and per Recompendium the GS/VU1/DMA are still stubbed
  there — "no playable game yet". The playable games in this family do not
  use ran-j's runtime as-is.
- **LightVelox (TaylorNAlbarnaz)/PS2Recomp, branch `sotc-port`** — the fork
  that made games actually run: full EE/IOP/VU emulation, MTVU timing, VU1
  ahead-of-time compilation, a real GS, SPU2 sound. This is the runtime that
  matters.
- **Urban Reign port** (thiagordsfx/urban-reign-recomp) — took the sotc-port
  runtime further (GS threading, PCSX2 GS back end, IPU movies, pnach,
  save states) for its own game.
- **LasVola/godhand-recomp** (GPL-3.0, 13 commits, HEAD `14e956a`, last push
  2026-10-20) — the sotc-port/Urban runtime **vendored into the repo**
  (`runtime/`, at sotc-port commit `ac9efa0` plus Urban's changes, per
  `runtime/README.md`), with God Hand's recompilation config and a thin game
  layer on top.
- **kc-anderson/kamite** (GPL-3.0, HEAD `9ffb14f` dated 2026-11-01, extremely
  active) — a credited fork of LasVola's tree adding features (see §1.3).
  Its README lists "Later: Vulkan, Linux and Android" as future plans: in
  the open lineage, **Android is unclaimed territory**. The only Android
  build of this game that exists is the private one.

### 1.1 LasVola/godhand-recomp layout (29 MB, code only — no game data)

- `recomp/` — the recompilation inputs. `godhand.toml` (main config),
  `godhand_functions.csv` (**10,554 functions**), relocation/data-section
  tables, and `recomp/modules/*.toml` — one config per SNR2 `.rel` overlay
  module (the game loads 81 of them; the private APK's cache step indexes
  exactly the same overlays).
- `generated/` — **not in the repo.** The generated C++ (roughly 10k files,
  per the CMake comments) is produced at build time from the owner's own
  disc image: `build.py generate` extracts the boot ELF from the ISO and
  runs `ps2_recomp`; `tools/snr2/` extracts and generates the overlay
  modules the same way. The repo ships the recompiler's source
  (`runtime/ps2xRecomp/`), so the tool can be built from source; build.py
  looks for `ps2_recomp.exe` in a few known spots.
- `runtime/` (4.8 MB) — the vendored runtime:
  - `ps2xRuntime/` — EE scheduler, HLE kernel/syscalls (`ps2_syscalls.h`,
    `ps2_stubs.h`, `src/lib/Kernel/`), memory, VU interpreters plus VU1 AOT,
    SPU2 audio, pad, CD-image reading, VFS, memory-card/save-state support,
    pnach patches, and the GS (below). Knobs are `PS2X_*` environment
    variables — the same knob names found inside the private Android binary.
  - `ps2xIOP/` — the IOP runs the game's original modules on an interpreter,
    with HLE module services.
  - `ps2xTest/` — offline tools (the VU1 recompiler, a GS replay tool).
- `src/` (396 KB) — the God Hand layer: `main.cpp` plus `src/godhand/`
  (HLE bindings, function hooks, disc verification by SHA-256 against
  `DISC_HASHES.md` — US SLUS-21503 only, any other disc is refused — host
  window, button-prompt art, thread policy, hang dump).
- `thirdparty/` (14 MB) — `pcsx2-gs/` (a slim extraction of PCSX2's GS made
  into a DLL, with a small compat layer) and a fuller `pcsx2/` source copy,
  plus DirectX headers.
- Build system: CMake 3.21+, C++20, driven by `build.py` (Ninja). The
  documented flow is Windows + Visual Studio 2022. The CMake files are
  MSVC-flavoured (e.g. `/arch:` flags for the generated code in
  `src/CMakeLists.txt`, `/GL` LTO options) — there is no non-Windows build
  target in the game repo at all today.
- Windowing/input on Windows: the window is **raylib**'s; controllers come
  through **SDL3** (fetched at configure time, currently gated `AND WIN32`
  in the runtime's CMake) with raylib/GLFW gamepads as the fallback. Audio
  leaves through a small host-backend interface (`ps2_host_backend`).

### 1.2 How graphics are wired on Windows — two renderers, neither Android-ready

1. **The runtime's own GS (the default).** `godhand.ini` ships
   `Renderer=native`. It has a CPU rasteriser back end and a GPU back end
   (`runtime/ps2xRuntime/src/lib/gs/gpu/`) written in **desktop OpenGL with
   GLSL `#version 460 core`** shaders, and a present layer that on Windows
   goes through WGL shared contexts (`gs_gl.cpp`). There is no Vulkan and
   no GLES anywhere in this GS.
2. **PCSX2's GS (optional, off by default).** Top-level CMake option
   `GODHAND_PCSX2_GS` builds `thirdparty/pcsx2-gs` as `pcsx2gs.dll` —
   **Direct3D 11 only, and the CMake block is `WIN32`-only**. Two more
   caveats from the CMake comments themselves: the wrapper "still carries
   Urban Reign's GameIndex fixes and serial", and the runtime silently
   falls back to its own GS when the DLL is missing.

Kamite flips the priority — PCSX2's hardware
...[truncated 10066 chars]