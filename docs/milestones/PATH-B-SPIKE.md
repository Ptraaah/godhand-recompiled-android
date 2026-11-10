# Path B spike: PCSX2's GS core as our Android Vulkan backend

Date: 2026-11-04. Scope: read-only feasibility investigation. No renderer code
was modified and no port was started. Sources: the trees in this workspace
(`godhand-recomp/`, incl. `thirdparty/pcsx2` at PCSX2 commit `2954ebe`,
2026-10-11, and `thirdparty/pcsx2-gs`), the 2026-11-01 `SPIKE.md`, the
0.24 verdict in `owner-0.24-run/ANALYSIS.md`, upstream PCSX2 via the GitHub
API, and public AetherSX2/NetherSX2 material (URLs at the end).

## 0. What the 2026-11-01 spike did and did not establish

The saved `SPIKE.md` ends mid-sentence in its §1.2 — the file was stored
truncated, so the "path B is the likely performance answer" judgement does
not survive in that document; it lives in the milestone notes around it.
What the spike *did* establish, and this investigation re-verified against
the trees:

- The runtime already carries a complete PCSX2-GS integration for
  Windows/DX11: a backend class in the runtime, a C API for the GS library,
  and a bridge inside the library. This is not a sketch — it runs two other
  games (Urban Reign upstream, God Hand in the kamite fork).
- Neither shipped renderer runs on Android as-is: ours is desktop
  OpenGL 4.6 shaders behind an EGL/GLES offscreen shim, theirs is
  Direct3D 11/12 behind a Win32-only CMake block.

Everything below builds on that; nothing here re-opens the EE/VU side,
which the 0.24 numbers exonerate (the fight cost is GS GPU retirement
time, ~53–80 ms per field, GS worker CPU costs all in the low hundreds
of ms per window).

## 1. Interface map

### 1.1 What our side looks like today

Three layers, all in `runtime/ps2xRuntime`:

- **GS frontend** (`gs_frontend.cpp`, 2,284 lines): parses the GIF stream
  itself, owns SIGNAL/FINISH/LABEL interrupt semantics, transfer
  bookkeeping, the privileged-register/display model, and the GIF arbiter
  that merges GIF paths into one stream-ordered feed. It talks to a
  `GSRasterBackend` (`gs_backend.h`, 53 lines, ~20 virtuals).
- **GSThreadedBackend** (1,385 lines): a wrapper that serialises backend
  calls into an op stream consumed by one GS worker thread. ARUS pillars 3
  and 4 live here (async readback cache with the M23 scan prefetch, the
  `Op::FieldGate` queue-depth gate), as does save-state Export/Import of
  backend state.
- **Inner backends**: our GPU backend (decoded primitives:
  `Submit(batch)`, `LoadClut`, `BeginTransfer`/`UploadImage`,
  `Present(request) → PresentationFrame` of CPU pixels), our CPU backend,
  and `GSPcsx2Backend` (601 lines).

### 1.2 The raw-GIF seam is the whole story

`GSRasterBackend` has an explicit second mode: `WantsRawGif()`. A backend
that wants it gets **no decoded draws at all** — the frontend keeps doing
its parsing/interrupt/transfer bookkeeping but forwards, in stream order:

