# Path B extraction manifest

Everything under `src/` is PCSX2 source code, extracted for the Android
path B build of this project. Nothing here was written by us; our code is in
`compat/`, `test/` and the top-level `CMakeLists.txt`.

## Upstream provenance

- Project: PCSX2 (https://github.com/PCSX2/pcsx2)
- Commit: `2954ebe6965669219f1bbd256e958fbd8f83a85d` (2026-10-11) — the same
  commit the project's Windows/DX extraction in
  `godhand-recomp/thirdparty/pcsx2` is pinned to.
- License: GPL-3.0+ (see `src/COPYING.GPLv3`; per-file SPDX headers are
  intact and must not be stripped). Support libraries keep their own
  permissive licenses in their folders (fmt MIT, cpuinfo BSD-2, imgui MIT,
  fast_float Apache-2/MIT/BSL, Vulkan headers Apache-2/MIT, VMA MIT,
  vixl BSD-3).

## How the extraction was made (2026-11-04, stage B0)

The in-tree DX extraction (`godhand-recomp/thirdparty/pcsx2`) only ever
contained the files the Windows build compiled or included, so it had no
Vulkan renderer and no POSIX platform files. The path B extraction therefore
starts from that tree and adds the missing pieces from a tarball of the
identical upstream commit (kept in `downloads/`, unpacked in `upstream/`).
Files shared by both sources were spot-checked byte-identical
(`pcsx2/GS/GS.cpp`, `pcsx2/GS/GSState.cpp`, `common/Console.cpp`,
`common/FileSystem.cpp`).

Layout mirrors upstream, rooted at `src/`:

| Path | Files | Source |
|---|---|---|
| `src/pcsx2/` (GS core, HW/Common/Null renderers, core headers) | 124 | in-tree extraction |
| `src/pcsx2/GS/Renderers/Vulkan/` | (in the 124) | upstream tarball — never in the DX extraction |
| `src/pcsx2/GS/GSVector4_arm64.h`, `GSVector4i_arm64.h` | 2 | upstream tarball — ARM64 headers the x86 build never included |
| `src/pcsx2/GS/Renderers/SW/*.arm64.h` (code generator headers) | 2 | upstream tarball — declarations only; see exclusions |
| `src/pcsx2/BuildVersion.h` | 1 | upstream tarball (its .cpp is replaced by compat/BuildVersion.cpp) |
| `src/common/` | 109 | upstream tarball (the in-tree copy lacked the Linux/ platform files and several .cpp files entirely) |
| `src/3rdparty/fmt` | 29 | upstream tarball |
| `src/3rdparty/cpuinfo` | 83 | upstream tarball |
| `src/3rdparty/imgui` | 20 | upstream tarball |
| `src/3rdparty/fast_float` | 17 | upstream tarball |
| `src/3rdparty/vulkan` (Vulkan headers + vk_mem_alloc.h) | 41 | upstream tarball |
| `src/3rdparty/include` (xxhash.h etc.) | 22 | upstream tarball |
| `src/3rdparty/vixl` | 54 | upstream tarball — headers only in this build (see exclusions) |
| `src/bin/resources/shaders/vulkan/` + `common/` (GLSL + FFX headers) | 11 | upstream tarball — runtime resources / compile-time includes |

## What the build compiles

Per `CMakeLists.txt`: the GS core list and the Vulkan append from upstream's
`pcsx2/CMakeLists.txt` at this commit, the POSIX subset of `common/`
(same subset as the project's pcsx2-gs build, `Linux/` instead of
`Windows/`), fmt, the five imgui core files, and cpuinfo via its own CMake.
`src/bin/resources/shaders/common/ffx_a.h` / `ffx_cas.h` are also pulled in
at compile time by `GSDevice.cpp` through a repo-root-relative `#include`.

## Explicit exclusions (deleted from the extraction, not just uncompiled)

- Renderers: `GS/Renderers/DX11`, `DX12`, `OpenGL`, `Metal` — whole folders.
- Software renderer sources: all `GS/Renderers/SW/*.cpp` (rasterizer,
  scanline, texture cache SW) and the x86 code-generator headers
  (`*.all.h`). SW **headers** remain because the HW renderer's multi-ISA TU
  and the shared vertex-conversion machinery include them; no SW code is
  compiled or linked (verified: no SW objects in `libpathb_gs.a`).
- `common/Windows`, `common/Darwin`, `common/emitter` (x86 JIT emitter),
  `common/CocoaTools.mm`, `common/FastJmp*.asm`.
- xbyak: never copied. D3D12MA / WIL / DirectX-Headers: never copied.
- `common/Linux/LnxMisc.cpp` is present in the extraction but NOT compiled:
  it requires D-Bus and X11. `compat/AndroidMisc.cpp` provides its symbols.

## Our files (this project, GPL-3.0+)

- `CMakeLists.txt` — standalone Android build (arm64-v8a, android-26,
  c++_static, NDK r27c), modelled on `thirdparty/pcsx2-gs/CMakeLists.txt`.
- `compat/HostStubs.cpp`, `compat/Pcsx2ConfigGS.cpp`, `compat/pcsx2gs_host.h`,
  `compat/pcsx2gs_imconfig.h`, `compat/pcsx2gs_api.h`, `compat/zip.h` —
  copied from the project's existing `thirdparty/pcsx2-gs` port.
  HostStubs.cpp carries three marked "Path B (Android) adaptation" edits:
  the `<Windows.h>` include is guarded, `SysMemory::GetCodePtr` uses mmap
  off Windows, and the OSD font existence check uses std::filesystem.
- `compat/GSVertexSWCompat.cpp` — the GSVertexSW vertex-conversion block
  copied verbatim from upstream `GSRendererSW.cpp` lines 206-306 (plus the
  `s_pos_scale` constant from line 22), with `makeGSRendererSW` returning
  the null renderer. Needed because the HW renderer's vertex trace uses
  that conversion table even though the SW rasterizer is excluded.
- `compat/TextureReplacementLoadersStub.cpp` — `GetLoader`/`SavePNGImage`
  stubs (no PNG codec in path B yet; feature simply finds nothing to load).
- `compat/AndroidMisc.cpp` — LnxMisc replacements: portable helpers verbatim
  from upstream, desktop-only features (D-Bus screensaver, X11 mouse,
  spawned sound players) as no-op stubs.
- `compat/AndroidShm.cpp` + `compat/android_shm.h` — memfd_create-backed
  `shm_open`/`shm_unlink` (Bionic's headers declare neither). The header is
  force-included only into `LnxHostSys.cpp` and `GS.cpp`; no upstream file
  is modified.
- `compat/BuildVersion.cpp` — BuildVersion constants stated plainly for a
  snapshot (untagged; GitHash = the pinned commit above).
- `test/vk_device_test.cpp` — the B0 surfaceless Vulkan device-create
  harness, built on PCSX2's own VKLoader.
- `shaderc-src/` — a copy of the NDK r27c bundled shaderc sources
  (`$NDK/sources/third_party/shaderc`), from which `libshaderc_shared.so`
  is produced (see PATH-B-B0.md). Apache-2.0 (Google shaderc), glslang and
  SPIRV-Tools under their own licenses in that tree.
