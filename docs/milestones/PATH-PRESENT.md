# Present-path stage — the per-field harvest tax

Question handed down from M30-LOG-ANALYSIS.md: the bridge present path
parks 667.5-908.0 ms per ~5 s window in EVERY window of the m30 log,
light scenes included (~2.3-3.2 ms per present, ~250-300 presents per
window). What is it doing for that long when the scene is light and
the GS worker is 81-85% idle?

Short answer: it is not waiting on the GPU, not waiting on a queue
drain, and not an accounting artifact. It is paying, once per field,
for **three full-frame CPU copies inside the bridge's frame harvest**
(`pcsx2gs_grab_frame2`), serialized behind the threaded backend's
Present drain. The cost is per-field and frame-size-driven
(2.3-4.9 MB/frame at scale 2), which is exactly why it does not shrink
in light scenes. The lever below removes two of the three copies and
moves the third off the present path.

## 1. The present path, mapped

Three threads are involved. Anchors are to the current sources.

**Host thread** (`runtime/ps2xRuntime/src/lib/ps2_runtime.cpp`, the
frame loop that prints [frame-split]):

- The `sync` bucket (fsSyncMs, ~line 3620) is a spin-sleep loop: wait
  until `eeScheduler().currentVSyncTick()` advances past the last
  presented field (250 µs sleeps, 50 ms cap). It is "wait for the
  guest to produce the next field", nothing else.
- The `gs-fetch` bucket (fsFetchT0, ~line 3644) spans
  `UploadFrame(...)`, which once per new vsync tick calls
  `GS::latchHostPresentationFrame()` (ps2_runtime.cpp:838) and then
  uploads the harvested pixels to the host GL texture.
- `swap` is `EndDrawing()` (eglSwapBuffers, display-vsync aligned);
  `draw` is the raylib overlay pass. Both are small in the log
  (~0.3 ms and ~1.0-1.5 ms).

**The latch** (`runtime/ps2xRuntime/src/lib/gs/gs_frontend.cpp:759`,
`GS::latchHostPresentationFrame`):

```cpp
m_backend->Sync(GSSyncReason::Presentation);
frame = m_backend->Present(request);
```

Both calls go through `GSThreadedBackend` (the frontend's backend is
always the threaded wrapper). `Sync` and `Present` are
`RunOnWorker(fn, kind)` (gs_threaded_backend.cpp:1242, 1255):
enqueue an `Op::Call` and then `Drain(kind)` (:1042, :833). `Drain`
publishes all pending producer bytes, snapshots `m_published`, and
blocks the caller on `m_doneCv` until the GS worker's `m_completed`
reaches the target — i.e. **a Present drain waits for every stream
chunk queued ahead of the Call, plus the Call's own execution**. The
DrainTimer records the whole blocked interval under the kind; those
records are the `[ee-work] parked: sync/present` buckets
(EeScheduler.cpp:468, fed by the gscoupling atomics in
gs_threaded_backend.cpp:26-46).

**GS worker thread**: executes the Call —
`GSPcsx2Backend::Present()` (gs_pcsx2_backend.cpp:402). Surfaceless
build: it calls the bridge export `pcsx2gs_grab_frame2` twice (size
query, then copy into `PresentationFrame.pixels`), with duplicate
suppression via the bridge frame counter. Its own comment states the
design intent: "The grab only polls a fence and copies, so this never
waits on a readback; the frame served lags the GS by about one
field." `NoteHarvest` (:456) times exactly the grab calls and prints
them as `[pathb] harvest: ... readback_wait=...`.

**Bridge thread** (path-b/compat/BridgeVK.cpp, inside
libpcsx2gs.so): at every vsync, `VsyncDirect` runs `GSvsync` (merge)
and then `CaptureFrame()` (:165), which records a snapshot of the
merged texture into the next slot of a 3-slot ring of Vulkan download
textures and submits without waiting (:92-109). Completion is
observed by `PollCapturesLocked()` (:130): for each slot whose fence
has completed, `Flush()` + `Map()` + a row-by-row memcpy from the
mapped staging buffer into `slot.pixels`. `CaptureFrame` itself polls
first (on the bridge thread); a grab polls again from the worker
thread. The serve path is `pcsx2gs_grab_frame2` (:975): under
`g_captureMutex`, the size query calls `RefreshServeLocked()` (:278),
which polls and then **copies the freshest ready slot's pixels into a
serve-cache vector** (`g_grab_pixels = best->pixels`); the copy call
then **memcpys the serve cache into the caller's buffer**.