| Our side emits | PCSX2 GS consumes | Match |
|---|---|---|
| `RawGif(qwords)` — all GIF paths merged, tags may split across calls | `GSgifTransfer(data, qwc)` | exact |
| `VSync(GSVSyncRegs)` per field | priv-reg block copied into the GS's register memory + `GSvsync(field, true)`; `GSVSyncRegs` is byte-identical to the bridge's `Pcsx2GsPrivRegs` (enforced by a `static_assert`) | exact |
| `Initialize(vram, 4 MB)` on state load | `load_vram`: renderer reset + memcpy into `GSLocalMemory` | exact |
| `ConsumeLocalToHostBytes` (guest's TRXDIR=1 reads) | `read_fifo` → `GSInitAndReadFIFO` | exact |
| `ReadTransfer` (async-cache refresh, from GSThreadedBackend) | `read_local`: flush + `InvalidateLocalMem` + `GSLocalMemory::ReadImageX`, leaves GS transfer state alone | exact |
| `SnapshotVram` (debug/save) | `save_vram`: flush + texture-cache readback + memcpy out | exact |
| `GsReset`, `Reset` | `GSreset(false)`, download-state reset | exact |

`GSThreadedBackend::MakeDefault()` already prefers `GSPcsx2Backend`
whenever `GSPcsx2Backend::Requested()` is true, and `Requested()` already
defaults to "pcsx2" — it is only the loader underneath that is
Windows-only (`LoadLibraryW("pcsx2gs.dll")`); off Windows the whole class
compiles to inert stubs returning false. The C API between the halves
(`thirdparty/pcsx2-gs/include/pcsx2gs_api.h`, 152 lines, 17 entry points)
is platform-neutral except for one field: `hwnd`.

**The interface gap is therefore small and precisely located.** It is not
in the GIF/state/memory semantics — those were designed for this and are
proven on DX11. It is in exactly three places:

1. **Present ownership (the real gap).** On Windows the PCSX2 GS presents
   itself into a child HWND; our `Present()` is a no-op returning an empty
   frame and the host leaves the screen to the DLL. On Android the host
   (raylib) owns the only game surface and the GS layer is contractually
   offscreen: today's GPU backend renders into an EGL pbuffer and
   `Present()` hands back CPU pixels, because EGL objects cannot cross
   into the host's context (see the M11 notes in
   `src/lib/gs/gpu/gs_gl.cpp`). Path B must reproduce that contract:
   run the Vulkan device **surfaceless** (a `WindowInfo::Type` PCSX2
   already has) and harvest the composed display frame each vsync
   through the renderer's snapshot/download path
   (`GSRenderer::SaveSnapshotToMemory` exists and the bridge already
   uses it for .bmp snapshots), or grow an Android-surface swapchain
   type and re-plumb host windowing. The first route is the milestone-1
   route: it preserves the host contract and costs one display-sized
   download per field (640×448×4 ≈ 1.1 MB at 1× internal res), which
   must be pipelined through PCSX2's staged download textures so it
   does not become a new synchronous stall of the kind ARUS just
   removed. The second route is a later optimisation, not a dependency.
2. **Renderer/device selection inside the bridge.** `compat/Bridge.cpp`
   (315 lines) hardcodes `GSRendererType::DX11`,
   `WindowInfo::Type::Win32`, a DXGI frame-statistics hook, and
   `__declspec(dllexport)`. The Vulkan variant is a small, mechanical
   delta (~80–120 lines): renderer type VK, surfaceless window info,
   drop the DXGI stats, symbol visibility instead of dllexport.
3. **Game fixes.** The bridge hardcodes **Urban Reign's** GameIndex entry
   (SLUS-21209: alignSprite, texture-inside-RT, half-pixel-offset,
   native scaling, `GSC_UrbanReign` skip-count). For God Hand those must
   come out. Grep of this snapshot's `GSHwHack.cpp` finds 47 `GSC_`
   functions and **none for God Hand** — upstream PCSX2 needs no
   CRC-based hacks for this title, consistent with its clean reputation
   in the AetherSX2 community (§2.3). What remains is choosing sane
   defaults (blending accuracy level, hardware download mode) — config,
   not code.

### 1.3 Size of the translation layer, honestly

Already written and reusable (Windows-proven): `gs_pcsx2_backend.cpp`
601 + `Bridge.cpp` 315 + `HostStubs.cpp` 688 + `Pcsx2ConfigGS.cpp` 332 ≈
**1,950 lines**. (The 514-line texture-replacement loader is an optional
feature and can be dropped.)

New or reworked for Android/Vulkan:

