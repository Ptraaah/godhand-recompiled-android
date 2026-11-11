

https://github.com/user-attachments/assets/2e8b4832-02c6-41e4-bd40-952ee78b58b8


# God Hand, recompiled for Android

This is an Android port of God Hand (the PS2 one, Clover Studio, 2006) built on top of the open-source God Hand recompilation work. Instead of emulating the console while the game runs, the game's code gets translated ahead of time into native code — here, native ARM for a phone. The shell around it is being written for Android from the start: touch controls, settings that stay where you put them, and a renderer chosen and tuned for mobile chips instead of carried over from a desktop build.

## Why

There are Android builds of this game floating around, and they do run. The problem is they are sealed. Settings reset themselves, the tuning knobs that exist in the code are not reachable, and when something lags there is nothing you can open up and fix. This one is the opposite bet: open source, every knob reachable, and if a specific scene is heavy it can be looked at and tuned for that scene. Slower to arrive, but nothing in it is a dead end.

## Status

Current build: 0.45-m44, a hotfix on top of the first playable public pre-release (v0.43-m42). It plays on a real phone — stated plainly, with the remaining distance stated just as plainly: it is playable, it is not stable yet, and both halves of that sentence are true.

- The full game code translates, links and boots on a POCO F5 from a real disc, and plays: fights, crowds, the works. Performance improved step by step across a long run of measured builds; sound is close — about 80% of the way there by my own ear — and both are still being pushed.
- The renderer that won is Path B: the PCSX2 graphics core, bridged onto Vulkan/Android. The home-grown GPU renderer and a later native-renderer experiment were both measured on the device and set aside; the bridge is what ships.
- m39 rebuilt the app side of things: real native Android buttons instead of drawn ones, a redesigned ready-to-play launcher, and an app icon of its own — plus research-backed audio and graphics fixes under the hood.
- m40 stopped copying a PS2 pad. The controls are now three phone-native styles — Brawler Clean, Gesture Evade, Radial Pro — and the player picks between them in the settings, because what fits one player's thumbs is not what fits another's. The same build added Player mode and Developer mode: Player is the clean game, Developer shows the boot log and the technical surfaces.
- m41 gave every control style one proper START button (that is the cutscene skip — it cannot be a tiny pill in a corner), and set the shipped defaults to the tuned values: widescreen on, resolution 1x.
- m42 makes Player mode run the performance options automatically — thread placement and disc prefetch are on for the player without opening a single toggle — and a full push forward on the move stick now dashes on its own, no double flick needed.
- m44 is a hotfix for the first public bug report: on MediaTek (Mali GPU) phones the game started and the sound played, but the screen stayed black — the main Vulkan renderer could not open on that GPU and the app had no backup plan. Now, if the main renderer cannot start, the game switches itself to the built-in renderer in the same session and a picture appears; and the boot log names the GPU it found, so a report from any phone says which hardware it ran on.

The full story with device screenshots and boot-log numbers lives in [docs/PROGRESS.md](docs/PROGRESS.md).

The detailed roadmap lives in [docs/ROADMAP.md](docs/ROADMAP.md).

## Downloads

Newest build: [v0.45-m44](https://github.com/Ptraaah/godhand-recompiled-android/releases/tag/v0.45-m44-gsfallback) on the releases page — a hotfix on top of the first playable public build (v0.43-m42), fixing a black screen on MediaTek (Mali GPU) phones. Download the zip, extract the APK, install it (it installs over m42 and the earlier builds), pick your own disc image, play. It is marked as a pre-release because that is what it is: playable today, still unstable in busy areas, audio still being improved.

The older v0.1.0-alpha (m33, with m27 as fallback) stays on the releases page for anyone who wants the earlier baseline. You still need your own disc (next section) — the downloads contain no game data.

## Contributors welcome

This is a one-person project and it is now open for help, plainly: the game plays, but it is still unstable, and I want it better than I can get it alone.

Help is needed most with performance and audio tuning, testing on phones other than the one POCO F5 everything is measured on, feedback on the three control styles, and playtesting the later stages nobody has verified yet. The single most useful contribution is a bug report with a boot log attached: switch to Developer mode in the settings, play until it breaks, then Share boot log from the launcher and attach the file to your issue.

Start with [CONTRIBUTING.md](CONTRIBUTING.md) — how to get the build, how to report so it gets fixed, and where the front lines are. Issues labelled `help wanted` and `good first issue` are the ones to grab.

## You need your own disc

No game data is distributed here, and none ever will be. There is no disc image, no extracted game files, and no generated game code in this repository — the .gitignore blocks them on purpose, not just by policy.

To build or run this you supply your own legally owned copy of God Hand:

- US version only, SLUS-21503. The port checks the disc's boot file by SHA-256 and refuses anything else.
- Generation only ever reads about 9 MB from the disc (the boot program and the overlay modules). The full disc image itself stays on your phone as runtime data.

## How it works, in short

- The game's main code is translated ahead of time into C++ by the PS2Recomp toolchain, then compiled to native ARM. Game logic runs as native code, full stop.
- The small IOP chip runs on an interpreter in this lineage — that is how the open runtime is built, and it is stated here instead of hidden.
- The graphics chip is translated at runtime by the renderer, which is true of every PS2 port anywhere; the work in milestone 3 is picking and tuning that renderer for phones.
- Honest debt, inherited from upstream: even on desktop, the game is only verified through stage 3. Stages 4 to the end are untested everywhere, and testing them is part of this project's job.

## Lineage and licence

This builds on LasVola's godhand-recomp and the PS2Recomp family (ran-j/PS2Recomp and the LightVelox sotc-port runtime, via Urban Reign's port), all GPL-3.0. This repository is GPL-3.0 too — see [LICENSE](LICENSE). Credit to everyone whose work this stands on; the goal here is only the Android part they had not claimed yet.

## Support

Everything here is free and stays free. If it ends up useful to you, support is open:

- Trakteer: https://trakteer.id/adel_putra2
- GitHub Sponsors: https://github.com/sponsors/Ptraaah

by ptraaa
