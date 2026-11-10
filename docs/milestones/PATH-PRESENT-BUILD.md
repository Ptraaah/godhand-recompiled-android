# Harvest-fastpath build (0.32-m31-harvest, versionCode 39)

**What this build is and nothing else.** m30 (0.31-m30-vblank)
with one variable changed: the bridge. `libpcsx2gs.so` is rebuilt
from `path-b/compat/BridgeVK.cpp` with the PATH-PRESENT.md §3 lever
compiled in — `PS2X_GS_HARVEST_FASTPATH`, default ON,
`PS2X_GS_HARVEST_FASTPATH=0` restores the m30 serve verbatim (see
the kill-switch verification below). The core is m30's shipped
core, byte for byte: the compiled-in Path B variant seed, the
forced-pcsx2 renderer resolution over the owner's ini, pipeline
on at depth 2 and the VU async seed are therefore exactly m30's,
because they live in files this build does not touch. The shell,
dex and resources are m30's bytes; the per-file table below
proves it from the final signed APK. No performance outcome is
claimed — §7 of PATH-PRESENT.md is what the device log
arbitrates.

## Source provenance — the only source that changed

`path-b/compat/BridgeVK.cpp`, nothing else. Full unified diff
against the pre-change file (preserved at
`repo/path-b/compat/BridgeVK.cpp`, md5
`02a08449606c94e68a801a389a0d16de` before and after this stage):
`m31-bridge-source.diff`, 12 hunks, +139 / −7 lines. The only
removed code is the old `PollCapturesLocked` signature and its
fence condition, absorbed into the split below; the m30 serve
path (`RefreshServeLocked` and the grab tail it feeds) is not
modified by a single line — the fastpath is added beside it.
Changed file md5 after the stage:
`48ae80b3bf04928e2548cd57bca476cf`. (The `repo/` mirror was left
untouched by this stage; syncing it is a separate step, and the
diff baseline above is its current content.)

The change, item by item (PATH-PRESENT.md §3):

1. **Serve from the slot.** `RefreshServeFastLocked()` (new)
   pins the freshest materialized capture by ring index in a new
   `g_grab_slot` global and stages only its metadata (width,
   height, vsync) in the `g_grab_*` state. The
   `g_grab_pixels = best->pixels` copy exists only in the
   untouched `RefreshServeLocked`. In `pcsx2gs_grab_frame2` the
   fastpath copy call memcpys directly from
   `g_captures[g_grab_slot].pixels` under `g_captureMutex`.
   Re-pinning requires a strictly newer capture, so when nothing
   newer has materialized the previous pin keeps serving — the
   fastpath twin of the staged cache serving the previous frame.
2. **Materialize on the bridge thread only.** `PollCapturesLocked`
   is split into `CheckCaptureFencesLocked()` (marks a new
   per-slot `fenceDone` flag for pending captures whose fence has
   completed; no copies) and `MaterializeCapturesLocked()` (the
   staging→slot copy for fence-done slots, in the original ring
   order, with the original Flush/Map ordering and the original
   Map-failure drop). `PollCapturesLocked()` remains as the exact
   composition of the two. `CaptureFrame` — the bridge-thread
   caller — still calls the composed poll, so it materializes
   exactly as in m30; the fastpath grab path calls only the fence
   check and never materializes. `fenceDone` is reset wherever
   `pending`/`ready` are reset (record, invalidate, destroy).
3. **Pin-integrity guard in the copy call** (PATH-PRESENT.md §4).
   The copy call re-validates the pin under the mutex before
   reading: the pin is intact while the slot is untouched (ready,
   pinned vsync) or merely re-recorded (new vsync, not ready —
   its pixels still hold the pinned frame, because a slot's
   pixels are only overwritten by materializing a newer capture
   into it, which flips it back to ready under the new vsync in
   the same locked step). Otherwise the pinned frame's pixels
   are gone: the call re-pins to the freshest materialized
   capture and serves that, or returns 0 if none verifies. No
   path serves pixels under another frame's counter or past the
   pinned byte count.
4. **Boot line.** Open prints
   `[pcsx2-gs] harvest fastpath: on|off (PS2X_GS_HARVEST_FASTPATH=0 restores the staged serve)`
   so a device log proves which serve ran.