- `GSPcsx2Backend`: replace the HWND child-window machinery
  (AttachHostWindow/HostTick/ChildProc/ShowWindow, roughly the top half
  of the file) with dlopen of `libpcsx2gs.so` from the app library dir
  and a real `Present()` that harvests frames: **~250–350 lines
  reworked**.
- `Bridge.cpp` Vulkan/surfaceless delta: **~100 lines**.
- Host stubs: paths/folders/OSD are already abstracted behind
  `pcsx2gs_host.h`; Android deltas are small (**~50–100 lines**).
- Build: a new extraction CMake mirroring `pcsx2-gs/CMakeLists.txt`
  (which is 124 lines for DX) with the Vulkan + POSIX file lists:
  **~250 lines**, plus the curation grind itself (§2.1).
- A `[gs-work]`-comparable stats shim in the backend (fields, GIF
  bytes, presents, present age — the backend already tracks most of
  this; PCSX2's `GSPerfMon` supplies draw/upload counters): **~100
  lines**.

**Total new/touched: roughly 800–1,100 lines of our code, plus build
curation.** Against that, the code we would *compile but not write* is
~100k lines for the DX-era subset already vendored (GS + common,
measured: 99,687 lines), plus the Vulkan renderer upstream adds
≈11–12k lines (GSDeviceVK.cpp alone is 246 KB / ~6.9k lines). The
honest summary: path B is a build-and-integration project, not a
renderer-writing project.

### 1.4 Tricky state semantics worth naming

- **Double display bookkeeping is by design.** Our frontend keeps its
  own priv-reg/display model (it needs it for interrupts and its own
  present path) and copies the registers into the GS's block at each
  vsync; the GS composes from its reading of the same registers. This
  already works on Windows, but any interlace/field-selection mismatch
  shows up as judder or wrong-field display, and it can only be judged
  on device.
- **VRAM authority flips.** With decoded backends, the runtime's 4 MB
  VRAM array is the live memory. In raw-GIF mode the GS's internal
  `GSLocalMemory` is authoritative and our array becomes a staging copy
  used only at state load/save. Anything else in the runtime that peeks
  at VRAM directly (debug reads use `SnapshotVram`, fine; the
  single-pixel `ReadVram`/`WriteVram` are stubbed to 0/no-op in the
  PCSX2 backend and are only exercised by the recording/debug wrapper,
  not the hot path) must be audited once, not assumed.
- **Save states get thinner.** `load_vram`/`save_vram` round-trip local
  memory but drop the renderer's texture cache and render targets
  (renderer reset on load — exactly PCSX2's own defrost semantics for
  the memory portion). Acceptable; it is the Windows bridge's behaviour
  too. Full `GSState` freeze/thaw is not exposed by the C API.
- **The guest's readbacks cross two caches.** EE-visible local→host
  reads are served by our threaded backend's async cache, refreshed via
  `read_local`; the GS underneath has its own download machinery and a
  hardware-download-mode setting. Getting the same answer from both
  layers for God Hand's fight reads (the M21–M23 scan pattern) is a
  correctness item for milestone 2, with the synchronous mode kept as
  the reference.

## 2. Build reality

### 2.1 What an Android ARM64 build of this GS actually needs

The vendored extraction cannot be re-used as the build base: its file
list is DX11+DX12 only (the Vulkan renderer folder was never extracted —
`GS.cpp` in the same snapshot already `#include`s
`GS/Renderers/Vulkan/GSDeviceVK.h`, so the code exists upstream at the
pinned commit), and its flag library is MSVC-only (`/Zc:*` options,
`_M_X86=1`, Win32 common sources, D3D12MA, WIL, DirectX-Headers,
`xbyak`).

A path-B extraction from the same upstream commit (or a newer pin)
needs:

- **GS core + HW renderer + Vulkan renderer** file lists (drop DX11,
  DX12, OpenGL — desktop GL is not GLES — and **drop the SW renderer**:
  its scanline code generators are xbyak x86 JIT; nothing else in the
  HW path needs xbyak).
- **common/ POSIX sources** instead of `Windows/Win*` (upstream common
  supports Linux/macOS; Android deltas are the usual ones: dynamic
  library loading, host paths, threading primitives — bounded, known
  work).
- **ARM64 is not a porting problem for the GS math.** This snapshot's
  `GSVector.h` has full NEON implementations (`GSVector4_arm64.h`,
  `GSVector4i_arm64.h`) behind `ARCH_ARM64`; `MultiISA` collapses to
  `isa_native`; the AVX-only `GSVector8` compiles out. Upstream ships
  ARM64 builds (Apple Silicon), which is why this code exists. Our own
  tree also already carries sse2neon shims from the runtime port as a
  fallback for stragglers.
- **The wrapped-memory trick is POSIX-ready**: `GS.cpp`'s non-Windows
  `GSAllocateWrappedMemory` uses shm + repeated `mmap(MAP_FIXED)` —
  the same mechanism AetherSX2 relied on.
- **Vulkan loader**: upstream `VKLoader` dlopens the system Vulkan
  library; Android exposes `libvulkan.so` (API 24+). Device creation
  goes through the standard `vkGetInstanceProcAddr` path.
- **Shader compilation is a real component, not a detail.** Upstream
  `VKShaderCache.cpp` compiles the renderer's GLSL
  (`bin/resources/shaders/vulkan/*.glsl`, incl. `tfx.glsl`) to SPIR-V
  **at runtime through a dynamically loaded shaderc**
  (`shaderc_compile_into_spv` and friends; upstream CMake does
  `find_package(Shaderc REQUIRED)`). Android path: build
  `libshaderc_shared.so` for ARM64 from the NDK's bundled shaderc
  sources — a solved problem (it is how Android Vulkan apps ship it),
  but it is a large additional build (~15 MB .so) and first-run shader
  compilation becomes a visible cost to manage (the DX build already
  ships a pre-made shader cache for this reason; the equivalent for VK
  is a device-side first-run compile + on-disk cache).
- **Support libraries**: fmt (MIT), imgui (MIT; only needed because the
  DLL's OSD/menu uses it — trimmable), cpuinfo (BSD-2, has native
  Android/ARM64 support), Vulkan Memory Allocator (MIT, single-file
  `vk_mem_alloc.cpp`), Vulkan headers. All permissive, all portable.
- **16 KB page caveat**: upstream's ARM64 page-size constant assumes
  Apple Silicon's 16 KB. The POCO F5 is a 4 KB-page device, and the
  constant is overridable (`OVERRIDE_HOST_PAGE_SIZE`) — a one-line
  build fix, but exactly the kind that produces a crash-on-boot if
  missed. Noted for milestone 0's checklist.

### 2.2 What the Vulkan renderer will demand of the Adreno 725

PCSX2's Vulkan device is a desktop-class renderer: Vulkan 1.3-era
feature set, dynamic rendering, and — for accurate GS blending —
either framebuffer-fetch-style extensions or render-order barriers
(the config even carries an `HWROVBarriersVK` toggle). On paper the
Snapdragon 7+ Gen 2's Adreno 725 with current Qualcomm drivers
exposes Vulkan 1.3 and tile-image extensions; in practice, stock
Adreno drivers are the historically weak link for this codebase
(AetherSX2's FAQ era is full of driver caveats, and its recommended
renderer on Adreno is Vulkan precisely because the GL driver was
worse). The knobs that decide whether a given game hits a slow
fallback — blending accuracy level, hardware download mode — are
runtime config in the bridge already (`blending=0..5`, exposed via
`PS2X_PCSX2_*` env vars). This is the main *performance* unknown of
path B, and it is genuinely unknowable until milestone 3 runs a fight
on the device.

### 2.3 Evidence this GS runs well on this class of hardware

Proven (public, citable):

- **AetherSX2 is a PCSX2 fork** (by Tahlreth, from PCSX2 circa 2021)
  ported to Android ARM64 with Vulkan and OpenGL renderers; it is the
  existence proof that this GS lineage — GSState, GSLocalMemory, the
  HW renderer and its texture cache — runs at full speed on Adreno
  phones. Its FAQ asks for Snapdragon 845-class hardware minimum,
  Adreno preferred over Mali.
- **God Hand specifically is a light title for it.** Community
  compatibility write-ups describe God Hand on AetherSX2 as
  "responsive and smooth... without any issues"; a public gameplay
  series runs God Hand Stage 5 (combat) at 60 FPS, 2× native, Vulkan,
  on a **Moto G67 Power (Snapdragon 7s Gen 2)** — a weaker SoC than
  the POCO F5's Snapdragon 7+ Gen 2 (Adreno 725), whose tier the
  AetherSX2 community guides class as the "sweet spot" for 2× native.
- **NetherSX2** carries the same fork forward with current releases,
  and its driver-focused forks flip the default renderer to Vulkan on
  current Adreno hardware — the lineage is alive and Adreno-tuned,
  albeit outside upstream.

Assumptions (not proven, and the report treats them as such):

- That **upstream** PCSX2's current Vulkan renderer (considerably
  evolved since the 2021 fork point — new texture cache, new download
  machinery, higher blending-accuracy ambitions) is as fast on a stock
  Adreno 725 driver as AetherSX2's older, mobile-tuned fork. Plausible,
  not guaranteed; §2.2 is where this can bite.
