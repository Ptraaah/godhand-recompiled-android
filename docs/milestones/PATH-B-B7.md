# Path B, stage B7 — pathb8: the repair build

Build: **0.25-m24-pathb8**, versionCode **32** — installs over pathb7 in
place, same signer. This stage exists because pathb6 and pathb7 both failed
the owner's on-device tests, on performance and (in his words) on the
widescreen that never arrived. B7 is a repair build under new acceptance
gates: this report is a deliverable, not an afterthought, and it contains
the forensic trace for both failures with the numbers that convict or
clear each layer. (The B6 stage's own report was never written — its record
is code comments plus the B5 report's protocol. That gap is part of what
this stage answers for.)

What pathb8 ships, in one paragraph: the VU stack is restored to
pathb5's **byte-identical** state (the dead-norm AOT tree and its runtime
helpers are out; the differential proof is a `diff -r` against the
preserved pre-B5 tree, below); widescreen returns to **default off** with
the shell toggle kept (it did not hold the performance floor, so it does
not get the default); the present layer is fixed at its actual root (the
Android host screen now follows the content aspect, so a 16:9 frame can
reach the maximal 1920x1080 box on the owner's panel instead of being
capped at 1440x810 by a 4:3 virtual screen) and is instrumented with a
`[present-rect]` log line that prints the drawn rectangle's arithmetic
every session; and the bridge pre-creates its capture ring at open, which
removes the B6-added part of the one-off boot-window queueWait spike.

## Forensic 1 — performance: three sessions, one table

Fight windows from the owner's three runs (per-window `[ee-work]` /
`[ee-prof]` / `[ee-prof:top]` / `[vu1]` lines; µs/call is the hot VU
program's window time divided by its calls; vcyc/run is guest VU cycles
per run, i.e. how much work the game actually asked for):

| build | window | fields/s | 0x0520 µs/call | 0x0358 µs/call | vcyc/run | join_wait |
|---|---|---|---|---|---|---|
| pathb5 | sustained fights (15 windows) | 44.0-52.0 | 17.2-19.6 | 19.8-22.9 | 1632-1980 | 0.76-1.39 s |
| pathb6 | first fights (warmup) | 25.2-29.0 | 28.1-38.5 | 37.5-42.1 | 1473-1705 | 1.48-1.53 s |
| pathb6 | settled fights | 29.6-37.9 | 22.5-23.8 | 20.3-23.8 | 1592-1898 | 1.41-2.17 s |
| pathb7 | opening fights | 44.1-45.1 | 22.8-23.3 | 21.0-22.8 | 1464-1745 | 1.18-1.23 s |
| pathb7 | collapse | 13.8-28.8 | 24.3-50.9 | 25.0-48.0 | 1590-1638 | 2.09-2.34 s |
| pathb7 | partial recovery | 31.4-36.0 | 21.0-21.1 | 29.1-29.5 | 1638-1733 | 1.36-1.78 s |

Three separable findings:

**1. The dead-norm tree is slower on the device, at constant guest
work.** vcyc/run is the same across builds (~1.5-1.9k), so the game asks
for the same VU work everywhere; what changed is the host cost of doing
it. pathb5's settled per-call cost (17.2-19.6 µs) rose ~20-25% in every
settled b6/b7 window (21.0-23.8 µs). Mechanism: the elimination's savings
were real only inside a block — `runFast` re-materializes the supposedly
dead normalized copies at **every block boundary** (`fixupNorm` over the
dirty set), because the persistent shadow must be whole whenever control
can leave the compiled image. The hot programs are block-dense (tens of
thousands of calls per window, each call many block dispatches), so the
work was moved and concentrated, not removed, and the dirty-set
bookkeeping rides on every raw write. The B5 differential bench measured
the change as a tie on the x86 host — single images run back-to-back in
hot caches, with none of the boundary pressure at device scale and an
instruction cache an order of magnitude larger; B5's report explicitly
made no host speed claim and named the device the arbiter. The device
has now arbitrated, twice. **-O3's share:** pathb6 (dead-norm + -O3)
was slow from its first fight window; B6 reverted -O3 (the CMake comment
records its own bench A/B for that step) and pathb7's opening windows
recovered to 44-45 — consistent with -O3 as an additional code-size /
icache cost in b6, and dead-norm as the persisting cost in b7.

