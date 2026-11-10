# Native build M25 — 0.26-m25: the native (ARUS) renderer, by construction

Build: **0.26-m25**, versionCode **33** — installs over pathb8 (0.25-m24,
versionCode 32) in place, same signer (certificate SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e,
verified identical to pathb8's). This build exists because of the
owner's pathb8 verdict ("Masih nge lag banget, ga ada perubahan") and
his direct order: "Native in please."

## Why pathb8 never ran the native renderer (the forensic in one paragraph)

pathb8 is a Path B **variant** build. Its Android glue carries a seed
block behind `#ifdef GODHAND_PATHB_DEFAULT_PCSX2`
(`godhand-recomp/android/glue/godhand_android_main.cpp:509-659`): when
the owner's external godhand.ini names no renderer, the glue writes
`PS2X_GS_RENDERER=pcsx2` into the real environment — the layer
loadSettings ranks **above** the app's internal ini. The owner's own
boot log (last session, 2026-11-05 19:27) proves it decided every
session: `[BOOT] settings: env: PS2X_GS_RENDERER=pcsx2 (wins over
godhand.ini native)`. His ini said native the whole time; the variant
default overruled it before the runtime ever looked. The lag he
measured was the bridge's: fight windows 31.7-47 fields/s, sync waits
75-88% of frame time, GS idle 66-87% — CPU/bridge-bound, not
GPU-bound.

## What this build is

- **Tree**: `godhand-recomp`, build dir `build/android-m2a`, in the
  restore-verified mainline state left by the B7 stage
  (`GODHAND_PATHB_DEFAULT_PCSX2:BOOL=OFF` in CMakeCache; ccache
  launcher + mold from the restore configure, untouched — no
  reconfigure was done for this stage). The shipped libgodhand.so is
  that restore build's product: a `ninja -n` dry run before packaging
  reported **0 pending steps**, i.e. the binary is current with the
  restored sources. No engine source was edited for this stage; the
  only source change is the shell manifest version bump
  (versionCode 32 → 33, versionName 0.25-m24-pathb8 → 0.26-m25).
- **Native by construction, in three independent layers:**
  1. The variant seed block is compiled **out** (the
     `GODHAND_PATHB_DEFAULT_PCSX2` option is OFF; the shipped .so
     contains zero "path B variant" strings — acceptance scan below).
     Nothing writes a renderer into the environment ahead of the ini.
  2. The settings chain resolves native: the built-in default ini
     (`godhand.ini`, compiled in as kDefaultIni) carries
     `[Video] Renderer=native`; loadSettings exports it as
     `PS2X_GS_RENDERER=native` and BOOT-logs the outcome line
     (`ini: PS2X_GS_RENDERER=native`, or an `env:` line if a real
     environment value ever outranks it — the log shows which layer
     decided, exactly as it did for pathb8). The owner's existing
     on-device ini already says native (his pathb8 log shows it being
     overruled), and app data survives the update install.
  3. The APK does not contain the bridge at all: packaging ran
     **without** `GODHAND_PATHB=1`, so `lib/arm64-v8a/` holds exactly
     one library, libgodhand.so — no libpcsx2gs.so, no
     libshaderc_shared.so. Even a stray `PS2X_GS_RENDERER=pcsx2`
     would fail the dlopen and fall back to the built-in GS with a
     `[gs]` log line (`GSPcsx2Backend::Requested()`,
     gs_pcsx2_backend.cpp:210ff: anything but "pcsx2" keeps the
     built-in GS).
- **ARUS**: on, master switch and all four pillars at the shipped
  0.23/0.24 defaults (upload dedup, 2x2 block shading, async readbacks
  mode 3, queue-depth gate K=2); `arus=off` and the per-pillar kill
  switches behave exactly as in 0.23/0.24.
- **Toggles, unchanged from what the owner already has**: widescreen
  toggle in the launcher, default **off** (IsoHolder `optWidescreen =
  "off"`). Stated precisely: on the native build the toggle's seed
  path is part of the variant glue, so the native renderer takes
  widescreen only from a `PS2X_WIDESCREEN` ini line; with none set it
  is off. Shell GPU-renderer setting default off (the CPU rasteriser,
  the native path proven on this phone). VU1 async (MTVU) on the
  native build is the compiled-in default **off**, reachable via the
  ini passthrough — the pathb variant's default-on seed does not
  exist here; this matches the shipped 0.23/0.24 native behaviour.

## What differs from 0.24-m23 (the last native build)

- The present-layer fix from the B7 stage is shared runtime code and
  is in this binary (the `[present-rect]` format string is present in
  the shipped .so): the Android host screen follows the content
  aspect instead of a fixed 4:3 virtual screen, and every session
  logs the drawn rectangle's arithmetic.
- The VU AOT stack is the restored tree — byte-identical to the
  pre-B5 (pathb5) state, per the B7 stage's `diff -r` proof. The
  dead-norm experiment never shipped in a native build and is not in
  this one.
- The bridge capture warm-up (`PS2X_GS_WARMUP`) is bridge-only code;
  no bridge ships here, so it is simply absent.
- Everything else — ARUS, audio, controls, thread policy — is the
  mainline state 0.24 shipped, plus the shell's widescreen toggle
  (default off, above).

## Performance expectations — stated honestly, nothing promised

No performance outcome is claimed for this build. The only native
device numbers on record are the owner's 0.24 run
(`owner-0.24-run/ANALYSIS.md`, POCO F5): in the heaviest fight
windows the native renderer delivered **~3.5-4 presents/s**,
fence-bound — GS worker 3930-4722 ms per window inside fence waits,
GPU busy 95-98%, ~50-80 ms of genuine GPU execution per field;
0.23's fight windows ran 5-12 presents/s. That is far below the
Path B builds' fields/s figures, and the two metrics are not the
same (native counts presented frames; the bridge numbers count EE
fields). The deltas in this build versus 0.24 — the present-layer
fix and the VU tree state — are not fight-throughput changes anyone
has measured. What this build guarantees is only that the renderer
the owner asked for is the renderer that runs. Whether native feels
better or worse than the bridge on his phone is his next log's
verdict, and this report makes no prediction either way.

What to read in that log: the `[BOOT] settings:` lines (expect
`ini: PS2X_GS_RENDERER=native` and **no** `env: PS2X_GS_RENDERER=pcsx2
(wins over ...)` line), the absence of any `[gs] renderer: PCSX2 GS`
line, the `[present-rect]` line, and the `[frame-split]` presents
numbers in fight windows.

## Acceptance checks (all run on the shipped files)

- Strings scan of the shipped libgodhand.so: **0** "path B variant"
  lines, **0** "variant default" seed lines.
- The APK's `lib/arm64-v8a/libgodhand.so` md5-matches the build
  tree's .so byte for byte: `b1f3bf2bb62584affb38f54bebfb845f`
  (99,711,576 bytes).
- APK contents: classes.dex + libgodhand.so only; no bridge
  libraries. `unzip -t` on the APK: no errors.
- apksigner: verifies v2 + v3, 1 signer, certificate SHA-256
  9791d26c… identical to pathb8's (update install is valid).
- aapt badging: `dev.ptraaa.godhand`, versionCode 33, versionName
  0.26-m25, minSdk 26, targetSdk 35.
- Delivery zip: `zip -T` OK.

## Files

- `godhand-0.26-m25-native.apk` — 99,787,276 bytes, md5
  `2d89ca5caf99620f9cf06e685e33790c`
- `godhand-0.26-m25-native.zip` — 24,206,311 bytes, md5
  `46ce3ae9f731870833d6e6d7cf1e574d` (contains the APK alone, as
  before)
- `libgodhand-0.26-m25-native.so` — the shipped library, kept beside
  the APKs as with previous stages
- Packaging log: `native-m25-package.log`

## Test protocol for the owner

Install over pathb8, change nothing, play into a few fight rounds,
send the boot log as before. The first `[BOOT]` settings lines are
the renderer proof; the fight `[frame-split]` windows are the feel
proof. Both are his to judge.