- That God Hand's fight scenes stress the same paths in both
  renderers. Our own data says our fight cost is fragment sampling out
  of a flat SSBO + batch churn; PCSX2's design (real texture units, a
  hashed texture/target cache, batched draws through a stream buffer)
  removes that specific wall by construction. The general claim "the
  GS is not the bottleneck for this game on this chip" is what the
  AetherSX2 evidence supports.

One caution about the precedent: AetherSX2's ARM/mobile adaptations
were never upstreamed and its source is not available to consult
(its later history also includes a public GPL dispute with the PCSX2
team — see §3). We get the *evidence*, not the code.

## 3. Licensing picture, plainly

- PCSX2 is **GPL-3.0+** (SPDX headers throughout the vendored tree;
  `COPYING.GPLv3` included). The extraction notices in
  `thirdparty/README.md` already pin the commit and say so.
- Our whole lineage is already GPL-3.0: PS2Recomp, LasVola's
  godhand-recomp (`LICENSE` in-tree is the GPLv3 text), and our public
  repo publishes the full source the APK is built from. **There is no
  license conflict to resolve** — path B adds more GPL-3.0 code to a
  GPL-3.0 project.
- What including the GS obliges, concretely:
  1. Keep PCSX2's copyright and SPDX notices in the copied sources
     (they are in the files; the extraction must not strip them).
  2. Distribute the corresponding source for whatever GS code ships
     in the APK, under GPL-3.0, with our modifications marked. Since
     the project's model is "public repo, APK built from it", this is
     satisfied by keeping the extraction + bridge in the public repo —
     it becomes a hard requirement, not a habit: a private or partial
     source drop for a path-B APK would be a violation.
  3. The permissive support libraries (fmt, imgui, cpuinfo, VMA,
     shaderc — Apache-2.0/BSD/MIT family) need their notices kept;
     all are GPL-compatible.
  4. No change to the no-game-data rule: the GS ships no game data,
     players still bring their own SLUS-21503 disc, disc-hash
     verification untouched.
