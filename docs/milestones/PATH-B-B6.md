# Path B stage B6 — pathb7: true 16:9 end-to-end + a settings toggle, and the fight-speed regression attacked

Build: **0.25-m24-pathb7**, versionCode **31** — installs over pathb6 in place,
same signer. The owner's verdict on pathb6 was rejection on both fronts
(fights *slower*, 29.6–37.9 fields/s against pathb5's 44–52, and a picture
that was still 4:3), and his order for this round was *semuanya* — everything,
no tradeoff. Both mandates were worked as binding:

1. **16:9 now reaches the screen.** The harvest composes a true-aspect frame
   (1280x720 at scale 2), and widescreen is a real launcher settings toggle,
   default on, with the ini line kept as the hard kill switch.
2. **Fight speed:** the two codegen deltas pathb6 shipped were A/B'd on the
   differential bench. One of them (B5's `-O3`) is reverted on the evidence;
   the other (dead-norm elimination) is *kept* on the evidence — it is faster,
   and the report below shows the numbers instead of asserting either way.
   The present path also gets ~20% lighter per field as a side effect of the
   16:9 canvas. Locked 60 in wide mode is **not** claimed: see the honest
   accounting at the end of the Failure 1 section.

## Failure 2 — where 16:9 actually stopped, and the fix

pathb6's core patch was genuinely on (the owner's log has both the glue seed
and `[widescreen] PS2X_WIDESCREEN=1: aspect patch group on, host aspect 16:9`),
and the shell's fit rect was genuinely 16:9. What never existed was a 16:9
*raster*: the bridge's harvest (`path-b/compat/BridgeVK.cpp`, `CaptureFrame`)
served the device's raw merged texture, whose size follows the game's display
output — 640x448 at scale 2 = 1280x896, a 10:7 raster — and the host stretched
that 4:3-proportioned picture into its 16:9 fit. The backend even carried a
comment claiming "the bridge's 16:9 composition fills it without bars"; no
such composition existed anywhere in the code. PCSX2's own present path
composes the merged texture into a window-sized backbuffer through
`GSRenderer::CalculateDrawDstRect` for exactly this reason; in surfaceless
mode there is no backbuffer and nothing inherited the job.

**The fix (bridge, `CaptureFrame`):** the stretch target is now a canvas with
the *current aspect's* proportions, the full merged texture drawn into it:

- canvas width = bridge window width x upscale multiplier (640 x 2 = 1280);
- canvas height = width / target aspect, target aspect from
  `EmuConfig.CurrentAspectRatio` — **1280x720 with 16:9 current**,
  1280x960 with 4:3 current;
- Stretch/Auto aspects (the backend never selects them) keep the pre-B6
  raw-texture serve, untouched;
- the ring slots key on the canvas size exactly as they keyed on the texture
  size before, and a new log line prints the canvas at creation (below).

The canvas aspect must equal the target aspect (rather than letterboxing
inside the old 1280x896) because the host applies its own aspect fit
(`ps2_host_display::aspect()`) to the served raster; bars inside the raster
would be fitted a second time and the picture would come out narrower than
4:3 — the same class of bug as the one being fixed, one layer down.

**End-to-end proof (code path, every hop read in this tree):**

1. Bridge `CaptureFrame()`: with 16:9 current, slots are created at
   1280x720 and the stretch draws the full merged texture into the full
   canvas; `PollCaptures` copies slot.width x slot.height pixels out.
2. `pcsx2gs_grab_frame2` serves `g_grab_width/g_grab_height` = the slot
   size (1280x720).
3. `GSPcsx2Backend::Present()` (non-Windows branch) returns
   `PresentationFrame{width=1280, height=720}` — it passes the grab's size
   through untouched.