Untouched, as §3 requires: the Sync(Presentation) drain, the
threaded backend, the bridge pipeline and its depth bound, the
capture ring size (3), and every VU/GIF path (those live in the
core, which is byte-identical).

## Kill-switch verification (=0 restores the m30 serve verbatim)

Code-level, from the shipped source:

- `pcsx2gs_grab_frame2` branches once on
  `HarvestFastPathEnabled()`. The =0 fall-through is the m30
  body, unmodified: size query → `RefreshServeLocked()`, serve
  from `g_grab_pixels` with the staged metadata.
- `RefreshServeLocked` is unmodified (the diff shows no hunk
  inside it): it still calls `PollCapturesLocked()` and still
  copies `best->pixels` into `g_grab_pixels`.
- `PollCapturesLocked()` = fence check + materialization performs
  the identical device-call sequence as m30's single function:
  one `GetCompletedFenceCounter()`, then per completed slot in
  ring order `pending=false`, `Flush()`, `Map()`, row memcpy,
  `ready=true`, with the same Map-failure drop. The `fenceDone`
  flag is set and consumed inside that one locked call in =0
  mode (no other =0 caller touches it), so it leaves no
  cross-call residue.
- `CaptureFrame` is unchanged apart from resetting `fenceDone`
  alongside `pending`/`ready` when it records a capture.
- Strings in the shipped bridge (grep-verified): the
  `PS2X_GS_HARVEST_FASTPATH` env name and the boot-line format
  are present; the exports `pcsx2gs_grab_frame` /
  `pcsx2gs_grab_frame2` / `pcsx2gs_get_api` / `pcsx2gs_set_aspect`
  are unchanged.

## Bridge build

