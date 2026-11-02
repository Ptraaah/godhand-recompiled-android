# AUDIT-M6 — What-if audit of the 1 FPS problem (read-only)

Scope: why does 0.6-m5 run the GOD HAND title screen (and in-game) at ~1 FPS on the
owner's POCO F5, when 0.4/0.5 (headless, no CD cache) at least moved? Method: code
inspection only, no builds, no edits. File references are to the tree as of this audit;
note that `Support.h`'s CD layer is being rewritten concurrently by the M6 fix, so the
CD quantification below describes the **shipped 0.6-m5** code, which is what ran on the
phone.

Established device facts used as constraints: threads on/off makes no difference
(owner A/B, worst frames 944–1230 ms either way) → the M5 thread-affinity governor is
acquitted and is not re-examined. Sound is clean for a split second at the intro start,
then chops in sync with the frames. The intro FMV (0.4/0.5) played, stuttery but moving.

---

## First, the assigned quantification: can the M5 CD cache explain CONTINUOUS ~1 s frames?

**The path.** EE-side `sceCdRead` is an HLE stub (`Kernel/Stubs/CD.cpp`) that calls
`readCdSectors` → `readHostRange` (`Kernel/Stubs/Helpers/Support.h`) **synchronously on
the EE thread**. God Hand's CRI file system reads `_DATA.AFS` through this EE path (the
0.5 boot log shows EE-side `sceCdSearchFile` opening `\_DATA.AFS;1`). So every guest
disc read is paid for inside an EE frame, in full, before the guest continues.

**The 0.6-m5 cache geometry.** 4 MiB file-aligned blocks, 8 slots (32 MiB), LRU. A miss
fills one whole 4 MiB block synchronously under `streamsMutex`; a miss on the block
right after the previous miss also fills the **next** block synchronously (another
4 MiB). One unlucky guest read can therefore perform 8 MiB of flash I/O on the EE
thread while holding the stream lock.

**What does a fill cost on his storage?** The only two storage measurements in evidence
are from the 0.5 log: 4 KB in 51 ms and 315 KB in 41 ms. Those two points are
**inconsistent with a pure bandwidth limit** (at a flat 7.7 MB/s the 4 KB read would
take ~0.5 ms, not 51 ms). They fit a **fixed latency of ~40–50 ms per cold read** plus
a transfer rate of at least ~30–100 MB/s. Two cost models for one sequential-miss
event (fill + prefetch = 8 MiB):

- Latency model: ~2 × (50 ms + 4 MiB ÷ ~50 MB/s) ≈ **0.25–0.35 s**.
- Bandwidth model (7.7 MB/s sustained, e.g. storage saturated by something else):
  8 MiB ÷ 7.7 MB/s ≈ **1.05 s** — uncomfortably close to the observed 1230 ms worst
  frame once the frame's own work is added.

**Continuous or spikes?** To hold a *steady* 1 FPS from the cache alone, the title
screen would need roughly one sequential-miss event **per frame** (bandwidth model) or
several misses per frame (latency model). After its load burst, the title's EE-side
disc traffic is basically the CRI ADX refill cadence — order one sequential read per
0.5–2 s from one AFS region — which this cache, once warm, serves from RAM, with one
paired fill per ~8 MiB of streamed music (tens of seconds). **Verdict: the cache as
shipped is a proven spike machine** — it fully explains hitches at menu entry / NEW
GAME, individual worst frames of 0.3–1.2 s, and sound dropouts when a fill lands under
an ADX refill — **but it does not, by itself, explain a steady 1 FPS at an idle title
screen** unless the game's reads thrash between distant AFS regions nearly every
frame. A second, per-frame or whole-session cost must exist. (In-game streaming
interleaves more regions, so the cache's share is plausibly larger there; the M6
redesign removes the question either way.)

