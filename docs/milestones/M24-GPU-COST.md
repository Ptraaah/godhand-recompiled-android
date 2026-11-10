# M24 — GPU-cost investigation: where a fight field's 53-80 ms of GPU time goes

**Verdict up front: the wall is architectural. No 0.25 fix build was made.** The remaining
fight cost is the compute software-rasterizer design itself — a per-pixel shader program
that addresses a flat VRAM SSBO by computed swizzle offsets, fed at GS state-hazard
granularity (~54-87 flush groups per field, ~200-260 barrier-separated GPU passes per
field) — and every targeted surgical win available inside that design has already been
taken (M18 narrowed barriers, M19 upload dedup, M20 block shading, M21-M23 async
readback, M22 queue gate). The existing device log can bound every remaining candidate
*below* the observed total but cannot separate the top two; the instrument that can
(the renderer's own GPU timer-query profiler) exists in the shipped code and has simply
never been switched on on device, and the shader ablation switches that would complete
the separation exist only in the HIRES shader variant, not in the shipping 1x + block-
shading configuration. Building a "fix" against an unseparated suspect list would be
guessing with Pablo's test time. Sections 5 and 6 give the decisive measurement (one
owner run, no new code for half of it) and the concrete path-B costing.

Everything below is labelled: **[log]** = measured in owner-0.24-run/session-last.log,
**[code]** = read in the shipped sources, **[arith]** = arithmetic on those two.
No number in this report comes from a host GPU run, for reasons stated in section 4.

---

## 1. The established frame (recap, with the receipts)

Fight windows of the 0.24 run **[log]** ([gs-work] lines; per-field values derived):

| Window | fields | prims/f | flushes/f | tiles/f | invocations/f | fence waits | fence ms | ms/field | loadClut/f | clutOps/f | ring MB/f | presents |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| W06 (line 353) | 56 | 3,918 | 37 | 6,102 | 1.56 M | 61 | 4,722.7 | 77.4 | 213 | 148 | 1.35 | 13 |
| W07 (line 404) | 80 | 1,906 | 54 | 8,317 | 2.13 M | 83 | 4,419.0 | 53.2 | 191 | 81 | 0.86 | 18 |
| W08 (line 443) | 58 | 2,109 | 87 | 13,912 | 3.56 M | 66 | 4,070.2 | 61.7 | 328 | 89 | 1.05 | 18 |
| W09 (line 476) | 44 | 2,013 | 81 | 13,759 | 3.52 M | 49 | 3,930.0 | 80.2 | 314 | 90 | 1.04 | 15 |
| light (line 340) | 269 | 189 | 12 | 2,519 | 0.64 M | 301 | 4,710.0 | 15.6 | 15 | 11 | 0.39 | 34 |

- "tiles" is `stats.tiles` = touched 16x16 workgroups summed over flushes **[code:
  workMaybeReport / flushBatch]**; invocations = tiles x 256 (the raster shader's
  workgroup size) **[code: kRaster `local_size 16x16`]**.
- The fence waits count ≈ the fields count in every fight window (49-83 waits vs
  44-80 fields), i.e. they are the ARUS field gate's waits, one per field **[code:
  Impl::fieldGate; WorkWindow fence counters]**. CPU-side submission of a whole field
  costs ~2.6 ms (submit + flush CPU per window / fields) **[log+arith]**, and
  [ee-work] reports `gsIdle 1-4%` with the EE parked 4.5-4.9 s per window waiting on
  the GS queue (`queueWait avg 272-307 ms`) **[log]** — the GPU stream, not the CPU
  and not the guest, is the pacer. Per-field GPU-stream time is therefore the fence
  wait per field: **53-80 ms in fights, 15.6 ms in the light window**.
- `busy 95-98%` in [gs-work] is the GS *worker's* time inside backend entry points
  (which includes the waits themselves) **[code: WorkWindow::busyMs]** — corroboration
  that the worker is never the constraint, not an independent GPU-utilisation counter.
- readData is 761-1,101 ms/window (was 3,210-3,332 in 0.23) and presents did not
  follow it down; across 0.23 and 0.24, presents track fence time at matched prim
  counts **[log, per owner-0.24-run/ANALYSIS.md]**. Pillar 3 is done; this report is
  about what the fence time is made of.
- [frame-split] agrees from the present side: in fights, 80-94% of each present's
  253-395 ms is `gs-fetch` (waiting for the GS), 0-7% is sync, ~1% draw **[log]**.