**2. pathb7's mid-run collapse is a machine-level slowdown, not
content and not one code path.** Across windows 6→9 the run went
44.1 → 28.8 → 20.8 → 13.8 fields/s and partially recovered (21.3 →
31.4 → 36.0). In the worst window the guest asked for the *least* work
of any fight window (0x0520: 25,359 calls, the minimum; GIF traffic
145.6 MB vs 265 MB in faster windows) at unchanged vcyc/run (1627),
yet per-call cost doubled (50.9 µs) and EE native time rose to
4,393 ms of a ~4,900 ms guest window. EE code, VU code and joins all
slowed together while guest work stood still or fell — the signature of
SoC frequency throttling, not of any single mechanism. This is an
inference, stated as one: the log carries no temperature or frequency
telemetry, so it cannot be proven from here. The plausible accelerant
is finding 1 — the same guest work at ~20% more host energy per cycle,
sustained through FMV, menus and fights, reaches the thermal envelope
sooner; pathb5's cheaper stack ran 27 windows without collapsing. The
restore removes the extra work; a long run on a cool phone (protocol
below) is the arbiter.

**3. The queueWait spike is a boot-window one-off in all three builds,
and it is not the fight problem.** The printed max is a
session-cumulative counter: 556.5 ms (pathb5), 1720.5 ms (pathb6),
1384.8 ms (pathb7) — already at its final value inside the **first**
`[ee-work]` window of each log and never growing afterwards. One chunk
of the GS coupling queue waited that long, once, during boot/FMV,
before any gameplay; in fight windows the same queue's *average* wait
is 0.5-2.5 ms while the binding constraints are `[vu1]` join_wait
(0.76-2.34 s/window) and native EE time. The spike's growth in b6/b7
coincides with B6 moving the frame composition into the bridge: the
capture ring's render targets, download textures and the
stretch/convert pipeline were all created lazily at the first vsync,
on the GS thread, in exactly that window. **Fix shipped:** the bridge
now pre-creates the ring and compiles the pipeline at open
(`WarmUpCaptures` in `path-b/compat/BridgeVK.cpp`: ring targets +
downloads at the canvas size, one dummy stretch + copy submitted and
completed before the first vsync; nothing warmed up is ever served —
no slot is marked pending or ready). Kill switch `PS2X_GS_WARMUP=0`.
The residual pathb5-level component is PCSX2's own first-use shader
compilation, present in the approved build as well; it is not chased
here. The marker that arbitrates the fix is window 1's queueWait max
in the next owner log.

**What restores the floor, and the proof.** Gate: ≥44-52 fields/s in
fights at 4:3, wide toggle off (the pathb5 baseline). pathb8 meets it
by construction — its VU half *is* pathb5's:

- `generated/vu1/` regenerated with the translator's existing kill
  switch (`VU1_RECOMP_NO_DEAD_NORM=1`): `diff -r` against the preserved
  pre-B5 tree (`b5-backup/vu1-gen-orig`) — **identical, file for file**.
  Provenance check the other way: the same translator and captures
  without the switch reproduce the current (pathb7) tree identically,
  so the translator, the captures and the block-pair default are all
  the ones that produced both trees.
- `ps2_vu1_impl.inl` restored from the pre-B5 backup: its delta against
  that backup was exactly B5's mechanism and nothing else (4 hunks,
  37 lines, additions only — the `normDirty` field,
  `writeVfRaw`/`writeVfRawFull`/`fixupNorm`, and the two `runFast`
  call sites).
- `godhand_vu1_code` compiles at the project-wide -O2, as in pathb5
  (the -O3 block stayed reverted from B6; re-enabling it is the CMake
  block quoted in `src/CMakeLists.txt`).
- The dead-norm experiment does not ship. It remains reachable at
  translation time (regenerate without the switch and restore the impl
  from git history), behind its proof requirement as before.

Remaining deltas from pathb5 in the wide-off path, each accounted for:
the B6 bridge composition (GPU-side stretch of the same frame; at 4:3
the canvas equals the source size, a 1:1 composition — zero CPU cost,
GPU cost inside the gsIdle headroom of 56-88% the logs show), the
open-time warm-up (boot window only), the `[present-rect]` /
`[host]` log lines (one line per change), and the shell toggle
plumbing (inert when off). On that basis the 44-52 floor is expected
to reappear; this report does not claim it as measured — the owner
run below arbitrates, and if the floor does not reappear, the deltas
above are the complete suspect list.

## Forensic 2 — widescreen: four links, two broken, one mis-measured

