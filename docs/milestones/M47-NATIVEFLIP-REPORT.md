# M47 — the flip: native renderer + ARUS GPU backend as the default path (0.47-m47-nativeflip)

Stage 2's first build (plan: `docs/STAGE2-NATIVE-MAINPATH-SCOPE.md`
§3; the scope's G8a + G8b, nothing else). m46 was skipped on
Pablo's order; the numbering jumps m45 → m47, and versionCode
53 → 54. The tree was re-verified clean at the m45 shipped state
before any edit, using the scope §0 one-minute check: `path-b/
compat/BridgeVK.cpp` md5 `9d8ec048…` (identical to the repo
clone's tracked copy), the newest sources in both trees are the
m45 edit set (2026-11-11 16:53–16:54 UTC), and the build cache
rested at `GODHAND_PATHB_DEFAULT_PCSX2:BOOL=OFF`. The stopped
m46 lane left no edits, no work dir, no half-flipped cache.

## What "the flip" had to mean in this tree — and the one trap in it

The naive reading of the scope's G8a ("ship the variant with no
`PS2X_GS_RENDERER=pcsx2` seed") is: build with the CMake option
`GODHAND_PATHB_DEFAULT_PCSX2` OFF, as in the m25 construction.
**In today's tree that is not a one-variable change, and it was
not done.** The whole seed block in the Android glue — renderer
seed, VU1 async / fast-FMAC / lean-execute seeds, the
shell-toggle widescreen seed, the bridge warm-up seed, and the
m39 core seeds (async audio feed, SPU2 nearest + reverb off,
bridge HW-download/blending) — lives inside the single
`#ifdef GODHAND_PATHB_DEFAULT_PCSX2` block
(`android/glue/godhand_android_main.cpp:540` → `#endif` ~:845)
with **no `#else`**. Building with the option OFF would have
silently deleted all of those seeds with it: the m39 audio would
revert to the m38 time-stretch feed, the widescreen toggle would
stop reaching the port, and three VU1 performance seeds would
vanish — five behaviour changes wearing a "defaults only"
label, and exactly the class of accident this campaign's
discipline exists to prevent.

So the flip was done the other way, the smallest selection
change that changes *only* the defaults (the task's own escape
clause): the variant block stays compiled in (the build
procedure is m44/m45's, unchanged — option ON for the variant
build, staged `.so`, cache restored OFF), and inside it the
**renderer seed's default value flips from `pcsx2` to
`native`**. The compiled-in variant default — the layer that
has outranked the ini in every shipped build since m24 — now
resolves native. Every other seed keeps its m45 value, and the
external-ini passthrough is untouched: `Renderer=pcsx2` (or a
`PS2X_GS_RENDERER` line) in the owner's external `godhand.ini`
still selects the bridge, which stays in the package as the
selectable fallback.

The second half of the flip is the GPU-backend default. On the
native path, `GSThreadedBackend::MakeNativeDefault()` picks the
GPU backend (`GSGpuBackend`) iff `PS2X_GS_GPU` resolves on, and
the glue always overwrites that variable from the shell's GPU
toggle (`godhand_android_main.cpp:532`). The toggle's
unset-preference fallback was `false`, so a stock install's
"native" silently meant the **CPU** backend (scope §1.1 — that
is also where m44's emergency fallback lands today). m47 flips
the fallback to `true` at every site the shell consults it —
the m41 widescreen-default pattern exactly: only the
unset-preference fallback changes, a stored value is read back
exactly as stored, and the toggle itself is untouched (still
shown, still tappable; off is now what it always claimed to be,
the explicit CPU-backend fallback choice).

Net effect on a stock install: boot → seed resolves
`PS2X_GS_RENDERER=native` → built-in GS → `PS2X_GS_GPU=1` →
`GSGpuBackend`. All four ARUS pillars, dormant on the bridge
path (scope §1.2), are live on the main path for the first
time since m24. On Pablo's own phone the shell half of the
flip is a no-op — his GPU toggle is already stored on, and his
persistent external ini already says `native`; the seed half
is the one that changes his boot, because the compiled-in
`pcsx2` seed used to outrank that ini and no longer does.

## Source changes (orig/ snapshots in m47nativeflip-build-work/orig/)

- `android/glue/godhand_android_main.cpp` (+20/−4):
  - Block comment at the seed site (:549–558): the passthrough /
    variant-default description now names native as the default
    and pcsx2 as the escape hatch, with the m47 note.
  - The seed itself (:632): `setenv("PS2X_GS_RENDERER",
    "native", 1)` and its log line now prints
    `PS2X_GS_RENDERER=native (variant default; …)`. Verified in
    the shipped lib (below): the native-default string is
    present, the pcsx2-default string is gone.
  - One added log line (:644–658), the build's self-identifying
    marker, printed only when the renderer resolved native
    (seeded default or ini value, case-insensitive, anything
    but `pcsx2` — mirroring `GSPcsx2Backend::Requested()`) AND
    the shell GPU setting resolved on:
    `native: m47: native ARUS GPU backend is the default path (renderer native, gpu backend on)`.
    A bridge boot or a CPU-backend boot never prints it, so a
    returned log certifies its own configuration.
  - Nothing else in the glue: every other seed (VU1, widescreen,
    warm-up, m39 audio/GS) is byte-identical to m45.
- `android/shell/.../GameActivity.java` (+9/−6 net by hunk): the
  m42 comment block rewritten for the m47 reality (the old text
  called the toggle an alternate renderer on the bridge path),
  and `KEY_SET_GPU` fallback `false` → `true` (:162) — the
  value that reaches the glue via `IsoHolder`.
- `android/shell/.../LauncherActivity.java` (+2/−2): the same
  fallback flip at the toggle's tap handler (:410, so a first
  tap on a fresh install turns it OFF, matching the displayed
  state) and at the settings-surface readout (:979, so the
  surface shows ON on a fresh install). The button's label text
  is unchanged — m47 changes defaults, not the surface.
- `android/shell/AndroidManifest.xml`: versionCode 53→54,
  versionName `0.46-m45-bootmsg` → `0.47-m47-nativeflip`.

No runtime, GS, bridge, disc or audio source was touched: no
rasterizer edits, no pillar tuning, no pacing changes. The m43
clipChildren bisect and the m45 boot-failure message ride along
exactly as shipped in m43–m45 (their strings and the
`nativeBootFailure` JNI export were re-verified present in the
new core lib).

## Build

- Core: `build/android-m2a`, cache flipped
  `GODHAND_PATHB_DEFAULT_PCSX2=ON` (configure log in the work
  dir), ninja rebuilt exactly the glue TU + relink — clean.
  Staged as `m47nativeflip-build-work/libgodhand-m47.so`
  (101,494,504 B, md5 `42e53e3e…`; +768 B over m45's — the
  flipped strings plus the marker block, not a configuration
  change). Cache then restored to OFF and verified
  (`configure-restore.log`; the resting state matches §0).
- Binary-level proof in the staged lib: `PS2X_GS_RENDERER=native
  (variant default` PRESENT, `PS2X_GS_RENDERER=pcsx2 (variant
  default` ABSENT, the m47 marker string PRESENT,
  `GODHAND_BOOT_FAILURE` + the m45 record line PRESENT,
  `PS2X_AUDIO_ASYNC=1 (variant default` PRESENT (m39 seeds
  intact), `nativeBootFailure` JNI export present.
- Packaging: `m47nativeflip-build-work/package_m47.py` (the m45
  script's flow verbatim, names/base/assertions updated):
  fresh javac/d8 dex from the tree shell sources (the feature
  half lives in Java), aapt2 compile/link with the in-tree
  manifest, entries re-zipped in the shipped order, zipalign,
  apksigner with the probe key.

## Arbitration vs the delivered m45 APK (entry-for-entry)

Entry set, order and per-entry compression types identical
(19 entries).

| entry | m45 | m47 | verdict |
|---|---|---|---|
| AndroidManifest.xml | b9da1120…, 2,544 B | 66da2216…, 2,548 B | DIFF — version strings only (aapt2 xmltree diff = exactly versionCode 53→54 and the versionName line) |
| classes.dex | b7e13b1d…, 90,040 B | cdeedc1a…, 90,044 B | DIFF — intended: the ONLY shell change (GPU fallback flips at the three sites + the GameActivity comment; source diffs above) |
| lib/arm64-v8a/libgodhand.so | b62c3978…, 101,493,736 B | 42e53e3e…, 101,494,504 B | DIFF — intended: the glue flip (seed default + marker), +768 B |
| lib/arm64-v8a/libpcsx2gs.so | ce07badc…, 28,793,912 B | ce07badc…, 28,793,912 B | IDENTICAL (asserted in-script against the m45 APK entry) — the bridge stays packaged as the selectable fallback |
| lib/arm64-v8a/libshaderc_shared.so | 2793f5b6…, 10,007,448 B | 2793f5b6…, 10,007,448 B | IDENTICAL |
| resources.arsc + all res entries | — | — | IDENTICAL, all |
| META-INF/*.SF/.RSA/MANIFEST.MF | — | — | DIFF — expected: signature digests of the changed entries; same signer (below) |

Gates: apksigner verify v2+v3 true; signer SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e
(the m39–m45 signer — installs over m45); zipalign -c -p 4
successful; aapt2 dump badging: dev.ptraaa.godhand,
versionCode 54, versionName 0.47-m47-nativeflip. The zip's
inner APK md5 equals the delivered APK's.

## Test status — honest

Not exercised on a device: there is no Android device or
emulator here, and this build's entire content is which
backend a phone boots — host-side, what is proven is that
both changed sides compile clean in their real build flows,
the shipped lib carries the flipped default and none of the
old one, the dex was built from the flipped sources, and the
selection chain is the tree's own (`Requested()` returns
false for anything but `pcsx2`; `MakeNativeDefault()` picks
`GSGpuBackend` iff `PS2X_GS_GPU` is on; the glue writes both
variables before the port runs). The configuration this
build boots is the one the 0.23/0.24 native sessions ran, so
it is a known-quantity path, not an experiment — but the
baseline numbers below are owed from THIS build on the
POCO F5, and until that log returns, m47 is a delivered
test build, not a proven one.

## The device proof owed — and the baseline it must produce

Protocol (Pablo): install m47 over m45, Developer mode, one
2–3 minute saloon fight without leaving the app mid-session,
return the whole boot log. (Developer mode so the settings
surface and log are complete; Player mode would run the same
path.)

Selection proof — these lines, in the boot's first seconds,
certify the flipped configuration; if any disagrees, the
line itself names the layer that decided instead:

- `native: path B variant: PS2X_GS_RENDERER=native (variant default; no renderer setting in the external …)` — or, on Pablo's phone, the `(from the external …)` form of the same line with value `native` (his ini carries the line; both forms prove the seed layer resolved native).
- `settings: owner chose scale=… gpu=on …` and `renderer: gpu (owner setting)` — the glue wrote `PS2X_GS_GPU=1`.
- `native: m47: native ARUS GPU backend is the default path (renderer native, gpu backend on)` — the marker; present iff the boot is the flipped default path.
- `[gs-gpu]` / `[gs-hw]` lines during play (shader cache, hardware raster) — the GPU backend alive, not just selected.
- Absence as proof: **no** `[pcsx2-gs]` line anywhere, and no `[gs] renderer: PCSX2 GS` — the bridge never opened.
- The m39 audio witness lines (`[audio] m39 feed:` / the async seed prints) unchanged from m45 — the audio configuration rode along.

The native baseline — read from the fight's `[gs-work]`
windows (the GPU backend's ≥5 s accounting windows; this is
the table m48's profiling round plans from):

| number | where in the log | how to compute | 0.24 reference (scope §1.3) |
|---|---|---|---|
| **Fence ms per fight field** (the stage-2 metric) | `[gs-work]` `fence <count>/<ms>` and `fields=<n>` in the same window | fence ms ÷ fields | 53–80 ms (windows of 3,930–4,722 ms over 49–83 waits) |
| Fence waits per field | same line | fence count ÷ fields (≈1.0 expected at gate K=2) | ≈1 (49–83 waits ≈ one per field) |
| Fields per second (guest production on native) | `[gs-work]` `fields=<n>` ÷ window seconds | the window is ≥5 s; the log's own cadence gives the denominator | (not the 0.24 headline; bridge-era fights ran 52–60) |
| Presents per second | `[gs-work]` `present <count>/<ms>` ÷ window seconds | count ÷ window seconds; also present ms ÷ count for the present cost | 3.5–4 /s in the heaviest windows |
| Present age | `[gs-work]` `present age <avg>/<max> fields` | read directly | avg 3.0–4.1 fields |
| Frame-pacing bands | `[frame-stats]` window(s) covering the fight | the 25–33 ms band's share of presents, same arithmetic as the m42 analysis | m39 (bridge): 1.2–1.7%; m40–m42: 7.6–9.2%; m43+ never measured on device |
| GS worker split | `[gs-work]` `submit … flush (raster … uploads …)` + `prims/batches/tiles` | read directly per window | submission was ~2.6 ms/field in 0.24; the flush/raster split is m48's input |
| Audio on the native path | `[audio]` feed lines + Pablo's ear | the m39 async feed running under native production rates | m39 verdict on the bridge path: sound ~80% |

Reading the result: m47 succeeds if it (1) boots and plays
the full fight on the native GPU backend per the lines above,
and (2) produces the baseline table filled with THIS build's
numbers. Whether those numbers are good is m48's question,
not m47's — the profiler run (`PS2X_GS_GPU_PROFILE=1`, scope
G1) is seeded against exactly these windows, and a fence
figure wildly outside the 0.24 family (in either direction)
is itself the finding that re-plans m48.

## Deliverables

- godhand-0.47-m47-nativeflip.apk — 140,506,559 B, md5
  3a5bcb0156e3d5cde6a429791ccb7bee
- godhand-0.47-m47-nativeflip.zip — 35,570,987 B, md5
  65811807d6b70dfc26b7888d62d288c0 (inner APK md5 verified
  identical)
- Work dir: m47nativeflip-build-work/ (orig/ source snapshots,
  package_m47.py, configure-variant / core-build /
  configure-restore / package logs, staged libgodhand-m47.so,
  pkg/ intermediates)