4. `GS::latchHostPresentationFrame` -> `ps2_runtime.cpp UploadFrame()`:
   the raylib texture is created/recreated at exactly frame.width x
   frame.height — the boot log's `TEXTURE: [ID n] Texture loaded
   successfully (1280x720 | R8G8B8A8)` line is the on-device receipt.
5. The present loop fits that texture by `ps2_host_display::aspect()`,
   which `main.cpp` sets to 16/9 when the switch is on: on the owner's
   2400x1080 (20:9) screen the picture is 1920x1080 centred, ~10% black
   per side — correct pillarbox, 16:9 proportions, from a raster that is
   itself 16:9 (no non-uniform stretch anywhere in the chain).

The bridge also logs, once per ring slot at creation:
`[pcsx2-gs] capture canvas 1280x720 (source 1280x896, aspect 16:9)` — the
(source is the raw merged texture, there for contrast in the same line).

**The settings toggle (shell):** Launcher -> Settings gains
**"Widescreen 16:9: ON/OFF"** (default ON), stored as the `set_wide`
preference. GameActivity copies it to `IsoHolder.optWidescreen`
("settings loaded: ... wide=on" in the shell log); the glue resolves
`IsoHolder.widescreen()` and the path B seed block now decides
`PS2X_WIDESCREEN` by precedence:

1. external `godhand.ini` line `PS2X_WIDESCREEN=...` — still wins over
   everything (the hard kill switch, unchanged);
2. the shell toggle — logged as
   `native: path B variant: PS2X_WIDESCREEN=1 (shell setting)`;
3. if the bridge lookup ever failed, on (the shipped-since-B5 state).

Like every shell setting it applies at the next game start; no file editing
is involved. With the toggle **off**, the whole 4:3 path returns: no aspect
patch group, host aspect 4:3, bridge aspect 4:3, canvas 1280x960 (aspect
4:3) — the on-screen result is the same correct 4:3 framing as pathb5; the
raster is 1280x960 rather than pathb5's 1280x896 because the canvas is now
aspect-true by construction (see above), and the log lines say so.

## Failure 1 — the regression, measured before touched

Per-call costs from the two owner logs (steady fight windows,
`[ee-prof:top]` program time / calls):

| program | pathb5 (4:3) | pathb6 (wide) |
|---|---|---|
| VU1 pc 0x0520 | ~17.2–19.6 us/call | ~22.5–23.8 us/call |
| VU1 pc 0x0358 | ~19.8–22.9 us/call | ~20.3–29.4 us/call |
| 0x0520 calls/window | ~45–65k | ~41–52k (scene-dependent) |
| `[vu1]` join_wait / fight window | 1.05–2.04 s | 1.48–1.61 s |
| queueWait max (session-cumulative) | 556.5 ms | 1,720.5 ms |

Readings:

- The joins did **not** grow (1.48–1.61 s sits inside pathb5's own range):
  the vblank join is a symptom, not the disease — it waits because the
  worker has real work queued. No join surgery was done; there was nothing
  honest to cut there.
- The queueWait maximum is a *session-cumulative single worst wait*, and
  in the pathb6 log it co-occurs with the scene-load window (fields=126,
  parked sync 1,100.2 ms, consume 116.1 ms): one full-pipeline drain at a
  load, in a build where everything downstream was slower. It is not a
  per-window cost and no per-frame fix applies to it.
- The per-call VU cost rose ~20–25% on the hottest program. pathb6 shipped
  exactly two codegen deltas over pathb5 (dead-norm elimination, `-O3`),
  plus the wider frustum making the *game* submit more work. The bench
  A/B below separates the two codegen deltas.

**Differential bench A/B** (`m24f-fixture/vu_bench.cpp`, extended with a
per-rep minimum statistic; 7 interleaved rounds of all four configurations;
ns per 1000 VU cycles, median of per-round minimums, lower is faster).
Behaviour gate first: **all four configurations produce identical digests
and cycle counts on all 22 entry fixtures**, and the hot entries
(2cfd/4dfe @0x0520, cd8f @0x0358) remain bit-exact against the interpreter
for the full 400k-cycle budget — any of the four was shippable; the choice
is purely speed.

| entry | dead-norm -O3 (pathb6) | dead-norm -O2 | no-dead-norm -O3 | no-dead-norm -O2 |
|---|---|---|---|---|
| 2cfd @0x0520 | 12,448 | **9,885** | 10,807 | 10,341 |
| 4dfe @0x0520 | 10,341 | **10,199** | 10,373 | 10,324 |
| cd8f @0x0358 | **6,028** | 6,072 | 6,206 | 6,174 |
| 2cfd @0x00B8 | 9,842 | 9,853 | 10,596 | 10,968 |
| 4dfe @0x00B8 | 9,835 | 10,010 | 10,910 | 10,459 |
| 5dce @0x0358 | **9,324** | 9,501 | 9,828 | 10,462 |
| sum, all 15 timed entries | 126,209 | **121,487** | 135,790 | 124,446 |

- **Dead-norm elimination is exonerated — it is faster**, at both opt
  levels, on nearly every entry (up to ~10% on 5dce @0x0358, ~7% on the
  0x00B8 kernels). It stays, exactly as shipped in pathb6.
- **`-O3` is the suspect that holds up**: the dead-norm tree at -O2 beats
  the same tree at -O3 overall and by ~21% on the single hottest entry's
  median (its -O3 best-of-7 matches -O2's, but its median collapses —
  the -O3 code is far more sensitive to cache state, which is precisely
  the phone's contended, small-cache environment). pathb7 therefore
  **reverts B5's `-O3` block**: `godhand_vu1_code` compiles at the
  project-wide level again (`src/CMakeLists.txt`; the block's comment
  records the evidence and how to re-enable).
- Caveat, stated plainly: the bench is an x86 host proxy for an ARM
  device. The direction (dead-norm good, -O3 not better and plausibly
  worse) is consistent across every hot entry, but the device run is
  the arbiter, as always — the markers below exist so it can convict or
  clear this call in one log.

**Present-path lightening (from the Failure 2 fix):** the per-field
capture/download/upload traffic drops from 1280x896 (4.59 MB) to 1280x720
(3.69 MB), −19.6%, on the GS thread's stretch + download and the host's
texture upload — pathb6's `[frame-split]` showed gs-fetch at 5.3–5.9 ms;
the copy-bound parts of that shrink proportionally.

**What was not done, and why:**

- *Join/pipeline surgery* (splitting the vblank wait, deeper pipelining):
  the accounting above shows joins did not regress; the wait is real
  work. Changing guest-visible sync semantics blind, with no device here
  to catch a subtle ordering break, trades a measured problem for an
  unmeasured risk.
- *Segment-emitter changes* (caching LQ raw values in locals, joining the
  vertex-loop segments): the segment full-write design (raw in memory,
  norm in a local) is load-bearing for segment exits and the self-loop
  refresh; the expected gain is single-digit percent against a wide
  correctness surface. Named here as the next lever instead (below).

**Honest bottom line for Failure 1:** pathb7 removes the one codegen delta
the evidence convicts (-O3), keeps the one it clears (dead-norm), and
takes ~20% off the present path's per-field traffic. If the device's
per-call times follow the host direction, fights should land back at or
past pathb5's 44–52 — *with widescreen on*. Locked 59.94 in wide mode is
not promised: the widened frustum makes the game itself submit more VU
work than the 4:3 scene does, and no translator change recovers work the
game genuinely has. The next lever, if the owner run still falls short,
is the segment emitter: joining the 0x00B8/0x0530 segment pair across the
vertex loop so registers stay in locals for a whole iteration instead of
round-tripping memory at every segment entry/exit.

## Package

- Package `dev.ptraaa.godhand`, versionCode **31**, versionName
  **0.25-m24-pathb7** (apksigner: v2 + v3 verified, 1 signer — the same
  debug keystore as every prior build).
- `godhand-0.25-m24-pathb7.apk` — 136,680,149 B; the delivery zip
  `godhand-0.25-m24-pathb7.zip` — 34,838,606 B. `unzip -t` clean.
- Contents (`lib/arm64-v8a/`, md5-matched build tree <-> APK):
  `libgodhand.so` **98,483,840 B** (variant; md5
  `59d9d198f486fe4e63dc83e5cac6988c`), `libpcsx2gs.so` (md5
  `8fcbbdb94ccdfb6cc39ca919c10fb196` — changed this round: the B6 canvas
  composition), `libshaderc_shared.so` (md5
  `2793f5b6394868783852325a4295e1f2`, unchanged). A copy of the variant
  library is kept at
  `~/workspace/godhand-android/libgodhand-0.25-m24-pathb7.so`.

## Verification done here

- Differential bench: all four codegen configurations identical in
  digests/cycles on all 22 fixtures (above); interp bit-exactness on the
  hot images unchanged.
- Bridge rebuild (`path-b/build-b1`, target `pcsx2gs`): exit 0.
- Android variant build (`build/android-m2a`,
  `-DGODHAND_PATHB_DEFAULT_PCSX2=ON`): exit 0; the variant libgodhand.so
  carries the new `(shell setting)` seed line alongside the B1B/B4/B5
  seeds and the `[widescreen]` boot string.
- Packaging with `GODHAND_PATHB=1`: exit 0, Path B staging confirmed
  (the shell Java, including the new toggle, compiles at this step),
  apksigner v2+v3 verified, version 31 / 0.25-m24-pathb7 confirmed via
  aapt; APK libraries md5-matched against the build trees (above).
- Mainline restoration: the variant configure was reverted to OFF, but
  the rebuild was overtaken by the stage B7 work landing in this tree
  (below); the tree's current state belongs to that stage.
- Not done here (no Android device on this VM): executing pathb7. The
  owner run below is the proof; the markers exist so it convicts or
  clears each change in one log.

## Log markers for the owner run

Boot/glue (proves the variant seeds and the toggle layer):

- `settings: owner chose scale=2 fps=... threads=... vsync=... prefetch=... gpu=... wide=on` (glue; new `wide=` field)
- `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant default; ...)` (B1B, unchanged)
- `native: path B variant: PS2X_VU1_ASYNC=1 (variant default; ...)` (B4, unchanged)
- `native: path B variant: PS2X_WIDESCREEN=1 (shell setting)` (B6: the
  toggle decided; an external ini line replaces this with
  `(from the external ...)` and still wins)
- shell log: `settings loaded: ... wide=on ...`

Runtime (proves the pieces are live):

- `[widescreen] PS2X_WIDESCREEN=1: aspect patch group on, host aspect 16:9`
  at boot, `[pnach]` continuous writes counting up.
- `[pcsx2-gs] capture canvas 1280x720 (source ..., aspect 16:9)` — the
  composed harvest size (new in B6).
- raylib `TEXTURE: [ID n] Texture loaded successfully (1280x720 | R8G8B8A8)`
  — **the presented texture is 1280x720** (pathb6 showed 1280x960/1280x896).
- `[ee-prof:top]`: 0x0520 / 0x0358 per-call cost against pathb6's
  ~22.5–23.8 / ~20.3–29.4 us and pathb5's ~17.2–19.6 / ~19.8–22.9 us.
- Gameplay fields/s against 44–52 (pathb5, 4:3) and 29.6–37.9 (pathb6, wide).

## Owner test steps

1. Install `godhand-0.25-m24-pathb7.apk` over pathb6 (same signer, in-place
   update). No file editing: if the external `godhand.ini` has a
   PS2X_WIDESCREEN line from the pathb6 A/B, delete it — it outranks the
   new toggle by design (kill switch), and the clean B6 verdict wants the
   toggle in charge.
2. In the launcher settings, confirm the new row: **Widescreen 16:9: ON**.
   Boot and play real gameplay (village + a fight). The picture should be
   true 16:9 — a wider view of the world with black bars only at the
   sides (~10% each), not a stretched or 4:3 picture.
3. Send back the full boot log. It should show the `(shell setting)` seed
   line, the `[widescreen]` line, the `capture canvas 1280x720` line, and
   the 1280x720 TEXTURE line, plus the usual `[vu1]` / `[ee-prof]` windows.
4. Toggle A/B (same install): set **Widescreen 16:9: OFF** in the launcher
   settings and reboot — exact 4:3 framing returns (`capture canvas
   1280x960 (aspect 4:3)`, TEXTURE 1280x960, no `[widescreen]` line);
   set it back ON and reboot to return. `PS2X_WIDESCREEN=0` in the
   external `godhand.ini` still forces 4:3 over the toggle;
   `PS2X_VU1_ASYNC=0` still restores pre-B4 behaviour, and
   `PS2X_GS_RENDERER=native` remains the built-in-renderer escape hatch.

## Files changed

- `path-b/compat/BridgeVK.cpp` — `CaptureFrame` composes onto an
  aspect-true canvas (1280x720 at 16:9/scale 2) + canvas log line.
- `godhand-recomp/src/CMakeLists.txt` — B5's `-O3` block for
  `godhand_vu1_code` reverted (evidence in the comment).
- `godhand-recomp/runtime/ps2xRuntime/src/lib/gs/gs_pcsx2_backend.cpp` —
  comment corrected (the composition lives in the bridge now); no
  behaviour change (surface choice and bridge aspect call as in B5).
- `godhand-recomp/android/glue/godhand_android_main.cpp` — widescreen
  seed takes the shell toggle between the variant default and the
  external ini; `IsoHolder.widescreen` JNI bridge; `wide=` in the
  settings line; header note.
- `godhand-recomp/android/shell/.../IsoHolder.java` — `optWidescreen`
  field + `widescreen()` getter.
- `godhand-recomp/android/shell/.../GameActivity.java` — fills
  `optWidescreen` from the `set_wide` preference; `wide=` in the
  settings-loaded log line.
- `godhand-recomp/android/shell/.../LauncherActivity.java` — the
  "Widescreen 16:9" settings row (`KEY_SET_WIDE`, default on).
- `godhand-recomp/android/shell/AndroidManifest.xml` — 31 /
  0.25-m24-pathb7.
- `godhand-android/m24f-fixture/vu_bench.cpp` — unchanged; the B6 bench
  harness is the same tool with a per-rep minimum statistic, kept in the
  B6 scratch (`~/workspace/tmp-gh/b6/vu_bench_min.cpp`) with its raw
  outputs (`logs/timing_all.txt`).
- Not changed: the generated VU tree (dead-norm stays), the VU runtime,
  the async VIF pipeline, ARUS, the audio path, VU0, the PCSX2 bridge
  binary interface (the canvas is internal to the harvest), every
  mainline default.

- Logs: `b6-bridge-build.log`, `b6-configure.log`, `b6-build.log`,
  `b6-package.log`, `b6-restore-configure.log`, `b6-restore-build.log`.

## Addendum — the device outcome, and what it reversed (written after the owner run)

pathb7 was tested on the device, and the full forensic is in
**PATH-B-B7.md** (stage B7 / pathb8), which supersedes this report's two
headline expectations. Recorded here so this file does not stand as a
claim the device already answered:

- **Performance:** pathb7's opening fights recovered to 44.1–45.1
  fields/s (the `-O3` revert held — that part of the A/B pointed the
  right way), then the run collapsed mid-session to 13.8–28.8 and
  partially recovered to 31.4–36.0. Settled per-call VU cost stayed at
  pathb6's level (~21–23.8 us on 0x0520 against pathb5's 17.2–19.6).
  The B7 forensic convicts the piece this report exonerated:
  **dead-norm elimination is slower on the device**, at constant guest
  work (vcyc/run unchanged across builds). Its savings are real only
  inside a block; `runFast` re-materialises the "dead" normalised copies
  at every block boundary (`fixupNorm` over the dirty set), and the hot
  programs are block-dense, so the work was moved and concentrated, not
  removed. The x86 bench above measured the opposite because a bench run
  is back-to-back in hot caches with none of the boundary pressure at
  device scale — the caveat printed in the Failure 1 section ("the
  device run is the arbiter") is exactly where this landed. The mid-run
  collapse itself is attributed by B7 — as a stated inference, the log
  has no thermal telemetry — to SoC throttling, plausibly reached sooner
  because the same guest work cost ~20% more host energy throughout.
  pathb8 restores the VU stack to pathb5's byte-identical state.
- **Present layer:** the canvas composition shipped here is necessary
  but was **not sufficient**, and this report's step-5 geometry claim
  (1920x1080 on the panel, ~10% side bars) was wrong as shipped. One
  layer further out, raylib's Android host screen is a fixed virtual
  640x480 (4:3) that raylib letterboxes into the real display: a 16:9
  fit inside it capped the drawn box at 1440x810 on the owner's
  2400x1080 panel, black on all four sides, even with a true 16:9
  raster underneath. The code-path proof above stopped at the virtual
  screen's edge. B7 fixed that layer (the host screen follows the
  content aspect when widescreen is on) and instrumented it with a
  `[present-rect]` line. The shell toggle built here is kept in pathb8;
  its default is flipped to off there, because widescreen did not hold
  the performance floor.