**Decisive evidence, already in the owner's 0.6 boot log:** the
`[cd-cache] after 60 s: hits=… misses=… prefetches=…` line (misses/minute at the title
distinguishes per-frame thrash from per-minute spikes), and any
`[cd-cache] slow block fill: X ms for 4194304 bytes` lines (X discriminates the two
cost models directly: ~100 ms → latency model, ~550 ms → bandwidth model).

---

## Suspect 1 — CPU rasteriser raw cost at the title scene

**Mechanism.** The "native" GS is a software rasteriser. GIF packets are queued by the
EE to a single GS worker thread (`GSThreadedBackend`), which runs the CPU backend;
triangle/sprite batches are split into horizontal bands across a `GSBandPool`. The
title's flaming logo is full-screen textured/blended passes at the game's native GS
resolution, so per-frame cost scales with pixel count and pass count.

**Evidence for.** Software rasterisation of several full-screen passes at 640×448
(~287k px/frame) through swizzled-texture fetches is plausibly 10–60 ms/frame on a
phone core — a real contributor to a several-hundred-ms frame, and the known ceiling
from the 0.5 era ("CPU rasteriser at scale 1 is the ceiling for now").

**Evidence against (as the main cause).** The pool is not single-threaded on Android:
`GSBandPool::ConfiguredThreads()` = `clamp(hardware_concurrency − 4, 1, 8)` = **4
workers** on this 8-core phone, and the calling GS thread consumes bands too. The SIMD
fast paths are compiled through the bundled **sse2neon** shim (`android/simd/`), are on
by default (`PS2X_GS_FAST` unset), and the rasteriser is desktop-proven in this same
lineage. Nothing in the band pool is Windows-gated. Even a pessimistic per-pixel cost
lands an order of magnitude below 1 s/frame for this scene.

**Audit finding inside this suspect — the scale knob is a no-op here.**
`PS2X_GS_SCALE` is read only by the GPU backend (`gs_gpu_backend.cpp`) and the PCSX2
backend (`gs_pcsx2_backend.cpp`). The string "scale" appears **zero times** in
`gs_cpu_backend.cpp` and `gs_cpu_raster.cpp`. On Android's CPU backend the game has
always rendered at native GS resolution (God Hand NTSC: 640×448 frame, 640×224
fields) whether settings said 3, 1 or 0.75 — which retroactively explains why the
owner's scale changes never moved the frame rate. The final upscale to 2400×1080 is
one GPU texture draw in raylib (`DrawTexturePro`) and is not the bottleneck.

**Convict/clear:** `PS2X_GS_WAIT_STATS=1` (exists) prints every 2 s
`[gs-wait] … drains=N X ms` — how long producers waited for the GS worker. If drains
eat most of each second, the GS is the wall; if they are small, it is not. Needs the
env exposed to the app (one line in the glue/settings plumbing).

## Suspect 2 — VSync in windowed mode

**Mechanism.** With `PS2X_VSYNC` non-zero the runtime sets raylib's
`FLAG_VSYNC_HINT` (swap interval 1) instead of the software 60 FPS limiter
(`SetTargetFPS(0)`), and the present loop additionally waits for the EE's emulated
vsync tick (`presentSyncEnabled`, 250 µs naps, 50 ms cap).

**Evidence for.** A broken swap-interval path on a 120 Hz HyperOS panel could in
principle quantise or stall presents; the present-sync wait couples the window to the
EE's cadence.

**Evidence against.** The scarier mechanism — `PS2X_VSYNC_LOCK`, which phase-locks the
emulated vblank to the host display — is **inert on Android**: `ps2xHostVsync()` in
`Kernel/host_vsync.cpp` is implemented only under `#if defined(_WIN32)` (D3DKMT) and
returns `false` everywhere else, so `lockToHostVsync()` returns the target unchanged.
Swap interval 1 can cost at most one refresh period (8.3 ms @120 Hz, 16.7 ms @60 Hz)
per present. The present-sync wait is capped at 50 ms and only *waits for work*, it
cannot manufacture 1 s frames. Bounds are all two orders of magnitude too small.