- The one historical warning is AetherSX2 itself: the PCSX2 team
  publicly treated its closed later builds as a GPL violation. Our
  model (open repo, source-first) is the opposite of that failure
  mode; path B does not change the project's publishing shape, it just
  raises the stakes of keeping it.

## 4. Milestone ladder

Effort ranges are focused working days at this project's demonstrated
pace (agent-driven builds are fast; the slow parts are file-list
curation and device round-trips for anything visual). Elapsed time
will run longer than the sum because every device verdict waits on a
human test session.

| # | Milestone | Done when | Effort |
|---|---|---|---|
| B0 | **GS core builds for Android ARM64.** Fresh extraction (GS core + HW + Vulkan, POSIX common, no DX/SW/xbyak), new CMake, shaderc .so built, `pcsx2gs` as `libpcsx2gs.so`, linked into a GSTest-style harness that opens a surfaceless Vulkan device and shuts it down | Library + harness build clean with the project's Android toolchain; device-create attempted on the POCO F5 via the harness (or honestly deferred to B1 if the harness can't run standalone) | **3–6 days** — curation grind: upstream's per-platform file lists, `__ANDROID__` deltas in common/, the 16 KB-page constant, shaderc build integration |
| B1 | **First title screen through PCSX2 GS.** Bridge to Vulkan + surfaceless; `GSPcsx2Backend` Android variant (dlopen, no HWND, `Present()` harvests frames into `PresentationFrame`); selectable via `PS2X_GS_RENDERER`, built-in GS remains the fallback and the default | God Hand title screen and menus render on device through the PCSX2 path; boot log shows the VK device open; switching the env var back restores today's renderer | **3–5 days** |
| B2 | **Village correctness.** Field/interlace handling, display composition, host→local and local→host transfers incl. the fight readback pattern against the async cache, VRAM save/load, OSD/FPS counters, God Hand config settled (Urban Reign fixes out, blending/download defaults chosen) | Village + menus visually correct vs the current build side by side; no transfer-related glitches; save/load round-trips | **4–8 days** — visual defects are one device round-trip each; this is the long pole |
| B3 | **Fight numbers.** `[gs-work]`-comparable per-window stats on the PCSX2 path (fields, presents, present age, GIF bytes, perfmon draws/uploads), then config tuning (blending accuracy, download mode, texture-cache settings) | Fight windows directly comparable to 0.24's (presents/window, avg frame ms, fence-equivalent waits) and a keep/drop verdict on the tuned config | **2–4 days** |