## 2. What one flush actually submits to the GPU [code]

On Android the hardware rasterizer is compiled out (`setupHardwareRaster` returns
false under `__ANDROID__`, M11: its fragment stage needs desktop pixel interlock).
**The compute rasterizer is the only renderer.** A batch accumulates in `Submit()`
until a hazard forces `flushFor(reason)`: reason 0 = draw-target key change
(fbp/fbw/psm/zbp/zpsm), 1 = texture feedback (a draw reading pages an earlier draw in
the batch wrote, or vice versa — `prepareTarget`'s page-set test), 2 = CLUT source
overlap, 3 = a guest transfer, 4 = external, 5 = sync. God Hand's fight draws change
texture/target constantly, which is why a field arrives as 54-87 flushes of ~24 prims
each instead of one draw list.

Each `flushBatch()` then issues, in order:

1. `executeTransfers()` — one upload compute dispatch per upload group plus page-copy
   dispatches for pending host->local work, each followed by `barrier()`.
2. `dispatchClutOps()` — **one** compute dispatch covering all pending CLUT ops
   (workgroup x = op index, 256 threads per op), followed by `barrier()`.
3. **One** raster compute dispatch: workgroups = touched tiles of this flush,
   followed by `barrier()`. Before it, four buffer uploads through the persistent
   ring (`bindUpload` of prims, states, tile headers, tile list).

`barrier()` on Android is `glMemoryBarrier(SHADER_STORAGE | SHADER_IMAGE_ACCESS |
BUFFER_UPDATE)` — already narrowed from GL_ALL_BARRIER_BITS in M18, after the 0.18
device log showed the wide version draining the whole pipeline (tile memory
included) and completing fences ~200 ms late **[code: barrier() comment]**. That
history matters for section 3.2: barrier cost on this driver is a measured, real
phenomenon in this codebase, not a theory.

Per fight field the totals are therefore roughly: **54-87 raster dispatches, up to
~87 CLUT dispatches, up to ~87 upload-group dispatches, ~200-260 GPU passes in all,
each pass separated from the next by a storage/image barrier**, plus ~0.9-1.35 MB of
ring traffic (prim records alone are 40 words x 4 B x ~2,000 prims ≈ 0.32 MB/field;
tile headers 4 words x ~14k touched tiles ≈ 0.22 MB/field; the rest is tile lists,
states and staged texture payloads) **[log ring bytes + arith]**.

## 3. Ranked costs

### 3.1 Rank 1 — Raster compute shading over the flat VRAM SSBO (suspect core, unseparated)

The raster shader (`kRaster` in gs_gpu_shaders.cpp) is a software rasterizer: each
invocation owns one guest pixel (one per 2x2 block under block shading), walks its
tile's prim list, and per covered prim runs the full GS pipeline in shader code.
The per-sample memory behaviour is the architectural problem:

- Texture sampling is `samplePoint` -> `locate()` (integer swizzle math plus a
  lookup-table SSBO load) -> `loadAt()` (a VRAM SSBO load). Bilinear filtering = 4
  such taps. Indexed formats add `lookupClut()` = two further SSBO loads into the
  CLUT buffer. **No access goes through the texture unit or its cache**; every tap
  is a general-purpose buffer load whose address is computed from the PS2 swizzle
  layout, so spatial locality in screen space does not imply locality in buffer
  space beyond one GS page.
- `writePixel` then performs a *dependent* chain: framebuffer SSBO read (needed for
  blending / fb-mask / destination-alpha test), depth SSBO read for the z test —
  there is no early-z; a fragment that will fail depth has already paid its texture
  taps and both reads — then the framebuffer write (a plain store for 32-bit
  formats, a paired `atomicAnd`/`atomicOr` for 16/8/4-bit formats via `storeAt`),
  then the depth write. Sub-32-bit framebuffer formats are common in God Hand's
  effect passes, so the atomic path is not rare.
- Coverage is also paid per pixel per prim: `coversPixel` runs 64-bit edge
  arithmetic (`imulExtended` pairs) for every prim in the tile's list, and under
  block shading the block leader runs it up to 4x per prim (once per block
  position) before shading once.

Evidence for rank 1: it is the only pass class whose input volume scales with the
fence time across all five windows above (prims/field 189 -> 15.6 ms; ~1,900-3,900
-> 53-80 ms) **[log+arith]**, and its per-sample cost structure (dependent SSBO
chains, no texture cache, atomics on sub-32-bit targets, no early-z) is exactly the
pattern mobile GPUs serve worst **[code]**. Block shading (M20) already cut the
shading rate 4x and the raster CPU-accounting is small — yet the fence time stayed
in the same band, which says the residual is in the *per-sample* cost, not the
sample count alone.

What bounds it from below: at an optimistic ~1,000 effective cycles per shaded
sample and ~0.9 M block samples/field (touched tiles x 64), the shading throughput
floor is only a few ms/field **[arith]**. For shading to be the whole 53-80 ms,
the samples must be running at latency-bound (low-occupancy, stall-dominated)
rates — plausible for this register-heavy shader with 4-6 dependent global loads
per sample, but **not measurable from any counter the 0.24 log contains**. That is
precisely the gap section 5 closes.

### 3.2 Rank 2 — Pass serialization at flush granularity (~200-260 passes/field)

The flip side of the same coin. Every flush's passes are separated by storage
barriers, and every pass depends on the previous pass's SSBO writes (draws read
what earlier draws wrote; that is the GS feedback model). On a tile-based mobile
GPU a compute dispatch cannot overlap the next when a full storage barrier sits
between them: the queue runs as a strictly serial chain of ~200-260 small passes
per field (a raster pass here is only ~160 workgroups on average — 24 prims'
worth). If the average dependent-dispatch turnaround on this driver were ~50 µs,
serialization alone is ~10-13 ms/field; at the ~200 µs scale M18 observed for
barrier pathology, it would be the entire wall **[arith on log counts; M18 code
comment]**. The truth is somewhere in that range and nothing in the current log
locates it. Ranked second only because rank 1's volume scaling is directly
visible in the log while this one's per-unit cost is not; they are also not
independent — merging flushes (the fix for rank 2) is blocked by the same hazard
semantics that create the flushes, and larger passes would amortize rank 1's
fixed costs too.