**Link 1 — the game-side patch widens the frustum. Sound, proven
statically.** The pnach group writes 0x3F9F49F1 over the stock
0x3F6EEEEF at EE 0x586B88 every vblank (the documented SLUS-21503
pair; the port's pnach engine applies place-2 lines at load and at
every vblank — the owner's logs count the writes). In the recompiled
game code, `Trans_Clipmatrix_set` (guest 0x1538f0) executes
`lwc1 $f22, 0x6B88($v0)` with $v0 = 0x580000 — loading the float at
0x586B88 — and immediately `div.s $f20, $f20, $f22`: the word is the
**aspect divisor of the clip-matrix setup**, read fresh every time
the setup runs, then multiplied through the remaining matrix terms
(generated/Trans_Clipmatrix_set_0x1538f0.cpp, lines 171/198/348/390).
The writers are the game's own screen setup (`func_0030B870` stores
the stock value there; `func_0015C928` also reads it). So with the
switch on, the game really does build its projection from the 16:9
constant — a wider frustum, rendered anamorphically into the same
framebuffer, exactly as on PCSX2 with the community patch. What this
VM cannot do is render the game (no game data here, by design), so
the empirical closer is the A/B in the protocol: at a fixed scene,
wide-on must show *more world at the edges* at the same vertical
framing — the same world stretched wider would convict this link
despite the static proof.

**Link 2 — the GS composition. Sound, and it is not a crop.**
`CaptureFrame` (path-b/compat/BridgeVK.cpp) stretch-draws the
**full** merged texture (source rect (0,0,1,1)) into the canvas —
1280x720 at scale 2 with 16:9 current. That is an anamorphic
composition, the mirror image of PCSX2's own backbuffer math: the
horizontally squeezed wide view (a world circle occupies 0.75x as
many horizontal as vertical pixels in the 1280x960 source) is mapped
onto a 16:9 raster (x scale 1.0, y scale 0.75), which restores round
proportions (0.75 x (1.0/0.75) = 1.0) with the whole frame preserved.
Earlier descriptions of this stage as a "crop" were wrong; the code
draws the full frame, and the owner's screenshots show the full
frame (complete HUD, nothing amputated). The honest cost: the served
raster carries 720 lines where the source has 960 — a 0.75 vertical
downsample versus composing at full height as PCSX2's window path
does. A future stage can compose at 1707x960 instead; it changes
sharpness, not proportions, and it is not smuggled into this repair
build.

**Link 3 — the host present. This is where the owner's verdict was
earned.** The shell draws the served texture into a fit rect computed
from raylib's *virtual screen*, which was fixed at 640x480 (4:3) —
and raylib maps that virtual screen into the real display preserving
the virtual aspect. The full chain for pathb7, wide on, from raylib's
own source (deps-src/raylib rcore.c `SetupFramebuffer` /
rcore_android.c) and the owner's boot log:

- `SetupFramebuffer(2400x1080 display, 640x480 screen)`: display
  ratio 2.222 > screen ratio 1.333 → render 1067x480, renderOffset
  (427, 0) — the log's own "Viewport offsets: 427, 0".
- `ANativeWindow_setBuffersGeometry(1067, 480)`: the EGL buffer is
  1067x480; Android's compositor scales it to the 2400x1080 panel
  (x2.249 / x2.25 — uniform within 0.05%).
- GL viewport inside the buffer: (213, 0, 640, 480).
- The 16:9 fit rect in virtual space was correct — (0, 60, 640, 360)
  — but mapped through that viewport and the compositor it lands at
  **1440x810** on the panel: true 16:9 proportions, yet only 60% of
  the panel's width and 75% of its height, black on all four sides.
  A 16:9 picture on a 20:9 phone should fill the height
  (1920x1080). Surrounded by black and smaller than the 4:3 mode's
  box in one dimension, it reads — correctly, from the couch — as
  "not 16:9". In pathb6 it was worse: the composition existed only
  in a comment (the B6 note in `EnsureOpen` admits the B5 harvest
  served the raw texture; the shipped b6 shell texture was the raw
  1280x960), so b6 squeezed a 4:3 raster by the 16:9 fit — the
  owner's b6 verdict was right on every count.
- The "~1.46 aspect" that circulated from the b7 screenshots is a
  measurement artifact: it was taken from the chat pipeline's
  2048x1152 rescale (anisotropic). The original capture files are
  2048x921 — an isotropic x0.8533 of the panel — and pixel-measuring
  them gives the game box at x 408..1636, y ~115..806, i.e.
  1440x810 at panel scale, aspect **1.776**. The proportions were
  right in b7; the size was wrong. Record corrected.