**Convict/clear:** the owner can flip VSync off in the existing launcher settings
(it maps to `PS2X_VSYNC=0`, the software limiter). Zero-build A/B; expect no change.

## Suspect 3 — IOP interpreter serialisation

**Mechanism.** The IOP (33–37 MHz MIPS, plain interpreter, `runtime/ps2xIOP`) runs on
its own worker thread in batches of 8192 EE cycles (~28 µs of EE time). The EE may run
ahead of the IOP by at most `kMaxAsyncLagEeCycles` = 512 batches ≈ **14.2 ms of EE
time**, and *every* IOP interaction (SIF RPC, SIF DMA, IOP memory, CD status, pad via
SIO2) **joins**: the EE waits for the running batch. So the whole machine's speed is
capped by the interpreted IOP's throughput whenever the guest leans on it — and at the
title screen the guest leans constantly: pad polls, ADX status/refills, memory-card
RPC (the McPort module the 0.5 log shows binding), CD checks.

**Evidence for.** This is the only compute path whose slowness gates *everything*
uniformly (picture and sound together, since SPU2/ADX live on the IOP), and it fits
the FMV contrast: FMV playback is mostly EE/IPU work with the IOP just streaming,
whereas the title loop is RPC-dense. The Linux join is also cruder than the Windows
one: on Windows `join()` spins 200 µs then *sleeps* on `WaitOnAddress`; on Linux the
`waitBatchDone` path is compiled out and `join()` is a raw
`while (busy) PS2X_IOP_SPIN_PAUSE();` — and `PS2X_IOP_SPIN_PAUSE()` on non-x86 is
`std::this_thread::yield()`. An EE thread yield-spinning against a worker that itself
yield-spins for up to 2 ms before sleeping is at the mercy of the Android/HyperOS
scheduler's yield latency under load; every RPC round trip pays it. Batches complete
in ~15 µs on desktop by the code's own comment; if phone scheduling stretches the
handshake to even ~0.5 ms per interaction, hundreds of interactions per frame add up
to the observed range.

**Evidence against.** The async design is sound and desktop-proven (the comment cites
a measured 22 → 25 fps *gain* in Urban Reign). The lag cap means a merely *slow* IOP
slows the EE to IOP speed — the IOP only needs to sustain ~37 MIPS, which even a
mediocre interpreter on a 2.9 GHz X3 core should do; nothing in the code degrades the
interpreter specifically on ARM/Android. The yield-spin hurts latency tails, not
throughput, unless the scheduler is pathological.

**Convict/clear:** `PS2X_IOP_THREAD_STATS=1` (exists) prints every 5 s:
`[iop-thread] N batches/s, worker busy X%, EE join wait Y%`. Worker busy ≈100% with
high join wait convicts; low busy clears. The blunt A/B is `PS2X_IOP_THREAD=0` (IOP
inline on the EE thread — removes every handshake). Both need the env reachable from
the app.

## Suspect 4 — Audio backend back-pressure

**Mechanism (hypothesised).** If the SPU2's PCM producer could block on a full ring
whose consumer is the miniaudio/AAudio callback, a misbehaving device would stall the
IOP thread, and via suspect 3's joins, the whole frame loop.

**Evidence against — cleared by inspection.** `PS2AudioBackend::submitPcm`
(`ps2_audio.cpp`) takes `pcmMutex`, copies into the ring, and **drops** overflow
(the design comment describes dropping ~127 ms at once when the 170 ms ring fills);
it never waits for the device. The device callback takes the same mutex only to copy
out one device buffer — bounded, short, no I/O. The rate-control/stretch logic is
arithmetic on counters. There is no condition variable, no blocking write, no
`ma_device` synchronous call on the producer path. The [spu2-silence] lines come from
the IOP's SPU2 core watching its own output go quiet — i.e. the SPU2
...[truncated 8293 chars]