Incremental ninja build in the existing `path-b/build-b1` tree
(the m27 pipeline bridge's tree): exactly one object recompiled
(`compat/BridgeVK.cpp.o`) and the library relinked
(`m31-bridge-build.log`). No other bridge source, and no other
library, was rebuilt.

| Bridge | md5 | Size |
|---|---|---|
| m30 shipped (`path-b/build-b1` before this stage) | `9e468a9082b5e51fdfaf435c9dad909a` | 28,786,128 B |
| m31 staged (`path-b/build-b1` after this stage) | `0fc367db8b63cd733d1bccd8c19b8574` | 28,791,224 B (**+5,096 B**) |

## Per-file md5 delta table (base m30 → this build, from the final signed APK)

Expected delta set: exactly {`libpcsx2gs.so` (the rebuilt
bridge), `AndroidManifest.xml` version fields, signature files}.
Observed delta set: **exactly that set — no unexpected delta.**

| File | m30 md5 | m31 md5 | Verdict |
|---|---|---|---|
| `lib/arm64-v8a/libpcsx2gs.so` | `9e468a9082b5e51fdfaf435c9dad909a` (28,786,128 B) | `0fc367db8b63cd733d1bccd8c19b8574` (28,791,224 B) | **CHANGED — the one intended payload change**; md5 equals the staged rebuild `path-b/build-b1/libpcsx2gs.so` |
| `lib/arm64-v8a/libgodhand.so` | `17ba5dc9628185de05d3e9c2a83425b0` (99,723,032 B) | `17ba5dc9628185de05d3e9c2a83425b0` (99,723,032 B) | **byte-identical** — m30's core with the variant seed: forced-pcsx2 resolution, pipeline depth 2 and the VU async seed are inherited untouched |
| `lib/arm64-v8a/libshaderc_shared.so` | `2793f5b6394868783852325a4295e1f2` | `2793f5b6394868783852325a4295e1f2` | **byte-identical** |
| `classes.dex` | `2d32e68be8c0eb911d80c700529277ee` | `2d32e68be8c0eb911d80c700529277ee` | **byte-identical** (m30 shell) |
| `resources.arsc` | `57ef0403aecfe9a84e3bee942fc1d080` | `57ef0403aecfe9a84e3bee942fc1d080` | **byte-identical** |
| `AndroidManifest.xml` | `36881126bbc5c758f11221805bde027e` (2,452 B) | `69cdd01a441844a99dcd4fa78190a141` (2,452 B) | CHANGED — version fields only (versionCode 38 → **39**, versionName `0.31-m30-vblank` → `0.32-m31-harvest`); `aapt2 dump xmltree` diff shows the two version attributes and nothing else |
| `META-INF/MANIFEST.MF` | `27a9387632f78b2cd0cba6478dccb95c` (589 B) | `e864106effb400065461031c7e0a94a2` (589 B) | CHANGED — signature file (regenerated by re-signing) |
| `META-INF/ANDROIDD.SF` | `86e0025983081621d24eb1f735c3eaa1` (716 B) | `235e18e4eea1bdafbf70b1dcd3b4b67e` (716 B) | CHANGED — signature file |
| `META-INF/ANDROIDD.RSA` | `d08ff1824c8234047cff0349181334f0` (1,194 B) | `4ba00eab1349e4a1e2a03876d1670e35` (1,194 B) | CHANGED — signature file |

Entry sets, order and compression methods are identical
(9 entries in both APKs: manifest deflated, all payload stored,
signature files deflated) — the m30 repackage method, mirrored
in `harvest-build-work/` (patch script `patch_axml_m31.py`,
adapted from m30's with only the version constants changed).

## Deliverables

- `godhand-0.32-m31-harvest.apk` — 138,613,365 B, md5
  `aa9b77042437301127230bea4f0c88ea`; package
  `dev.ptraaa.godhand`, versionCode **39**, versionName
  **0.32-m31-harvest**, minSdk 26, targetSdk 35 (aapt2 badging) —
  installs as an update over m30 (versionCode 38).
- `godhand-0.32-m31-harvest.zip` — 35,090,584 B, md5
  `913aca7040e6773235fdd70a3fe4ed00`; contains the single APK;
  archive integrity verified, inner APK md5 matches the
  standalone file.
- `zipalign -c -p 4` passes on the final APK.
- Signer: apksigner **v2 + v3 verified, 1 signer** — DN
  `CN=Probe, O=ptraaa, C=ID`, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — the same shared debug signer as b5/m26/m27/m28/m29/m30, so
  the update installs in place.

## The lines that arbitrate the device run

PATH-PRESENT.md §7, in its order, against the m30 baselines in
M30-LOG-ANALYSIS.md. Config proof first — if these lines are
absent, the run measures nothing:

- **Config proof (core, unchanged from m30):** the boot must
  print `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant
  default; no renderer setting in the external ...)` and
  `[BOOT] settings: env: PS2X_GS_RENDERER=pcsx2 (wins over
  godhand.ini native)` — the internal ini will still say native;
  the env line winning over it is the proof. It must also print
  `native: path B variant: PS2X_VU1_ASYNC=1 (variant default;
  ...)` and `[pcsx2-gs] pipeline on: ... depth 2 fields`. There
  must be **no** `path B variant: PS2X_WIDESCREEN` and no
  `PS2X_GS_WARMUP` seed line.
- **Config proof (this stage):** `[pcsx2-gs] harvest fastpath:
  on (PS2X_GS_HARVEST_FASTPATH=0 restores the staged serve)`.
  An `off` line means the kill switch was set from the ini and
  the run measures the m30 serve, not the lever.
- **`[pathb] harvest` readback_wait per present:** target
  < ~1.2 ms (m30: 1.95-2.69 ms) — the lever's direct signature.
- **`[ee-work] parked: present`:** target < ~450 ms/window in
  light windows (m30: 667.5-908.0 everywhere).
- **`[frame-split]`:** gs-fetch share target < ~22% (m30:
  24-35%); avg must still equal 1000/fields-per-s.
- **`[ee-work]` heavy fields/s and `[vu1] joins`:** expected
  unchanged within noise (crowd is throughput-bound); a large
  jump either way falsifies PATH-PRESENT.md §3's model and must
  be explained before the lever is credited.
- **Judder bound:** `[pathb] pipeline` dropped_captures and
  harvest suppressed against m30's 3-47/window suppressed range;
  growth means the §5 staleness cost is real.
- **Kill switch / A/B (external `godhand.ini`):**
  `PS2X_GS_HARVEST_FASTPATH=0` restores the m30 serve in this
  same binary (expect the boot line to read `off` and
  readback_wait to return to the 1.95-2.69 ms band). The m30
  switches stay live beneath it: `PS2X_VU1_VBLANK_BOUND=0`,
  `PS2X_VU1_LIVE_REGS=0`, `PS2X_GS_PIPELINE=0`. The deliberate
  road back to the native renderer is unchanged: a
  `PS2X_GS_RENDERER=native` line in the **external**
  `godhand.ini`.