**Total: ~12–23 working days; realistically 3–5 elapsed weeks**
including device-test latency. For calibration, milestones M20–M23
(each one ARUS pillar or fix) each took roughly a day of build work
plus a device cycle — path B's B2 is several of those stacked.

### Top three ways this path fails or stalls

1. **Adreno fallback cliff (performance).** Upstream's Vulkan renderer
   on the stock Adreno 725 driver hits a slow path for God Hand's
   fight blending/downloads (no usable framebuffer fetch, barrier
   costs, shader-compile hitches), and path B lands at "better than
   4 FPS, still not smooth". Mitigations are config-level and cost
   accuracy. This is the risk that decides whether B was worth it,
   and it cannot be retired before B3 — only bounded earlier by
   testing device creation + a synthetic draw load in B0/B1.
2. **Present-path regression (engineering, ours).** The surfaceless
   harvest, done naively, reintroduces a per-field synchronous
   download stall — the exact disease ARUS cured — wearing a Vulkan
   costume. Mitigation exists (staged download textures, one-field
   pipeline delay, our async machinery already knows the pattern),
   but it is the easiest place to lose a week to a self-inflicted
   wound.
3. **State-semantics drift (correctness).** Double display
   bookkeeping, VRAM-authority flip, thinner save states, and
   two-layer readback caching each have failure modes that only
   appear in specific scenes (a transition, a specific effect, a
   load). The Windows bridge de-risks the model; it does not exempt
   this game on this build from its own B2.

## 5. ARUS under path B