So one harvested frame crosses three full-frame CPU copies before the
host even uploads it:

1. staging (mapped download texture) → `slot.pixels`
   (PollCapturesLocked — paid by whichever thread polls first after
   the fence completes: bridge thread at CaptureFrame, or the grab);
2. `slot.pixels` → `g_grab_pixels` (RefreshServeLocked — always
   inside the grab's size query);
3. `g_grab_pixels` → `PresentationFrame.pixels` (grab_frame2 copy
   call — always inside the grab).

Plus a fourth transfer that is NOT in the parked figure: the host's
GL texture upload of the same pixels inside UploadFrame.

## 2. Reconciling the three instruments

The m30 numbers look contradictory until each timer's thread and
scope are pinned down. They are not; they nest.

- **[frame-split] avg == the field period.** Light window: avg
  16.7 ms at 59.9 fields/s; heaviest window: 23.8 ms at 41.9
  fields/s. 1000/59.9 = 16.7, 1000/41.9 = 23.9. The line decomposes
  the same wall time [ee-work] counts fields over — it is a second
  view of the field interval, not additional time. Anyone adding
  frame-split's buckets to [ee-work]'s parks double-counts.
- **frame-split `sync` (56-70%) is the host waiting for the guest.**
  From the host's chair, everything the EE thread does — guest
  execution, VU joins, the vblank barrier — is inside this bucket.
  It is not a present-path cost.
- **parked present ⊂ frame-split `gs-fetch`.** gs-fetch runs
  4.25-6.65 ms/present in the log; the latch inside it accounts for
  parked sync (~0.4 ms light) + parked present (~2.3-3.2 ms), and
  the remainder (~1-2.5 ms) is the GL texture upload of the
  harvested frame. The two instruments measure the same latch wait
  from opposite ends and agree.
- **parked present ≈ readback_wait + a small queue residue.**
  Per-call, from the log's 23 windows: parked present per drain
  2.34-3.24 ms; `[pathb] harvest` readback_wait per Present() call
  1.95-2.69 ms (grabbed 226-285, suppressed 3-47 per window;
  readback_wait 533-833 ms/window). The difference, ~0.3-0.6 ms, is
  the genuine drain residue — stream chunks queued ahead of the
  Call still executing. Corroboration: [ee-work] `queueWait avg`
  is 0.5-1.0 ms and `callExec` averages ~1.1-2.2 ms per Call (Sync
  and Present mixed). The drain semantics are therefore NOT the
  story: at most ~20% of the park is queueing; ~80% is the grab
  executing on the worker while the host waits for it.
- **Ruled out**: the bridge item queue (`[pathb] pipeline: park=`
  is 0.0 ms in 16 of 20 windows; the four nonzero parks,
  32.5-384.5 ms, are early/boot windows); fence waits inside the
  grab (captures are fire-and-forget, the served frame is already
  complete by construction — a not-yet-complete capture just means
  the previous frame is served, which the suppressed-duplicate
  counter shows happening only 3-47 times per window); vsync
  alignment (that lives in frame-split's `swap`, ~1.0-1.5 ms, and
  never enters the Drain timer); latch cadence (one latch per
  field, presents track fields ~1:1).

**Verdict: real blocked time, mislabelled bucket.** Two independent
timers (the Drain timer on the host thread, NoteHarvest inside
Present on the worker) agree to within the queue residue, so the
park is real. One labelling caveat worth recording: the thread that
parks on Present drains is the HOST thread (the latch runs inside
UploadFrame), not the EE thread — yet [ee-prof] folds the same
drain totals into its `parked` sum and subtracts them from guestMs
to derive the native residual (EeScheduler.cpp:513-515). In m30 the
residual stays large and positive so nothing breaks, but the
[ee-prof] native figure is mildly understated by exactly the
harvest cost. Not a field-rate factor; a footnote for whoever reads
[ee-prof] next.

Why ~2.5 ms for "poll + copies": the frame at PS2X_GS_SCALE=2 is
the merged texture, 2.3-4.9 MB depending on display mode (the
session's [present-rect] lines show 640×896 up to 1280×960).
Copies 2 and 3 are ~10 MB of DRAM traffic through one thread, and
copy 1 reads from a mapped staging buffer; at mobile memcpy rates
the measured 1.95-2.69 ms per grab is what three copies of this
size cost. The B3 pipeline removed the ~6.6 ms synchronous GPU
download from this path (see the NoteHarvest comment); what remains
is the CPU-side echo of the same frame, three times over.

## 3. The lever

**PS2X_GS_HARVEST_FASTPATH** — bridge + backend pair, default ON.
`PS2X_GS_HARVEST_FASTPATH=0` restores today's serve verbatim (serve
cache copy, grab-side materialization, same counters).

Two changes, both inside the harvest:

1. **Serve from the slot, not from a copy of the slot.** In
   `pcsx2gs_grab_frame2`, the copy call memcpys directly from the
   freshest ready slot's `pixels` under `g_captureMutex`. The serve
   cache keeps only metadata (width, height, vsync counter) — the
   `g_grab_pixels = best->pixels` vector copy in
   `RefreshServeLocked` goes away. The size-query/copy pair stays
   consistent the same way it does today: the query pins the chosen
   slot's vsync in the metadata, the copy call serves that slot,
   and the backend's existing `m_lastFrameCounter` duplicate check
   remains the second guard. (The mutex is already held across the
   whole serve, and the remaining memcpy already ran under it; the
   critical section does not grow — total lock traffic shrinks by
   one full-frame copy.)
2. **Materialize captures on the bridge thread only.** Split
   `PollCapturesLocked` into a fence check (cheap, any thread) and
   materialization (the staging→slot copy). `CaptureFrame` — which
   already polls every vsync on the bridge thread — materializes;
   the grab path only checks readiness. If no slot is materialized
   yet (startup, resize), the grab serves nothing / the previous
   frame exactly as today's not-ready case does, instead of paying
   copy 1 itself.

Untouched: the Sync(Presentation) drain before Present (its
~0.4 ms light / up-to-~2.3 ms worst-window cost is the parked-sync
bucket — real, second-order, and the ordering guarantee other
backends share; not this stage's target), the threaded backend's
Drain semantics, the bridge pipeline and its depth bound, capture
ring size, and every VU/GIF path.

**Predicted effect** (per present, light windows):

- readback_wait: 1.95-2.69 ms → ~0.7-1.2 ms (one copy remains:
  slot → PresentationFrame, from hot DRAM);
- parked present: 667.5-908.0 → ~250-420 ms/window;
- frame-split gs-fetch share: 24-35% → ~15-22% (the GL upload
  remains);
- light fields/s: unchanged (~60, pacing-capped) — the win is
  ~1.5-2 ms/field of host-loop slack, i.e. margin, not rate;
- crowd fields/s: +0-2 at most. Crowd field production is
  guest-bound (VU joins, throughput); a cheaper present cannot
  manufacture guest fields. What it can do is stop pushing swaps
  across display-vsync boundaries, which reads as smoother cadence
  at the same field rate.

Stated plainly so the next stage is aimed right: **this lever does
not close the crowd gap.** After it, the only remaining crowd lever
is per-job VU/GIF throughput (m30: join totals up to 1,230
ms/window, VU busy 57-91%, GIF 338-488 MB/window).

## 4. Correctness — what could break, and what covers it

- **Size/copy pair divergence.** Today the serve cache freezes the
  frame between Present()'s two grab calls. Serving from the slot
  keeps the same guarantee differently: both calls run under
  `g_captureMutex`, the query pins the slot by vsync counter, and
  slots are only rewritten by CaptureFrame after their previous
  capture was consumed or dropped — a pinned ready slot's pixels
  cannot change between the pair. The backend's counter check
  (`counter == m_lastFrameCounter` → suppress) catches any
  residual repeat.
- **Slot reuse while a copy is in flight.** The grab's memcpy holds
  `g_captureMutex`; CaptureFrame takes the same mutex for its
  whole recording. No slot can be recycled mid-copy — the same
  exclusion that protects the serve cache today.
- **Serving a staler frame.** Change 2 means a capture that
  completes just after the bridge thread's per-vsync poll waits
  for the next vsync's poll to materialize — worst case the served
  frame is one field older than today's, for one field. The design
  already serves ~1-field-old frames by construction, and dropped
  captures (ring full) already produce exactly this pattern;
  `dropped_captures` and the suppressed counter make it visible in
  the log if it grows.

## 5. Honest risks

- **Judder (miniature, bounded).** The §4 staleness case can add an
  occasional repeated frame at field boundaries. If the owner's
  eye catches it, the kill switch restores today's path in one
  env var; the log's suppressed/dropped counters arbitrate whether
  it actually grew.
- **Tearing: none expected.** The serve remains whole-frame copies
  under a mutex into a private buffer, then a single texture
  upload and swap — no new partial-frame path is introduced.
- **Audio cadence: untouched, in both directions.** SPU2 is slaved
  to guest timing; this lever changes only how the host thread
  collects finished frames. It will not fix the crowd audio
  choppiness Pablo reported on m30, and it cannot worsen it —
  anyone expecting an audio change from this stage is looking at
  the wrong wall; the audio follows the VU-join/throughput wall.
- **The prediction could be wrong about copy 1's share.** If the
  staging→slot copy (mapped-memory reads) is much slower than the
  two DRAM copies, change 2 carries most of the win and change 1
  little; if the reverse, vice versa. Either way one copy remains
  on the path and the arbitrating lines below measure the sum —
  the stage does not need to know the split in advance.

## 6. Evidence base

- M30-LOG-ANALYSIS.md (this stage's input): parked present
  667.5-908.0 ms in all 23 windows, light included.
- Source trace as cited in §1-§2 (gs_frontend.cpp, 
  gs_threaded_backend.cpp, gs_pcsx2_backend.cpp, ps2_runtime.cpp,
  EeScheduler.cpp in the recomp runtime; compat/BridgeVK.cpp in
  path-b).
- Log arithmetic in §2, all recomputed from the m30 session:
  per-drain and per-grab rates from [ee-work], [pathb] harvest and
  [frame-split] over the same 23 windows; join_wait cross-checked
  against the [vu1] async summary line (1.0-1,230.3 ms, matching
  the summed attribution to the tenth).

No build was made for this report; the report-first gate applies
and the next build is commissioned against §7.

## 7. The lines that arbitrate a future device run

From the m31 (or later) log's last session, in order:

1. `[pathb] harvest` — readback_wait per present (grabbed +
   suppressed): target < ~1.2 ms (m30: 1.95-2.69 ms). This is the
   lever's direct signature.
2. `[ee-work] parked: present` — target < ~450 ms/window in light
   windows (m30: 667.5-908.0 everywhere).
3. `[frame-split]` — gs-fetch share: target < ~22% (m30: 24-35%);
   avg must still equal 1000/fields-per-s (the reconciliation
   check that the instruments still agree).
4. `[ee-work]` heavy fields/s and `[vu1] joins` — expected
   UNCHANGED within noise (crowd is throughput-bound); a large
   jump either way falsifies §3's model and must be explained
   before the lever is credited.
5. `[pathb] pipeline` dropped_captures and harvest suppressed —
   the §5 judder bound: growth beyond m30's 3-47/window range
   means the staleness cost is real, not theoretical.
6. Owner feel, as always, outranks every line above: light scenes
   should feel identical, crowds identical-to-slightly-smoother;
   any new judder he can see is a fail regardless of the numbers.
