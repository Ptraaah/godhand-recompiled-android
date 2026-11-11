# Roadmap

Where this port starts from, and the order the work goes in. Kept short and
factual; statuses change as the milestones land.

## Starting point

- ran-j/PS2Recomp is the toolchain: it finds functions in a PS2 program and
  translates the code into C++ ahead of time.
- The playable runtime comes from the LightVelox sotc-port lineage (full
  EE/IOP/VU support, a real graphics layer, SPU2 sound), carried into
  LasVola/godhand-recomp along with God Hand's recompilation config:
  10,554 functions in the main program and 81 SNR2 overlay modules.
- In the open lineage, Android is unclaimed — the existing trees target
  Windows desktops. The graphics layer is the hard kernel: neither open
  renderer (the runtime's own OpenGL GS, the optional Direct3D 11 PCSX2 GS)
  runs on Android as shipped.

## Milestone 1 — translation proof

Prove the whole game translates and builds, on a Linux host first.

- The recompiler (ps2_recomp) builds and runs on Linux with zero source
  patches.
- The runtime and game layer compile and link into one whole program
  (an 88 MB host binary that boots its banner and refuses cleanly when no
  disc is present). Only two small fixes were needed: a guarded SSE4.1
  compile flag for non-MSVC compilers, and one missing <cstddef> include.
- Generation inputs from the owner's own disc are pinned down: the 3.6 MB
  boot program (SLUS_215.03, SHA-256 verified), GODHAND.FST, and the 81
  overlay modules — about 9 MB total, not the 1.57 GB disc image.
- Status: done. The full compile of the ~10k generated game sources with
  the real modules in landed whole, and the linked program boots on the
  phone. Details in docs/PROGRESS.md and the M1A-M2C reports.

## Milestone 2 — Android shell

Make it an app a phone can run.

- The same code compiled for arm64 with the NDK; the WIN32 gate on SDL3
  lifted where needed.
- Android shell written for phones: activity, touch controls (gamepad
  welcome too), picking the disc image from phone storage, audio through
  Android's own path.
- Settings that persist and stay reachable — the sealed-build behaviour
  (choices silently resetting on every start) is the specific thing this
  shell exists to not do.
- Status: done, and past the target. The app boots through the intro to
  the title and menus on a real phone (POCO F5), with the settings page,
  layout editor and remapping all live. Playability is now a renderer
  question, which is Milestone 3.

## Milestone 3 — renderer, tuned per stage

The graphics chip is translated at runtime by design; performance lives
or dies here.

- Paths taken, plural, because each was measured on the phone before
  being believed: the runtime's own GS, brought to Android as an EGL
  OpenGL ES 3.2 backend (title and menus at a real 60 FPS); ARUS, the
  adaptive pipeline on top of it (upload dedup, 2x2 block shading, async
  readbacks, bounded queue); and Path B — the PCSX2 graphics core bridged
  onto Vulkan/Android. Path B is the one that ships: it won on the
  device, not on paper. A native-renderer experiment (m25) was measured,
  found worse, and reverted.
- Where it stands: done as the main line. The game plays on the
  reference phone (Snapdragon 7+ Gen 2, Adreno 725). What remains is
  polish, measured the same way as everything before it: crowd
  performance and sound, build by build (the call-count era, m28-m38,
  then the research-backed audio/GS fixes in m39). One deliberate
  reopening, recorded under Milestone 4: stage 2 brings the native
  renderer back as the main path, measured the same way, because the
  bridge's ceiling is now the wall. Path B remains the fallback and
  the comparison baseline.
- Testing debt stated openly: upstream verifies the game only through
  stage 3, on any platform. Stages 4 to the end get tested here, on the
  phone, as part of this milestone.

## Milestone 4 — a native Android app, not an emulator shell

The last emulator habits live in the app's own surfaces, and they go.

- m39 (done): real native Android buttons instead of drawn ones, a
  redesigned ready-to-play launcher, an app icon of its own, plus the
  research-backed audio and graphics fixes. Device verdict: really
  good; sound about 80% of the way there, performance clearly better.
- m40 (done): controls redesigned for a phone instead of cloned from a
  PS2 pad — three phone-native styles (Brawler Clean, Gesture Evade,
  Radial Pro) built in as a player-selectable setting, alongside a
  Player mode / Developer mode switch (Player hides the technical
  surfaces; Developer shows the boot log and the knobs).
- m41 (done): one proper START button in every control style — Start is
  the cutscene skip, not a corner pill — and the shipped defaults set
  to the best known values (widescreen on, resolution 1x, the rest
  audited).
- m42 (done): Player mode runs the performance options automatically
  (thread placement and disc prefetch on at game start, stored toggles
  untouched), and a full forward push on the move stick dashes on its
  own — no double flick.
- m44 (done): hotfix for the first public bug report — a black screen
  on MediaTek (Mali GPU) phones, where the main Vulkan renderer cannot
  open. If that happens the game now switches itself to the built-in
  renderer in the same session so a picture appears, and the boot log
  names the GPU it found.
- m45 (done): the second public report (issue #3) — a modded disc
  image was refused and the player got a silent dead screen. The
  refusal is unchanged, but the app now says on screen why the game
  did not start and what image it needs (a clean dump of the
  original US disc, SLUS-21503).
- m47 (internal testing, stage 2 opened): in the test build, the
  default engine swaps. Our own native ARUS GPU renderer is the
  main path again — the first native-default build since m25 — and
  the PCSX2 bridge becomes the selectable fallback. The GPU
  renderer toggle now defaults to on, so a fresh install's native
  path is the GPU backend, not the CPU one. Stage 2, the
  native-main-path campaign, is the active programme from here: it
  is scoped in docs/milestones/STAGE2-NATIVE-MAINPATH-SCOPE.md,
  and m47 is its baseline build — defaults only, on purpose, so
  the next rounds plan against the native path's true device
  numbers. It is an internal build, not a public release: it was
  briefly published on 2026-11-12 and taken down the same day,
  because the native path is not stable enough to ship yet. The
  public build stays m45 until the campaign earns the swap on
  measurements.
- Released: v0.43-m42 went out on 2026-11-11 as the first playable
  public pre-release, and the project is open to contributors
  (CONTRIBUTING.md). Playable, still unstable, help wanted — sound and
  crowd performance are the standing fronts from here. v0.45-m44
  followed on 2026-11-12 as a hotfix for the MediaTek black screen.
  v0.46-m45 followed the same day, answering issue #3 with an
  on-screen message when a disc image is refused, and it is the
  current public build. Stage 2, the native-main-path campaign,
  opened the same day in internal testing (see the m47 line
  above); nothing from it is a public release yet.

## Standing rules

- No game data in this repository, ever: no disc images, no extracted
  files, no generated game code. Players supply their own US SLUS-21503
  disc, hash-verified.
- Everything GPL-3.0, source shipped with every release.