**pathb8's fix:** on Android the host screen follows the content
aspect — 640x360 with widescreen on, the unchanged 640x480 with it
off (`hostWindowSize` in ps2_runtime.cpp, logged as `[host]`). The
wide-on chain becomes: `SetupFramebuffer(2400x1080, 640x360)` →
render 800x360, offsets (160, 0), buffers 800x360, GL viewport
(80, 0, 640, 360); the fit rect is now the whole screen (host aspect
== screen aspect), so the surface box is (80..720, 0..360) of the
buffer and the compositor (x3.0 / x3.0) lands **1920x1080** on the
panel — aspect 1.7778, full height, the maximal 16:9 box. Wide off,
every number is the pre-B7 one (box 1440x1080, aspect 4:3). Desktop
is untouched (resizable windows; the change is `#if __ANDROID__`).

**Link 4 — evidence, from now on.** Guessing from screenshots is
how two builds shipped on claims. The present block now logs, on the
first drawn frame and on any change:

```
[present-rect] tex 1280x720 screen 640x360 aspect 1.7778 dst (0.0,0.0 640.0x360.0) gl-viewport (80,0 640x360) surface-box (80.0,0.0 640.0x360.0) box-aspect 1.7778
```

(texture size, virtual screen, the aspect used, the destination quad
in screen coordinates, raylib's actual GL viewport in the real
surface, and the derived on-surface box with its aspect.) Together
with the `[host]` line and the bridge's canvas line, the next owner
log either proves the drawn rectangle or convicts the exact layer
that broke it.

**Default (gate 3c):** wide ships **default OFF** — gate 2's floor is
not established with it on (pathb7's wide-on fights are the 13.8-36.0
windows above), so the toggle stays as the owner's opt-in
(Launcher → Widescreen 16:9; the external ini `PS2X_WIDESCREEN`
line remains the kill switch in both directions). It returns to
default-on only when a wide-on run holds the 44-52 floor.

## Files changed

- `godhand-recomp/generated/vu1/*` — regenerated with
  `VU1_RECOMP_NO_DEAD_NORM=1`; byte-identical to the pre-B5 tree.
- `godhand-recomp/runtime/ps2xRuntime/src/lib/vu/ps2_vu1_impl.inl` —
  restored from `b5-backup/ps2_vu1_impl.inl` (pre-B5 bytes).
- `godhand-recomp/runtime/ps2xRuntime/src/lib/ps2_runtime.cpp` —
  Android host screen follows `PS2X_WIDESCREEN` (640x360 / 640x480,
  `[host]` line); `[present-rect]` evidence log in the present block.
- `path-b/compat/BridgeVK.cpp` — `CaptureCanvasSize` helper shared by
  capture and warm-up (no behavior change); `WarmUpCaptures` at open
  (ring pre-creation + dummy stretch/copy; `PS2X_GS_WARMUP=0` kill
  switch); the canvas line now also prints from warm-up creation.
- `godhand-recomp/android/glue/godhand_android_main.cpp` — external-ini
  forwarding for `PS2X_GS_WARMUP` (same pattern as the other path B
  seeds; without it the warm-up kill switch would not be reachable
  from godhand.ini on Android).
- `godhand-recomp/android/shell/src/dev/ptraaa/godhand/GameActivity.java`,
  `IsoHolder.java`, `LauncherActivity.java` — widescreen default off
  (toggle unchanged; a stored owner preference still wins).
- `godhand-recomp/android/shell/AndroidManifest.xml` — versionCode
  32, versionName 0.25-m24-pathb8.
- `godhand-recomp/src/CMakeLists.txt`,
  `godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp`
  — comment references updated to this report (the B6 report they
  pointed at was never written).
- Not changed: the PCSX2 bridge API, the pnach widescreen group, the
  host aspect setting, ARUS, the audio path, MTVU
  (`PS2X_VU1_ASYNC`), the native renderer (escape hatch intact:
  `PS2X_GS_RENDERER=native`), every mainline default.

## Verification done here

- VU restore: `diff -r` regenerated tree vs `b5-backup/vu1-gen-orig`
  (identical) and `diff` restored impl vs backup (identical);
  reverse regeneration reproduces the pathb7 tree identically.
  The shipped libgodhand.so contains zero `fixupNorm` / `writeVfRaw`
  strings (the dead-norm helpers do not exist in the binary).
- Bridge rebuild (path-b/build-b1): libpcsx2gs.so rebuilt with the
  warm-up; the binary carries the "capture warm-up" line.
