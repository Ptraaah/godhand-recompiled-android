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

- Path taken: the runtime's own GS, brought to Android as an EGL
  OpenGL ES 3.2 backend with its shaders translated from desktop GLSL at
  load time. The CPU rasteriser path remains as the fallback and the
  measurement baseline. The Vulkan/PCSX2 route was not needed.
- Where it stands: title and menus run at a real 60 FPS on the GPU
  backend and the 3D world renders; the open work is in-game frame
  production under scene load (the guest completes ~1-3 new frames per
  second there today), being instrumented per scene on the reference
  phone, a Snapdragon 7+ Gen 2 (Adreno 725).
- Testing debt stated openly: upstream verifies the game only through
  stage 3, on any platform. Stages 4 to the end get tested here, on the
  phone, as part of this milestone.

## Standing rules

- No game data in this repository, ever: no disc images, no extracted
  files, no generated game code. Players supply their own US SLUS-21503
  disc, hash-verified.
- Everything GPL-3.0, source shipped with every release.