Note what does **not** explain W07 vs W09: near-identical prims/field (1,906 vs
2,013) but 53.2 vs 80.2 ms/field. W09 has ~1.65x W07's tiles/field and ~1.5x its
flushes/field. Any single-knob theory (pure prims, pure flushes) fails one of the
two windows; a mix of 3.1 and 3.2 fits both. That is the honest state of the
evidence, and it is why no single fix is unambiguous.

### 3.3 Rank 3 — CLUT pass volume (real, but folds into rank 2)

**[log]** 12k-19k `LoadClut` frontend calls and 3.9-8.3k dispatched CLUT ops per
window (81-148 ops/field). **[code]** each op is one 256-thread workgroup whose
every invocation performs a swizzled VRAM read (`clutHalf` -> `readPixelEpoch`),
i.e. ~512 SSBO reads per op to rebuild a 256-entry palette; ops are batched into a
single dispatch per flush, so the op count costs shader work linearly but adds no
extra dispatches beyond the per-flush one. The shader work is small next to rank
1's; its risk is only as part of rank 2's pass count. Not independently fixable in
a way that would show: the frontend already dedups repeat loads (`clutKeyValid`),
and the remaining ops are genuine palette changes by the game.

### 3.4 Rank 4 — Upload streaming and per-pixel atomic upload writes

**[log]** staged upload payloads 13.5-25 MB/window and total ring traffic
45-121 MB/window (~0.9-1.35 MB/field); CPU-side `executeTransfers` is 54-273
ms/window (small). **[code]** the upload shader (`kUpload`) writes one pixel per
invocation through `writePixelRaw`/`storeAt`, which for the indexed formats God
Hand uploads constantly (PSMT8/PSMT4) is a pair of global atomics per pixel, and
the M19 dedup already skips ~30% of fight upload bytes before dispatch. GPU-side
upload time is not separated in the log; bounded by payload volume it cannot be
the majority of 53-80 ms/field, but its atomic-write pattern keeps it on the list
below the top two.

### 3.5 Exonerated, with evidence

- **readData / pillar 3**: fixed in 0.24 (761-1,101 ms vs 3,210-3,332) and presents
  provably do not track it **[log, ANALYSIS.md]**. The 3 remaining sync scan
  starters per window park the EE (940-1,672 ms/window of consume park), but that
  time overlaps the GPU stream the EE is waiting on anyway.
- **Present path**: 23-40 ms CPU per window, 15-34 presents; [frame-split] shows
  present time is 80-94% waiting (`gs-fetch`), ~1% draw **[log]**. A victim.
