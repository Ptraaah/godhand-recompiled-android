# God Hand, recompiled for Android

This is an Android port of God Hand (the PS2 one, Clover Studio, 2006) built on top of the open-source God Hand recompilation work. Instead of emulating the console while the game runs, the game's code gets translated ahead of time into native code — here, native ARM for a phone. The shell around it is being written for Android from the start: touch controls, settings that stay where you put them, and a renderer chosen and tuned for mobile chips instead of carried over from a desktop build.

## Why

There are Android builds of this game floating around, and they do run. The problem is they are sealed. Settings reset themselves, the tuning knobs that exist in the code are not reachable, and when something lags there is nothing you can open up and fix. This one is the opposite bet: open source, every knob reachable, and if a specific scene is heavy it can be looked at and tuned for that scene. Slower to arrive, but nothing in it is a dead end.

## Status

Early, and stated plainly: there is nothing playable to download yet.

- The recompiler builds and runs, and the runtime + game layer compile and link as a whole program.
- Milestone 1 is the translation proof: the game's own code — 10,554 functions plus its 81 overlay modules, generated from a real disc — translates and builds whole. That full compile is the step in flight right now.
- Next is milestone 2: the Android shell (app, touch controls, storage, audio), aimed at a first boot on a real phone.

The detailed roadmap lives in [docs/ROADMAP.md](docs/ROADMAP.md).

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
