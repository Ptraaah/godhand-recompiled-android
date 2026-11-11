# STAGE 2 SCOPE — our native renderer as the main graphics path

Read-only scoping report, 2026-11-12. No source was modified and nothing
was built for this document. Commissioned by Pablo's "full Android
class" order: the port's own mobile-first renderer (the in-tree native
backend carrying ARUS) becomes the main path, and the PCSX2 Vulkan
bridge demotes to selectable fallback — the m44 roles reversed.

Numbering note: **m46 is skipped** (Pablo, 2026-11-12: "Gausah m46
loncat m47"). The bridge-side texture-lightening round will not ship
as its own build. The first stage-2 build is **m47**; §3 folds what
was worthwhile in the m46 plan into the sequence in native form (§2.10).

Principal sources: `ARUS.md`, `M20.md`–`M24-GPU-COST.md`,
`PATH-NATIVE-M25.md`, `docs/M39-CORE-REPORT.md`,
`docs/M42-DIAGNOSTIC-GPU-AUDIO.md`,
`docs/M43-PRESENTPATH-INVESTIGATION.md`,
`docs/M44-GSFALLBACK-REPORT.md`, `owner-0.24-run/ANALYSIS.md`, and the
live tree (`godhand-recomp/runtime/ps2xRuntime`, `godhand-recomp/android`,
`path-b/`). Where project lore and code disagreed, code won; the two
disagreements found are called out in §1.2.

---

## 0. Tree state — the m46 audit (read this before building m47)

The m46 lane was stopped mid-work. The parent asked whether partial
m46 edits (bridge texture lightening / effect skipping) are sitting in
the tree. **They are not. The tree is at the exact m45 shipped state,
and no restore step is needed before m47.** Evidence, all gathered
read-only on 2026-11-12:

- No directory `m46lightex-build-work/` exists. The lane created no
  work dir, no `orig/` snapshots, and no build outputs.
- Newest source files in `godhand-recomp/` (runtime, src, android;
  build dirs excluded): the m45 edit set, mtime 2026-11-11 16:53–16:54
  UTC (`game_disc.h/.cpp`, `src/main.cpp`,
  `android/glue/godhand_android_main.cpp`, `TouchPad.java`,
  `GameActivity.java`, `AndroidManifest.xml`), then the m44 edit set
  at 09:56 UTC (`gs_frontend.cpp`, `gs_threaded_backend.cpp/.h`,
  `gs_pcsx2_backend.cpp`). Nothing is newer. The lane's observed
  activity after its 18:05 UTC start was reads and greps only.
- `path-b/compat/BridgeVK.cpp` (the file m46 would have edited first)
  is md5 `9d8ec0484e6bc07a530b07d255f1be55`, **identical to the repo
  clone's tracked copy** — i.e. the m44-shipped state, including the
  Vulkan device line. It differs from the m44 `orig/` snapshot
  (`86e7f442…`) by exactly the m44 change, as it should. Newest
  path-b source overall is that same file; everything else in
  `path-b/src` is Nov 3–4.
- Whole-tree scan for files modified after the m45 build completed
  (2026-11-11 16:57 UTC), excluding build dirs and APK artifacts:
  nothing in either source tree. (Only `.git` internals of the repo
  clone moved, from the m45 release lane's fetch/push.)
- `godhand-recomp/build/android-m2a/CMakeCache.txt`:
  `GODHAND_PATHB_DEFAULT_PCSX2:BOOL=OFF` — the resting state the
  m44/m45 lanes restored after their variant builds. No half-flipped
  cache was left behind.

Cheap re-verification for the m47 lane before it touches anything
(one minute): re-run the BridgeVK md5 comparison above, and
`find godhand-recomp/runtime godhand-recomp/src godhand-recomp/android path-b -name '*.cpp' -o -name '*.h' -o -name '*.java' | xargs ls -lt | head` — the top of the list must still be the m45 set.

---

## 1. Where the native backend stands today

### 1.1 Selection reality: present in every build, reachable almost never

The native backend is not missing or bit-rotted — it compiles into
every shipped `libgodhand.so`. It is sidelined purely by selection:

- `GSThreadedBackend::MakeDefault()` (`gs_threaded_backend.cpp:725`):
  PCSX2 bridge if `GSPcsx2Backend::Requested()`, else
  `MakeNativeDefault()`. The shipped builds are Path B **variant**
  builds: the glue seeds `PS2X_GS_RENDERER=pcsx2` into the environment
  (`godhand_android_main.cpp:629`, under
  `GODHAND_PATHB_DEFAULT_PCSX2`), and that layer outranks the ini —
  the reason Pablo's phone, whose external ini says `native`, has
  booted the bridge in every shipped session since m24.
- `MakeNativeDefault()` (`gs_threaded_backend.cpp:733`): the GPU
  backend (`GSGpuBackend`, the GL ES 3.2 compute rasterizer) if
  `PS2X_GS_GPU` resolves on, else the CPU backend
  (`GSCpuBackend`). `src/main.cpp:723` sets GPU-by-default true, but
  the Android glue **always** writes `PS2X_GS_GPU` from the shell's
  GPU toggle (`godhand_android_main.cpp:532`, overwrite), and the
  toggle's shipped default is **off**. So on a stock install,
  "native" means the **CPU** backend — which is exactly where the
  m44 fallback lands
 ("native renderer active (built-in GS, CPU)").
  The ARUS GPU backend runs on a player's phone today only in the
  narrow corridor: renderer somehow resolves native **and** the GPU
  toggle is on. Pablo's own toggle is on (his m42 video), so his
  phone is one seed-flip away from the real native path; a fresh
  player's is not.
- The bridge's lazy-open failure (Mali) is caught by m44's latch-hook
  swap through `GS::setRasterBackend` — the seam §3 reuses in
  reverse for the end state.

### 1.2 ARUS pillar audit — all four exist; two corrections to the lore

Verified in code, with shipped defaults. The pillars do **not** all
live in the same layer, which decides which of them do anything on
today's bridge-default build:

| Pillar | Where | Shipped default | Live on bridge path? |
|---|---|---|---|
| 1. Upload dedup | `gs_gpu_backend.cpp` `executeTransfers()`; `dedupEnabled` parsed in `Impl` ctor (:1002) | **On** (`PS2X_GS_UPLOAD_DEDUP=0` kills) | **No** — inside `GSGpuBackend` only |
| 2. 2×2 block shading | `kRaster` LORES blocks, `gs_gpu_shaders.cpp`; `renderDiv` member default **2** (`gs_gpu_backend.cpp:458`), parsed :1010 | **On** (`PS2X_GS_RENDER_SCALE=1` kills; force-off when `PS2X_GS_SCALE>1`) | **No** — inside `GSGpuBackend` only |
| 3. Async readbacks | Threaded wrapper's cache, `gs_threaded_backend.cpp:141` `asyncReadbackMode()` | **Mode 4** (M23: ≤64 px any shape/format, position-exact key, scan prefetch + neighbour stale-serve; `=off` kills, `=1` restores pre-M21) | **Yes** — it wraps whichever inner backend is selected and speaks `ReadTransfer` |
| 4. Queue-depth gate | Threaded wrapper enqueues `Op::FieldGate` per field (`gs_threaded_backend.cpp:684`); `GSGpuBackend::Impl::fieldGate()` executes it (`gs_gpu_backend.cpp:1145`) | **On, K=2** (`PS2X_GS_QUEUE_DEPTH=off` kills; marks not even enqueued) | **No** — the fence lives in `GSGpuBackend`; for other backends the mark is a `dynamic_cast` no-op |

Two corrections to the campaign brief's background, with evidence:

1. **Pillar 4 was built.** It shipped as Milestone 22 in 0.23
   (M22.md: "the threaded wrapper now enqueues one zero-payload mark
   per guest field… submitted-but-unretired work is thereby bounded
   to K = 2 fields"), and the code is in the shipped tree at the
   citations above. What is true is narrower: pillar 4 has never
   been *live in the shipped default configuration*, because the
   bridge has been the default since m24 and the gate's fence only
   exists inside the GPU backend. "Never built" is wrong; "built,
   proven in the 0.23/0.24 native sessions, dormant since" is right.
2. **Pillar 3's shipped default is mode 4, not mode 3.** `ARUS.md`
   describes mode 3; M23 added prefetch + the stale-serve policy as
   mode 4 and made it the unset default (`gs_threaded_backend.cpp:145`
   returns 4 when the variable is unset). The doc drifted; the code
   is the truth.

The master switch also exists as documented: `src/main.cpp:409ff`
(`applyArusMasterSwitch`) — `arus=off` / `PS2X_ARUS=off` forces the
four pillar variables to pre-ARUS values before any backend is
constructed, except pillars set explicitly in the environment.

### 1.3 The fence problem: what a stall actually waits on

Every blocking wait in the native GPU backend, from the code:

- **Field gate** (`gs_gpu_backend.cpp:1165`): `glClientWaitSync` on
  the fence placed at the end of field N−2. It waits for the Adreno
  command queue to **retire all GPU work submitted up to that
  fence** — in a fight, two fields' worth of compute passes
  (raster/CLUT/upload/copy dispatches and their barriers).
- **Ring wrap** (`gs_gpu_backend.cpp:1115`): the same wait,
  denominated in bytes — reusing a 48 MB ring chunk waits for the
  chunk's fence. Both waits use the flush flag and a 5 s timeout
  (proceeds on timeout; a wedged fence degrades the bound, never
  hangs).
- **Present mode 2** (`gs_gpu_backend.cpp:3709`): a bounded 8 ms
  wait on the *previous* frame's readback-slot fence.
- **Synchronous readbacks** (`readData` / `localToHostTransfer`):
  full drain waits. After pillar 3, the only ones left in fights
  are the 3 scan starters per accounting window.

The device numbers (0.24 run, POCO F5, `owner-0.24-run/ANALYSIS.md`
and M24-GPU-COST §1): fight windows show **3,930–4,722 ms of fence
waits per window, 49–83 waits ≈ one per field, averaging 53–80 ms
per field**, presents at 3.5–4 per second in the heaviest windows,
GS worker CPU costs all tiny (~2.6 ms/field submission), EE parked
waiting on the GS queue (`gsIdle` 1–4%).

The crucial interpretation, and M24's established verdict: **after
ARUS, the fence is the meter, not the disease.** The pre-ARUS
diseases — seconds-deep queues, 175–285 ms synchronous pocket-change
readbacks, displayed frames 25–70 fields old — are gone (frame age
is 3.0–4.1 fields in the 0.24 log). What the field-gate wait now
measures is *genuine GPU execution time* for one field of the
software rasterizer: ~54–87 hazard flushes per field become
~200–260 barrier-separated compute passes, each sample doing
dependent SSBO loads through computed PS2 swizzle addresses with
no texture cache, no hardware depth, and read-modify-write
blending in shader code. The brief's "~4.9 s/window fence stalls"
is this same 3.9–4.7 s/window figure; calling it a stall is fair
from the player's seat (the screen waits), but the campaign's
enemy is **per-field GPU cost**, and every gap item in §2 is
priced against reducing it. The one number every stage-2 device
test is judged on: **fence ms per fight field (baseline 53–80)**.

What is *not* yet known — M24 §5, still true today: the split of
that 53–80 ms between raster shading itself vs pass-serialization
turnaround. The instrument exists in the shipped code
(`PS2X_GS_GPU_PROFILE=1`, per-pass timer queries, extension
confirmed present on the owner's Adreno 725) and has **never been
switched on in a device fight run**. The HIRES-only ablation
switches (`gsDebug`) were never ported to the shipping 1× path.
No stage-2 fix should be built before that measurement; §3 makes
it m48.

### 1.4 PS2-shaped assumptions the native path still carries

Each is a place the design still pretends the GS is a fixed-function
chip wired to its memory:

1. **Flat VRAM SSBO, one 32-bit word per guest pixel**
   (`vramBuffer`; `gs_swizzle.cpp`, `ps2_gs_memory.cpp`, all shader
   addressing). The memory model is the PS2's, verbatim. Consequence
   (M20, settled): there is no render target whose size could be
   halved; "internal resolution" does not exist as a knob, only
   shading rate does.
2. **Texture sampling without texture units.** Every tap is
   `locate()` (integer swizzle math + lookup-table SSBO load) →
   `loadAt()` (SSBO load); bilinear = 4 taps; indexed formats add
   CLUT SSBO loads. No texture cache locality, no hardware
   filtering, no mip hardware.
3. **No fixed-function depth or blending.** Depth lives in VRAM
   words; the z-test is a dependent SSBO read *after* the texture
   taps (no early-z — a failing fragment has already paid
   everything); blending is a framebuffer read-modify-write in
   shader; sub-32-bit framebuffer formats write through paired
   `atomicAnd`/`atomicOr`.
4. **Hazard-granularity submission.** The batch flushes at every GS
   state hazard (target change, texture feedback, CLUT overlap,
   transfer, sync — `flushFor` reasons 0–5), because the real GS
   executed in order against live state. On a queued GPU each
   flush is a chain of dispatches separated by
   `glMemoryBarrier(SHADER_STORAGE | SHADER_IMAGE_ACCESS |
   BUFFER_UPDATE)` — the queue runs as a strictly serial chain of
   small passes.
5. **Present by readback and re-upload.** The GL context is
   surfaceless (1×1 pbuffer, `gs_gl.cpp` Android section, M11);
   `Present()` composites into `dataBuffer`, copies to a readback
   slot (mode 2 returns the *previous* slot — one frame of built-in
   latency), the host copies the pixels and re-uploads them into a
   raylib `Texture2D` every frame (`ps2_runtime.cpp` `UploadFrame`).
   The PS2's CRTC scanned VRAM directly; here every displayed frame
   makes a full CPU round trip. (~1.15 MB/frame at 640×448 —
   small at 4 presents/s, a real tax at 60 fields/s.)
6. **Page-granular bookkeeping everywhere.** Hazards, dedup
   dirty-tracking, CLUT provenance, readback keys — all keyed to
   GS pages, because that's the unit the guest's memory model
   exposes. Host-side structures inherit the guest's granularity
   whether or not it suits the GPU.
7. **Lock-stepped production.** By ARUS's design the guest's field
   rate now honestly equals the GPU's retirement rate (pillar 4).
   That is correct GS semantics — draws read what earlier draws
   wrote — but it means there is no decoupling left to hide GPU
   cost behind: cost reduction is the only road.
8. **The CPU backend is a second, separate rasterizer**
   (`gs_cpu_backend.cpp`) that shares none of the GPU backend's
   modes. Any native-path feature must either be GPU-backend-only
   (and a documented no-op on CPU) or built twice. Today the CPU
   backend is what most "native" boots actually run (§1.1).

For contrast, the bridge's assumptions are the desktop mirror
image: a real texture cache and hardware rasterizer (the whole
§1.4 list designed away by PCSX2), bought with desktop-class
Vulkan device/pipeline requirements that fail outright on Mali
(M44) and a synchronous `pcsx2gs_grab_frame` harvest as its
present path.

---

## 2. Gap list — what must change for native to be the default again

Ranked by expected player-visible gain (reduction in fence ms/field
in fights, and its shadow, presents/s). Sequencing is a separate
question — §3 orders the builds, and notes where a low-gain item
must land early because everything else needs it. Every item names
how a device log proves it worked; the standing metric is fence
ms/field in a fight window, baseline 53–80 ms (0.24).

### G1. The decisive measurement: GPU profiler on, ablation switches ported

- **Area:** `gs_gpu_backend.cpp` (profiler already in tree,
  `PS2X_GS_GPU_PROFILE=1`, `ProfKind` pass timers), `gs_gpu_shaders.cpp`
  (port the HIRES `gsDebug` ablations — 1 = no texture reads,
  2 = shade without writing, 4 = coverage only — into the 1× path;
  M24 §5 identified the vehicle: `uRaster.y/z/w` are currently
  unused in the 1× path and the host already uploads them as 0).
- **Idea:** One device fight run with the profiler on prints GPU
  busy/frame split by pass kind (rasterCompute / clut / upload /
  copy / present / clear), the heaviest frame's raster passes by
  prims/target/flags, and the flush-reason histogram. Three short
  ablation runs then price coverage vs sampling vs framebuffer
  traffic inside rasterCompute. This separates M24's rank 1
  (per-sample shading cost) from rank 2 (pass serialization) —
  the fork the entire campaign turns on.
- **Risk:** ~None to the game (measurement only; profiler results
  are read frames late by design). The ablation port touches the
  shipping shader's uniform plumbing — keep it behind the same
  debug word, default 0 = today's shader exactly.
- **Log proof:** `[gs-gpu-profile]` lines exist and the split sums
  to ≈ the window's fence time. No player-visible change is the
  success criterion.
- **Size:** an evening (profiler is a seed flip; the port is the
  small contained change M24 specified).

### G2. Flush coalescing — fewer, bigger passes per field

- **Area:** `gs_gpu_backend.cpp` `flushFor()` / `flushBatch()` /
  `prepareTarget()` hazard logic; `gs_frontend.cpp` draw batching.
- **Idea:** A fight field currently arrives as 54–87 flushes
  (~24 prims each) → ~200–260 barrier-separated GPU passes. Where
  consecutive flushes' page sets are disjoint (no read/write or
  write/write overlap either way), their passes can be merged or
  reordered so barriers and dispatch turnarounds amortize. Start
  with the safest hazard class the G1 flush histogram convicts
  (likely target-key changes between non-overlapping framebuffers),
  behind its own switch, one class per build.
- **Risk:** The highest correctness risk on this list. The hazard
  rules *are* GS ordering semantics; a wrong merge shows as
  effects sampling stale framebuffers — subtle, scene-dependent,
  exactly the failure class that needs the golden-replay gate
  (`ps2xTest/gs_replay` + `gs_reference`) run before any device
  test, plus the per-class switch as a kill.
- **Log proof:** flushes/field and passes/field fall in
  `[gs-work]`-adjacent accounting; fence ms/field falls with them;
  zero visual diffs reported on the owner's route.
- **Size:** weeks, plural, if the histogram convicts serialization.
  Only build it if G1 says so.

### G3. Texture sampling through real texture objects (the long pole)

- **Area:** a new sampling path inside/alongside `GSGpuBackend`:
  page-content-versioned uploads of guest texture pages into GL
  texture objects (or a texture array/atlas), `kRaster` sampling
  via texture units for the formats/layouts that map cleanly;
  SSBO path retained as the fallback for feedback textures and
  unmapped formats.
- **Idea:** Attack M24 rank 1 at its root: replace per-tap
  computed-swizzle SSBO loads with the texture unit's cache,
  filtering and format hardware. This is the one item that
  changes the architecture rather than tuning it — M19's verdict
  stands ("lowering internal resolution is a different backend,
  not a setting"); this is that different backend, grown
  incrementally behind a switch (`PS2X_GS_GPU` value or a new
  env), A/B-able against the incumbent in the same APK.
- **Risk:** Large. Page-version tracking must be exact or
  textures go stale (the dedup/hazard page machinery is the
  foundation to build on — it already tracks every VRAM write).
  Drawn-page feedback (the game's flip copy, effect passes
  sampling framebuffers) must stay on the SSBO path, so the two
  paths coexist and the boundary between them is the bug surface.
  Golden-replay gates are mandatory, not optional.
- **Log proof:** a boot line naming the sampling path per texture
  class + counters (texture-object hits vs SSBO fallbacks per
  window); fence ms/field in fights is the verdict.
- **Size:** the long pole — several weeks, incremental. Do not
  start it before G1 prices it; if G1 convicts serialization
  instead, G2 outranks it and this may shrink to "only for the
  hottest texture class".

### G4. CLUT rebuild dedup by content

- **Area:** `gs_gpu_backend.cpp` `dispatchClutOps()` / `clutOps`,
  frontend `LoadClut` dedup (`clutKeyValid`).
- **Idea:** Fights dispatch 81–148 CLUT ops per field, each
  rebuilding a 256-entry palette from swizzled VRAM reads
  (~512 SSBO reads/op), although the frontend already dedups
  repeat *requests*. Key the backend op by the source pages'
  content version (the same page-write tracking dedup uses):
  if the palette source hasn't changed since the op last ran,
  skip the dispatch. Genuine palette changes — the game does
  make them — still run.
- **Risk:** Low-moderate. A missed invalidation = wrong colours
  on indexed sprites until the next genuine change; the
  page-version machinery is proven by pillar 1, and the switch
  is the kill.
- **Log proof:** CLUT ops/field falls in the window accounting;
  profiler's `clut` pass share (from G1) falls with it.
- **Size:** an evening to a few days.

### G5. Upload shader: packed writes for indexed formats

- **Area:** `gs_gpu_shaders.cpp` `kUpload` / `storeAt`;
  `executeTransfers()`.
- **Idea:** Uploads write one pixel per invocation, and for the
  PSMT8/PSMT4 formats God Hand streams constantly, each pixel is
  a pair of global atomics. Pack a full 32-bit word per
  invocation where the staged data allows (the upload is
  host-staged, so alignment is knowable on the CPU side),
  reserving atomics for edge fragments. Pillar 1 already skips
  ~30% of fight upload bytes; this cheapens the rest.
- **Risk:** Low. Upload contents are byte-exact by construction;
  the golden gates cover it. Keep the atomic path for partial
  words.
- **Log proof:** profiler `upload` pass share falls; ring/upload
  byte counts unchanged (same data, fewer, wider writes).
- **Size:** evenings.

### G6. Raster shader micro-costs: coverage under block shading, early-outs

- **Area:** `gs_gpu_shaders.cpp` `kRaster` (`coversPixel`,
  `drawPrim`, `samplePoint`, `writePixel`).
- **Idea:** Two priced-by-G1 micro-items. (a) Under block
  shading the block leader evaluates `coversPixel` up to 4× per
  prim (once per block position) before shading once — coverage
  cost did *not* quarter when shading did; a shared-edge
  evaluation or per-prim block-mask precompute in the tile
  header can cut it. (b) Depth-first ordering inside `drawPrim`:
  hoist the depth read/test above the texture taps where the
  prim's flags allow (many God Hand prims are opaque; for those,
  a failed z need not pay 4+ SSBO taps first). Both are
  shader-local, switchable by shader variant, and exactly what
  the G1 ablations price before anyone builds them.
- **Risk:** Moderate-low. (b) changes evaluation order, not
  results — but GS blending/date-test interactions must be
  audited per flag combination; the ablation numbers decide if
  it's worth it at all.
- **Log proof:** profiler rasterCompute share falls at constant
  prim/tile counts; ablation deltas reproduced as real deltas.
- **Size:** evenings to a week.

### G7. Present: kill the CPU round trip

- **Area:** `gs_gl.cpp` (Android context), `GSGpuBackend::Present`
  / `presentAsync`, `ps2_runtime.cpp` `UploadFrame`.
- **Idea:** Give the GS context a real window surface
  (ANativeWindow via the glue's existing surface plumbing) or an
  AHardwareBuffer hand-off, composite `kPresent` directly into
  it, and retire the readback-slot + `glReadPixels`-class copy +
  raylib texture re-upload chain — including mode 2's built-in
  one-frame latency. At today's 3.5–4 presents/s this is worth
  little; at the field rates G2/G3 aim for, ~1.15 MB/frame of
  memcpy + upload and a frame of input latency are a real tax,
  and the m43 lesson (present pacing is where regressions hide)
  says the present path should be as thin as possible before it
  becomes the bottleneck again.
- **Risk:** Moderate. Surface lifetime negotiation with
  NativeActivity/raylib is the fiddly part (M11 chose the
  pbuffer because raylib owns the window context outside the
  backend's share group — that constraint must be re-solved,
  not ignored). Overlay/FPS-label ordering rides on this too.
- **Log proof:** `[frame-split]` present buckets: `gs-fetch`
  share falls, sync/copy time ~0, frame age drops from ~3–4
  to ≤2 fields.
- **Size:** about a week.

### G8. Selection & defaults surgery (the end-state enabler)

- **Area:** `godhand_android_main.cpp` (variant seed block,
  `PS2X_GS_GPU` write), `gs_threaded_backend.cpp` selection,
  shell settings surface.
- **Idea:** (a) Ship the native-seed build variant: no
  `PS2X_GS_RENDERER=pcsx2` seed (the m25 construction, updated —
  the seed resolves native, the ini can still choose `pcsx2`).
  (b) Stop the glue's unconditional `PS2X_GS_GPU` overwrite from
  forcing the CPU backend on native boots: on the native path
  the GPU backend becomes the default (toggle becomes an
  explicit CPU fallback choice, default on). (c) Reverse
  fallback: a latched native-open failure (GL context creation
  in `Impl::ensure`) swaps to the bridge through the same
  `GS::setRasterBackend` latch hook m44 built — m44's
  bridge→native swap stays as the second rung, so the chain is
  native → bridge → CPU, each rung only on a *latched open
  failure*, never mid-session guessing. (d) Pablo's phone: his
  persistent `native` ini stops being poison and becomes simply
  correct; the boot log's settings lines name the new chain.
- **Risk:** Low as code (the m25 build proved the construction;
  m44 proved the swap seam). The risk is *sequencing*: flipping
  the default before G1's baseline exists would waste the one
  clean before/after comparison. §3 orders it as m47 anyway —
  because every later measurement must run in the end-state
  configuration to mean anything — with the profiler run
  immediately after as m48.
- **Log proof:** boot shows the native seed line, `[gs] renderer`
  naming the built-in GS, `renderer: gpu (owner setting)` no
  longer lying (M42 §1: today that line prints the toggle, not
  the backend — the backend proof line is the `[gs]` one).
- **Size:** an evening or two.

### G9. Gate and ring tuning, after the costs fall

- **Area:** `gs_gpu_backend.cpp` `fieldGate()` / `ringPut()`,
  `PS2X_GS_QUEUE_DEPTH`, ring chunk sizing.
- **Idea:** With per-field GPU time down, re-tune what ARUS
  shipped blind: K=2 was chosen when a field cost 53–80 ms.
  Cheaper fields may want K=1 (a field less of latency) or
  expose the depth in the shell's Developer surface. The 48 MB
  ring fence becomes proportionally larger in field terms as
  uploads shrink — re-denominate if the accounting says so.
  Tuning only; no mechanism changes.
- **Risk:** Low (switches exist; the pre-ARUS path is one value
  away).
- **Log proof:** frame age and fence ms/field at the new K vs
  the old, same fight.
- **Size:** an evening.

### 2.10 What m46 would have done, and where it went

- **Bridge texture lightening** (halve host texture resolution
  in the bridge's texture cache): **moot in the end state** —
  the native backend has no host textures to halve; guest
  textures *are* VRAM pages. Its intent (cheaper texture work)
  survives as G3 (texture-unit sampling), G5 (cheaper uploads)
  and the already-shipped pillar 2 (cheaper shading). Nothing
  is lost; the bridge implementation would have been thrown
  away at the role reversal anyway.
- **Bridge effect/particle skipping:** the safe-classification
  core of that idea reappears inside G2 — flush classes are
  exactly where optional effect passes can be identified in
  the native stream, with the same rule m46 was given (real
  geometry/characters are identified-not-cut without
  game-logic proof).
- **Steadier pacing:** already partly shipped (the m43
  clipChildren bisect rides in every build since), and the
  native side of it is G7 + G9.

---

## 3. Proposed build sequence

Discipline, same as the project's bisect culture (m43 is the
template): one variable per build, a kill switch per change,
the device proof lines named *before* the build ships, installs
over the previous build, same signer. Every build below starts
from the clean m45 base (§0) plus the previous stage-2 build.
The tree is free for m47 the moment the m46 lane's stop is
confirmed — §0 says it already is.

- **m47 — the flip (G8a + G8b only).** Native-seed variant:
  no compiled-in `pcsx2` seed; on the native path the GPU
  backend is the default selection (shell GPU toggle becomes
  the explicit CPU-fallback choice, default on). No renderer
  code changes. Purpose: (1) prove the end-state configuration
  boots and plays on the POCO F5 — the first native-default
  build since m25; (2) capture the true native baseline in the
  exact configuration all later builds improve: `[gs-work]`
  fence ms/field and presents/s in a fight, frame age, the
  m39-era audio behaviour on the native path. Proof lines:
  the settings seed lines naming native, the `[gs]` backend
  line, absence of any `[pcsx2-gs]` line.
- **m48 — the measurement (G1).** m47 + the ablation port
  (default 0 = m47's shader byte-for-byte in behaviour) and a
  variant seed enabling `PS2X_GS_GPU_PROFILE=1`. Pablo runs
  one profiler fight + three short ablation fights. Output is
  not a verdict on feel; it is the priced split (raster vs
  serialization vs clut vs upload) that orders m49+. If the
  profiler somehow cannot run on device, that is learned here,
  cheaply, before any fix is built on suspicion — the M24
  mistake (a wall diagnosed but never separated) is not
  repeated.
- **m49 — the convicted fix, first class (G2 *or* G6, per m48).**
  Exactly one mechanism, behind its switch, default on.
  If serialization was convicted: flush coalescing for the
  single safest hazard class from the flush histogram.
  If raster internals were convicted: the single largest
  ablation-priced shader item (coverage sharing or depth-first
  ordering). Golden-replay gates pass before the APK is
  packaged; the device proof is fence ms/field moving by at
  least the ablation's predicted amount — a fix that doesn't
  move its predicted line is reverted, not tuned blindly.
- **m50 — the cheap pair, one at a time (G4, then G5).**
  Two builds if discipline holds (m50 = CLUT content dedup,
  m51 = upload packed writes); combine only if m48 priced
  both as noise-level, in which case they ship as one build
  with two switches and the report says why. Each judged on
  its profiler pass share.
- **m52 — present thinning (G7).** Zero-copy/direct-surface
  present prototype behind a switch, default on; mode-2
  readback present remains the fallback value of the same
  switch. Proof: `[frame-split]` buckets + frame age ≤2.
- **m53+ — the long pole, if priced (G3).** Texture-object
  sampling as an experimental backend variant selectable in
  the same APK (incumbent SSBO path remains default). A/B in
  identical fights: fence ms/field, profiler split, golden
  gates, owner's visual verdict on feedback effects (the
  known boundary risk). It becomes the default only when it
  beats the incumbent on the metric *and* passes the gates;
  until then it is a Developer-surface experiment, in the
  project's standing pattern for switchable experiments.
  In parallel slots (never in the same build): G2's remaining
  hazard classes, one per build, if m48 convicted
  serialization and m49's first class paid off.
- **The roles-reversed release (G8c + G9).** When native ≥
  bridge on POCO fight metrics (fence ms/field at or below
  the bridge's fields/s equivalent, no open correctness
  items): ship the end-state configuration of §4 as a public
  release — native default, bridge selectable, dual fallback
  chain live, gate re-tuned. Before promoting, one Mali
  field check through the existing public channel (the m44
  reporter has already volunteered to keep testing): the
  release notes ask for his boot log by name.

If m48's numbers say the native ceiling really is the
architecture and no priced fix closes the gap, the honest
exit is written down in advance: stop after m50, keep the
bridge as default, and let stage 2's deliverable be the
measurement + the cheap wins on the *fallback* path (native
still improves as the Mali answer, which needs "playable",
not "fastest"). Pablo decides that with m48's numbers in
hand; nobody decides it from lore.

---

## 4. End-state configuration

| Layer | Today (m45) | End state |
|---|---|---|
| Renderer seed | Variant-compiled `pcsx2` | None (native); ini `renderer=pcsx2` still selects the bridge |
| Native inner default | CPU (glue forces `PS2X_GS_GPU` from a default-off toggle) | GPU backend (`GSGpuBackend`); CPU is the explicit fallback choice |
| ARUS | All four pillars on, three of them dormant (wrong layer for the bridge) | All four live on the main path; `arus=off` still restores pre-ARUS wholesale |
| Fallback chain | Bridge open-fail → native (m44), lands on CPU on stock installs | Native open-fail → bridge (new, m44's seam reversed); bridge open-fail → native (m44, unchanged); final rung CPU backend — a picture on every GPU the app can open a context on at all |
| Shell surface | "GPU renderer" toggle (a proven no-op on the shipped build, M42 §1) | Renderer choice that tells the truth: Native / PCSX2 (bridge) / CPU, with the backend proof line in Developer mode |

**Mali in that world:** the bridge's death on Mali was a
*Vulkan* death — PCSX2's device/pipeline requirements. The
native path speaks GL ES 3.2 compute + SSBO, a different and
older contract that Mali-G610-class GPUs implement in the
stock driver; nothing in the m44 evidence implicates it. The
honest unknowns are Mali GL driver *quality* under this
shader load (dependent SSBO chains are exactly what weak
drivers serve worst) — which is why the Mali field check is
a named gate before the roles-reversed release, and why, if
native GL also fails or crawls on a given Mali, the chain
still lands on the CPU backend: a playable picture instead
of the m42-era permanent black screen. "All Androids" is
served by the chain, not by any single rung.

---

## 5. What stays true throughout the campaign

- **The translated game brain is untouched.** Every item
  above lives in `runtime/ps2xRuntime/src/lib/gs/`, the
  selection code, and the Android glue/shell settings
  surface. No generated EE/VU code, no `function_hooks`
  changes, no disc/hash/ISO-layer changes (m45's work is
  unaffected), no game data ships — the player's own disc
  remains the only source of the game, as always.
- **Shell, audio, input are unaffected.** The m39 audio
  feed, the m40–m42 control schemes and Player/Developer
  modes, and the m43 pacing bisect carry through every
  stage-2 build byte-identical except where G8 relabels the
  one renderer setting — and that change is named in its
  build's report, per discipline.
- **Save states get *simpler*, not riskier:** the native
  backends own the shared 4 MB VRAM image natively; it was
  the bridge that needed a translation layer for snapshots.
- **Every mechanism keeps its kill switch.** `arus=off`
  still restores the pre-ARUS build wholesale; each stage-2
  item ships behind its own switch in the same idiom, so
  any regression is one ini line from a bisect.
- **The bridge is not deleted or deprecated** — it becomes
  what m44 made the native backend: the fallback that turns
  a dead screen into a playable one, plus a Developer-surface
  choice for players whose GPUs prefer it.

## 6. Size summary, honestly

| Item | Size |
|---|---|
| G8 selection/defaults (m47) | an evening or two |
| G1 measurement (m48) | an evening |
| G4 CLUT dedup, G5 upload packing, G9 gate tuning | evenings each |
| G6 raster micro-costs | evenings to a week, per item |
| G7 present zero-copy | ~a week |
| G2 flush coalescing | weeks, one hazard class at a time, gated on G1 |
| G3 texture-object sampling | the long pole — several weeks, incremental behind a switch, gated on G1 |

The campaign's shape in one line: two evenings of flips and
measurement (m47–m48) buy the priced truth M24 asked for;
everything after is spent only where that truth points.