- **CPU submission and binning**: ~2.6 ms/field all-in **[log+arith]**.
- **EE/VU1 guest compute as the pacer**: [ee-work] shows the EE parked ~92% of
  fight windows waiting on GS (`gsIdle 1-4%` on the worker side) **[log]**. VU1
  time inside [ee-prof] is real CPU work but it is not what the queue waits on.
- **FP64**: the rasterizer's `double` Z math is demoted to float by the ES shader
  shim (rules D1/D2 in gs_gpu_backend.cpp: GLSL ES has no double; `pd()` decodes
  host double bits to float). There is no FP64 cost on device **[code]**. (The
  HIRES path's float-Z scheme exists for desktop rate reasons; on device it is
  already float.)

## 4. Why there is no host-side measurement in this report

Stated plainly, as instructed: **the host cannot exercise the real fight path.**
(1) This VM has no Adreno — indeed no discrete GPU; the desktop GL build would time
a different driver on different silicon, and the two unknowns that decide the
ranking (Adreno dependent-dispatch turnaround after storage barriers, and Adreno
SSBO load throughput/latency for this shader) are properties of the phone's driver
and hardware, not of the code. (2) The desktop build is a different renderer
configuration: the hardware rasterizer path exists there, barriers are
GL_ALL_BARRIER_BITS there, and doubles are real there. (3) No GS recording of a
fight exists in the tree to replay (the recording/replay tooling exists —
ps2xTest/gs_replay — but no captured fight stream is present). Any host number
would be invented precision. The ranking above therefore rests on device-log
arithmetic and code structure only.

## 5. The decisive measurement (what to run before any fix)

The renderer already contains a per-pass GPU timer profiler
(`PS2X_GS_GPU_PROFILE=1`, GL_EXT_disjoint_timer_query — the 0.24 boot log confirms
the extension resolves on this Adreno 725). It was **off** in the 0.24 run (zero
`[gs-gpu-profile]` lines in the log). One owner run with it on prints, every 120
frames: GPU busy ms/frame, the split by pass kind (rasterCompute / clut / upload /
copyRead / copyWrite / present / clear / pageCopy), the heaviest frame's raster
passes broken out by prims/fbp/fpsm/flags, a per-target raster cost table, and the
flush-reason histogram (target / texture / clut / transfer / external / sync) that
section 2's flush analysis needs. That single run separates rank 1 from ranks 2-4
by pass kind, at zero code cost.

The second half — separating, *inside* rasterCompute, coverage vs texture sampling
vs framebuffer/z traffic — needs the ablation switches. They exist today only in
the HIRES shader variant (`gsDebug`: 1 = no texture reads, 2 = shade without
writing, 4 = coverage only), fed from a scale buffer that is only created when
scale > 1 **[code: Impl::ensure]**. In the shipping 1x path they do not exist.
Porting them is small and contained: the raster shader's uniform `uRaster` has
exactly one read in the 1x path (`.x`, the final epoch) **[code: gs_gpu_shaders]
** and the host already passes `Uniform4ui(loc[0], epoch, 0, 0, 0)` — components
y/z/w are free to carry a debug word, with the three early-outs placed at
`sampleTexture`, at the `writePixel` tail, and at the top of the tile loop,
mirroring the HIRES semantics. Three short runs (or one run per flag) then price
each stage of section 3.1 directly. This is a measurement build, not a fix, and it
is the gate any future in-renderer fix should have to pass: a candidate fix must
name which profile line it moves, by how much, before it is built.

**The one number any resulting device test is judged on: fence ms per fight window
— equivalently the average field-gate wait per field (53-80 ms/field, 3,930-4,722
ms/window in 0.24).** Presents/s is the player-visible shadow of that number and
moves only when it moves.

## 6. Path B — PCSX2 GS as the renderer: what it concretely takes