- Variant build (build/android-m2a, GODHAND_PATHB_DEFAULT_PCSX2=ON):
  completed; the lib carries 8 "path B variant" seed lines, the
  `[present-rect]` format, both `[host]` screen lines and the
  `PS2X_GS_WARMUP` seed. Two compile failures on the way are worth
  recording: the first `[present-rect]` draft used `glGetIntegerv` /
  `GL_VIEWPORT` in ps2_runtime.cpp, a TU where rlgl.h declares no raw
  GL entry points (its glad include is implementation-gated; the
  earlier assumption that a sibling TU's GL calls proved otherwise
  was a misread — those calls live in gs/gpu/gs_gl.cpp). The shipped
  code includes the platform header `<GLES2/gl2.h>` under
  `#if defined(__ANDROID__)`, exactly as gs_gl.cpp does, and queries
  the viewport only on Android.
- Host (linux) compile proof: the touched translation units
  (ps2_runtime.cpp, gs_pcsx2_backend.cpp, ps2_vu1_core.cpp with the
  restored impl, and a restored generated VU image) compile clean
  for the desktop x86-64 target. A full host link was not run: the
  desktop delta of this stage's changes is preprocessor exclusion
  (the GLES2 include and the viewport query are
  `#if defined(__ANDROID__)`; the host-screen helper is not called
  on desktop), and the restored VU code is the pre-B5 code the host
  target has built since pathb5.
- Packaging: `GODHAND_PATHB=1 build-apk.sh`; apksigner verifies v2
  and v3 with the project signer (below); aapt reads versionCode 32
  / versionName 0.25-m24-pathb8; the zip tests clean; the APK's
  lib/arm64-v8a/libgodhand.so and libpcsx2gs.so md5-match the build
  trees byte for byte.
- Process notes, for the record: the B6 stage's mainline-restore
  build was found still running, orphaned, when this stage started
  (its inputs had been overtaken by this stage's edits, so its
  product would have been a mix); it was terminated and the restore
  is redone as part of this stage. A VM restart mid-build killed the
  first variant build at step ~4456/13136; all sources, the bridge
  library and this report survived intact (verified by diff and
  grep), and the build was resumed, not reconfigured. The single
  translation unit `generated/register_functions.cpp` (12.9 MB,
  -O0) is the build's long pole on this VM (~45 minutes under the
  cell's CPU throttling); everything else in the 13,136-step plan
  took about an hour across the attempts.

## Build tooling (ccache + mold) and the mainline restore

Adopted at this stage's restore configure — the first fresh
configure after the tools landed (ccache 4.14.2 and mold 3.0.0 in
~/workspace/tools/bin); the variant build already in flight was not
reconfigured for it. The restore configure
(build/android-m2a, GODHAND_PATHB_DEFAULT_PCSX2=OFF) sets
CMAKE_C/CXX_COMPILER_LAUNCHER to the ccache binary
(CCACHE_DIR=~/workspace/tools/ccache-cache, 15 GB, compression on;
the build runs with CCACHE_SLOPPINESS=pch_defines,time_macros) and
the shared/exe linker flags to -fuse-ld=<mold>. Measured on the
restore's first pass, stated plainly: the cache started cold (the
variant build ran without ccache), so the pass is all misses by
construction — early in the pass the stats read 1,105 cacheable
calls, 0 hits, 0 uncacheable, ~0.1 GB populated. The PCH-heavy
compilations in this tree ARE cacheable under the sloppiness
setting (no pass-through fallback). The restore then ran in two
passes (the first was killed by a runtime restart at ~35 minutes;
ninja resumed it) and completed rc=0: the restored mainline
libgodhand.so (99,711,576 bytes) carries **zero** "path B variant"
strings — the acceptance check passes and the tree is back in true
mainline state.
The final ccache numbers need stating without varnish: across
both passes the stats read 13,201 cacheable calls, **0 hits**,
13,201 misses, 0.7 GB stored. The cache populated but never
matched across the pass boundary — the restart between the passes
came with a cmake regeneration of the build rules, which
evidently invalidated every key (the regenerated PCH / rule set
is the plausible carrier; the exact field was not isolated).
That the mechanism itself works in this tree is proven by a
controlled test after the restore: touching one small generated
TU's source and rebuilding it twice through the restored rules
produced two immediate ccache hits (the stats moved 0 → 2).
So the honest summary is narrower than the expectation: ccache
pays inside an uninterrupted build sequence in a stable tree
state; across reconfigures and VM restarts in this tree it has,
so far, paid nothing, and a future stage that wants the win
should keep the tree configured and the cache warm rather than
reconfiguring between builds. Mold linked the restored library
(step 11321/11322, no incidents, product verified above); no
isolated GNU-ld-vs-mold timing pair for the same link exists in
this stage's logs, so no numeric link claim is made.

