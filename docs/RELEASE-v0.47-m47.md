# v0.47-m47 — our native engine becomes the default

Release page: https://github.com/Ptraaah/godhand-recompiled-android/releases/tag/v0.47-m47-nativeflip
(pre-release, tag v0.47-m47-nativeflip). These are the release notes as published.

God Hand, recompiled to run natively on Android. Not an emulator: the
game's code is translated ahead of time into native ARM code, and the app
around it is built for phones from the start.

You bring your own legally owned copy of the game (US version,
SLUS-21503). There is no game data in this download, and there never
will be.

## What is new in this build

- The default graphics engine changes. This app has always carried two
  renderers: the PCSX2 graphics core bridged onto Vulkan (Path B), and
  our own native renderer — the ARUS GPU backend, written here for
  mobile chips. From m24 through m45 the bridge was the default because
  it measured faster on the phone, and the native renderer sat behind
  it as the spare — it is what m44's automatic fallback lands on when
  the bridge cannot open.
- There was a catch in that arrangement: on a fresh install, picking
  the native renderer quietly gave you the CPU backend, because the
  GPU renderer toggle defaulted to off. The fast native path was
  effectively hidden.
- m47 flips both defaults, and nothing else. The built-in renderer
  seed now resolves to native instead of the bridge, and the GPU
  renderer toggle now defaults to on — a saved choice is still read
  back exactly as stored, and turning it off still gives the CPU
  backend, as an explicit choice. A fresh install now boots our own
  GPU renderer: the first native-default build since m25, with all
  four ARUS stages (upload dedup, 2x2 block shading, async readbacks,
  bounded queue) live on the main path for the first time since m24.
- The bridge has not gone anywhere. It stays in the package as the
  selectable fallback — set the renderer to pcsx2 in the settings
  file and the game runs on it exactly as in m45 — and m44's
  automatic switch to the built-in renderer still stands behind a
  bridge that cannot open.
- Why reverse a measured win? The bridge's ceiling is the wall the
  recent builds kept meeting: it is a desktop-class graphics core,
  and on some mobile GPUs it does not open at all (the m44 bug). The
  native renderer is ours — every bottleneck in it is a bottleneck we
  can open up and fix, on the hardware phones actually have. That
  programme is stage 2 of this project, scoped in full in
  docs/milestones/STAGE2-NATIVE-MAINPATH-SCOPE.md, and m47 is its
  first build.
- What did not change: the game code, the m39 audio work, the three
  control styles, Player and Developer modes, the START button, the
  shipped defaults (widescreen on, resolution 1x), the disc check
  and m45's on-screen message when an image is refused, and m44's
  automatic fallback. This is a one-variable build on purpose.

## Honest state

This build is a foundation step, not a promised speed jump. Flipping
the default is how the campaign gets its true baseline: the native
path's real numbers on a real phone, in a real fight, from this
build — the figures the next rounds plan against. The configuration
it boots is the one the 0.23/0.24 native sessions ran, so it is a
known-quantity path, but m47 itself has not yet been run on a device.

So this release asks for something specific. If you try it, please
send a boot log from an actual fight: install it, switch to Developer
mode in the settings, play one fight of two to three minutes without
leaving the app, then use Share boot log in the launcher and attach
the file to a new issue, along with your phone model. Every GPU
counts — Adreno, Mali, anything else — because the log opens with
which engine and GPU the session ran on, and the fight windows in it
are the baseline the stage-2 campaign is measured against. A report
that it felt faster or slower is welcome too, but the log is the
gold.

Playable, still unstable, still a pre-release. Whether the native
numbers come back better or worse than the bridge's is exactly what
this build exists to find out.

## Install

1. Download godhand-0.47-m47-nativeflip.zip (35,570,987 bytes, md5
   65811807d6b70dfc26b7888d62d288c0) and extract the APK inside.
2. Install it — it installs over m45 (and m44 and the earlier builds)
   if you already have one of those.
3. Open the app, pick your game ISO, play.

## Help wanted

This is a one-person project and it is open. Testing on phones other
than the one POCO F5 everything is measured on is the front line
right now — and with the native engine as the default, boot logs from
other GPUs are the single most useful thing anyone can send.
Performance and audio tuning, control style feedback and later-stage
playtesting all help too. See CONTRIBUTING.md for where help is
needed most.

by ptraaa