The seam already exists. `GSPcsx2Backend` (include/runtime/gs/gs_pcsx2_backend.h)
implements the same `GSRasterBackend` interface the compute renderer sits behind:
it takes the **raw GIF stream** (`WantsRawGif`/`RawGif`), initializes against the
shared 4 MB host VRAM image, self-presents, and exposes `ReadTransfer` /
`ConsumeLocalToHostBytes` for the threaded backend's download contract — the exact
boundary ARUS pillar 3's cache already speaks to. On Windows this class wraps
`pcsx2gs.dll`; in the Android build every entry point is a shell returning false
**[code: gs_pcsx2_backend.cpp, 601 lines]**. Path B is filling that shell with
PCSX2's real GS, built for arm64, using its Vulkan (or OpenGL) hardware renderer —
real texture units, real render targets, hardware early-z: the entire cost class
of section 3.1 (programmatic swizzle sampling out of an SSBO) ceases to exist,
replaced by PCSX2's texture cache, which is the most battle-tested answer to
exactly God Hand's workload (heavy framebuffer-feedback effect passes) that exists
in open source. Existence proof on this hardware class: AetherSX2/NetherSX2 ran
PCSX2's GS at full speed on Android Adreno devices for years.

Concrete work items, in dependency order:

1. **GS core extraction for arm64 (the long pole, ~1-2 weeks).** The in-tree
   `thirdparty/pcsx2-gs` slim extraction is D3D11-only and WIN32-only in CMake,
   and per SPIKE.md it still carries Urban Reign's GameIndex fixes and serial.
   The fuller `thirdparty/pcsx2` source is present. The job: carve GS + its Vulkan
   renderer + the Util-layer subset it needs (threading, config, timers, file
   system) into a static library that builds under the Android CMake/NDK flow.
   Known sub-fights: PCSX2's build assumes desktop Linux/Windows; its Vulkan
   shaders are compiled GLSL->SPIR-V via glslang at runtime upstream, which on
   Android becomes either a bundled glslang or precompiled SPIR-V blobs.
2. **Presentation seam (~days, inside item 3's week).** PCSX2 GS presents to its
   own swapchain. On Android it must render into the raylib shell's
   ANativeWindow surface (or into a texture our present path composites, if the
   FPS label / touch overlay ordering requires it). The interface's OSD hooks
   (`SetOsd`, `HostUiVisible`) already anticipate the overlay negotiation.
3. **Transfer and snapshot contracts (~1 week with item 2).** Implement
   `ReadTransfer`/`ConsumeLocalToHostBytes` over PCSX2 GS's local-memory download
   path, preserving the threaded backend's async-readback behaviour contract
   (the scan/prefetch machinery keys off `ReadTransfer` results — it survives the
   swap unchanged, it just gets a different inner backend). Implement
   `SnapshotVram` against PCSX2 GS's state/VRAM image so save states keep working;
   upstream GS state serialization exists but its format is not our 4 MB image,
   so this is a translation layer, not a memcpy.
4. **GameIndex and tuning (~1-2 weeks, overlapping correctness testing).** Strip
   Urban's serial/fixes, add SLUS-21503 entries only where God Hand needs them;
   then tune the knobs that replace the ARUS switches in practice (texture-cache
   and blending-accuracy settings vs Adreno throughput), and re-verify the known
   sensitive spots: the l2h scan reads (M14 classes), the framebuffer-feedback
   post chain, sub-32-bit framebuffer effects. God Hand's later stages are
   unverified even upstream (SPIKE.md), so correctness testing is bounded by the
   owner's route, as with every milestone so far.
5. **What path B does not change:** the EE/VU1/recompiler side (proven not to be
   the pacer, section 3.5), the threaded backend and its readback cache, the
   shell, the ini surface (renderer selection already exists:
   `PS2X_GS_RENDERER`), and the licence posture (the tree is already GPL-3.0).

**Estimate: 4-6 weeks of focused work** — 1-2 for the GS/Vulkan arm64 build and
link, ~1 for presentation + transfer/snapshot seams, 1-2 for God Hand correctness
and tuning. Principal risks: PCSX2 GS's Vulkan renderer on Adreno (mitigated by
the AetherSX2 precedent, not eliminated — that fork carried its own patches),
APK size growth, and the loss of the ARUS pillar switches as user-facing controls
(they are replaced by PCSX2's settings model; the ARUS work itself — readback
cache, queue gate, dedup discipline at the threaded layer — carries over).

## 7. Recommendation

Do not build another in-renderer fix on suspicion. Run the section-5 measurement
(one owner run, profiler on; the ablation port only if the profiler split leaves
rasterCompute dominant and unexplained). If rasterCompute is confirmed as the bulk
of the fence time with sampling/coverage priced the way section 3.1 fears, the
in-renderer ceiling is the architecture and path B (section 6) is the spend that
matches the problem. If the profiler instead convicts pass serialization (rank 2),
a flush-coalescing fix inside our renderer becomes the unambiguous, buildable
candidate — and only then.