| Pillar | Fate | Why |
|---|---|---|
| 1. Upload dedup | **Retired — subsumed.** | PCSX2's `GSTextureCache` is the mature form of this idea: uploads land in hashed textures/targets and are re-sent only on invalidation. Our dedup code goes away with our renderer. Its *accounting* (skipped-bytes counters in `[gs-work]`) should be re-derived from `GSPerfMon` so before/after windows stay comparable. |
| 2. 2×2 block shading | **Retired — unnecessary.** | It existed to compensate for fragment sampling out of a flat VRAM SSBO. PCSX2 samples through real texture units from cached textures; full-rate shading is the design point. The visible softness goes away with the pillar. Nothing wraps. |
| 3. Async readbacks + scan prefetch | **Splits.** The in-GS half is PCSX2's own download machinery (`GSDownloadTexture` staged downloads; hardware download mode incl. the unsynchronized `GSReadLocalMemoryUnsync`-style path). The above-the-interface half — GSThreadedBackend's async readback cache and the M23 scan prefetch/stale policy — sits **above** `GSRasterBackend` and already talks to the PCSX2 backend's `ReadTransfer`. It survives nearly unchanged and still earns its keep: the guest's tiny reads should never park the EE, whichever GS is underneath. | |
| 4. Field gate / bounded queue | **Survives as a wrapper.** `Op::FieldGate` lives in GSThreadedBackend, above the interface, and throttles the EE→GS op stream for any inner backend. Its job changes slightly — from bounding a runaway GPU queue to bounding how far EE runs ahead of retired fields under the new present path — and its present-age accounting must be re-instrumented in `GSPcsx2Backend` (which already counts fields/presents). | |

Net: ARUS does not die under path B. Its two renderer-internal pillars
are absorbed by the renderer that should have had them all along, and
its two pipeline-discipline pillars (3's cache, 4's gate) keep wrapping
the new backend — which is also the safety net: if a fight scene ever
regresses under PCSX2 config, the switches (`arus=off`,
`asyncreadback=off`, per-pillar env vars, and `PS2X_GS_RENDERER` back
to the built-in GS) still compose.

## Sources

- Workspace: `SPIKE.md` (2026-11-01); `owner-0.24-run/ANALYSIS.md`;
  `godhand-recomp/runtime/ps2xRuntime/include/runtime/gs/gs_backend.h`,
  `gs_threaded_backend.h`, `gs_pcsx2_backend.h`;
  `.../src/lib/gs/gs_pcsx2_backend.cpp`, `gs_frontend.cpp`,
  `gs_threaded_backend.cpp`;
  `godhand-recomp/thirdparty/README.md`,
  `thirdparty/pcsx2-gs/CMakeLists.txt`,
  `thirdparty/pcsx2-gs/include/pcsx2gs_api.h`,
  `thirdparty/pcsx2-gs/compat/Bridge.cpp`;
  `thirdparty/pcsx2/pcsx2/GS/GS.cpp`, `GS/GSVector.h`,
  `GS/Renderers/HW/GSHwHack.cpp`, `common/Pcsx2Defs.h`,
  `common/WindowInfo.h`, `pcsx2/Config.h`.
- Upstream PCSX2 via GitHub API (2026-11-04): `pcsx2/GS/Renderers/Vulkan/`
  file listing (GSDeviceVK.cpp 246,308 bytes, VKShaderCache, VKSwapChain,
  vk_mem_alloc); `VKShaderCache.cpp` shaderc entry points;
  `cmake/SearchForStuff.cmake` (`find_package(Shaderc REQUIRED)`);
  `bin/resources/shaders/vulkan/tfx.glsl`.
- AetherSX2 performance guide (Snapdragon tiering, Vulkan-on-Adreno
  guidance, hardware download mode): https://gamehelptech.com/aethersx2-best-settings/
- Best phones for AetherSX2 by SoC (7+ Gen 2 "sweet spot", 2× native):
  https://aetherrsx2apk.com/best-phones-for-aethersx2/
- God Hand on AetherSX2, "responsive and smooth": https://aethersx2-emu.com/
- God Hand Stage 5 at 60 FPS, Vulkan 2×, Snapdragon 7s Gen 2 (YouTube):
  https://www.youtube.com/watch?v=3F_Va1-pCjk
- NetherSX2 vs AetherSX2 (fork lineage, Vulkan default on Adreno forks):
  https://tech-insider.org/nethersx2-vs-aethersx2-android-2026/