## APK

godhand-0.25-m24-pathb8.apk — 136,696,533 bytes, md5
09946f38b7d863d8b5c36e89419d2d0a. Packaged as
godhand-0.25-m24-pathb8.zip (34,843,455 bytes, md5
44fc7f30c1c903ac829ec234f766a36e; the zip contains just the APK).
Inside: lib/arm64-v8a/libgodhand.so 98,497,432 bytes (md5
0b9dffd9efa26c900d266d5e66f57d1c, also kept beside the zip as
libgodhand-0.25-m24-pathb8.so), lib/arm64-v8a/libpcsx2gs.so
28,106,992 bytes (md5 e86c76527b7e796828621488f1e05de9),
lib/arm64-v8a/libshaderc_shared.so (unchanged). Signature: APK
Signature Scheme v2 and v3 verified, signer certificate SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e —
the same signer as every build since 0.19-m18, so pathb8 installs
over pathb7 in place.

## Owner test protocol

Install over pathb7 (same signer, in-place update). Two runs.

**Run 1 — the floor (wide off).** In the launcher, make sure
"Widescreen 16:9" is OFF (pathb8 defaults it off, but a preference
stored by pathb7 wins — the boot log's settings line must read
`wide=off`). Play the same route as the pathb5 run: menus, village,
a real fight, and keep playing — at least 15 windows' worth (the
pathb5 run was 27) — on a phone that starts cool. Expected markers:

- `[host] screen 640x480 (4:3 host screen)`
- `[present-rect] ... screen 640x480 aspect 1.3333 ... box-aspect 1.3333`
- First-window `queueWait ... max` — the warm-up's report card:
  materially below pathb7's 1384.8 ms is the fix landing; the
  pathb5-level residue (~0.5 s) is PCSX2's own shader compilation.
- Fight `[ee-work]` windows at 44-52 fields/s and
  `[ee-prof:top]` 0x0520 back at ~17-20 µs/call (divide its ms by
  its calls) — the restored stack's signature.
- If the rate still collapses late in a long run on the restored
  stack, note whether the phone is hot to the touch: that separates
  the thermal hypothesis from the code, and this report's inference
  stands or falls by it.

**Run 2 — widescreen (toggle on).** Flip "Widescreen 16:9" on in the
launcher, reboot the game, same route. Expected markers:

- `settings: ... wide=on`, glue seed `PS2X_WIDESCREEN=1 (shell setting)`
- `[widescreen] PS2X_WIDESCREEN=1: aspect patch group on, host aspect 16:9`
- `[pcsx2-gs] capture canvas 1280x720 (source 1280x960, aspect 16:9)`
  and `[pcsx2-gs] capture warm-up: ...`
- `[host] screen 640x360 (16:9 host screen, PS2X_WIDESCREEN=1)`
- `[present-rect] tex 1280x720 screen 640x360 aspect 1.7778 dst (0.0,0.0 640.0x360.0) gl-viewport (80,0 640x360) surface-box (80.0,0.0 640.0x360.0) box-aspect 1.7778`
  — the drawn rectangle, in numbers, before anyone judges a
  screenshot. On the panel the picture should now fill the height
  (1920x1080 box, bars only at the sides).
- The frustum A/B: at one fixed spot (village gate, same save),
  screenshot wide off and wide on. On must show more world at the
  left/right edges at the same vertical framing. Same world,
  stretched = link 1 convicted despite its static proof; report it
  as such.
- Fight fields/s with wide on, against the two baselines: 44-52
  (pathb5, 4:3) and 36.0 (pathb7's best wide window). Widescreen
  costs real guest work (a wider frustum is more world to draw);
  how much is now measured on the restored stack, not on the
  dead-norm one.

Kill switches, unchanged: `PS2X_WIDESCREEN=0` in the external
godhand.ini forces 4:3 regardless of the toggle; `PS2X_GS_WARMUP=0`
disables the bridge warm-up; `PS2X_VU1_ASYNC=0` restores pre-B4 VU
behavior; `PS2X_GS_RENDERER=native` is the built-in-renderer escape
hatch.
