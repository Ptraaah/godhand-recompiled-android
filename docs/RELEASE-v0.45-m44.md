# v0.45-m44 — Mali black-screen hotfix

Release page: https://github.com/Ptraaah/godhand-recompiled-android/releases/tag/v0.45-m44-gsfallback
(pre-release, tag v0.45-m44-gsfallback). These are the release notes as published.

God Hand, recompiled to run natively on Android. Not an emulator: the
game's code is translated ahead of time into native ARM code, and the app
around it is built for phones from the start.

You bring your own legally owned copy of the game (US version,
SLUS-21503). There is no game data in this download, and there never
will be.

## What is new in this build

- A fix for the first public bug report: on phones with a MediaTek
  (Mali GPU) chip — reported on a Dimensity 8050 — the game started,
  the sound played, and the screen stayed black forever. The main
  Vulkan renderer could not open on that GPU, and the app had no
  backup plan: it drew nothing, and kept drawing nothing.
- Now it has one. If the main renderer cannot start, the game switches
  itself to the built-in renderer in the same session, a picture
  appears, and the game plays. The fallback renderer is the CPU one,
  so where it fires, expect it to be slower than the main renderer —
  a playable picture instead of none.
- On phones where the main renderer starts fine (Snapdragon and
  Adreno included), nothing changes: the fallback only ever fires on
  a real failure to open.
- The boot log now names the GPU it found, one line at every start,
  so a bug report from any phone says which graphics hardware it ran
  on without a follow-up question.
- Everything else is the m42 build: the three control styles, Player
  mode with automatic performance, the auto-dash, the proper START
  button, widescreen on and 1x resolution as the shipped defaults. The
  app shell also carries the m43 test build's one-line presentation
  change; nothing else about the app changed.

## Honest state

The fallback is verified by design and careful code reading, and the
build is clean — but it has not yet been confirmed on a real MediaTek
phone, because there is none on this desk. That confirmation is
exactly what this release is for.

If you have a MediaTek / Dimensity phone, please try this build and
say what happens. If a picture appears where m42 gave you a black
screen, that is the fix working. Either way, the most useful thing
you can send is your boot log: switch to Developer mode in the
settings, start the game once, then use Share boot log in the
launcher and attach the file to an issue, along with your phone
model.

Playable, still unstable, still a pre-release — busy areas still drop
frames and the sound is about 80% of the way there, same as m42.

## Install

1. Download godhand-0.45-m44-gsfallback.zip (35,570,502 bytes, md5
   635e06df86de8535de39f3a14c7dde7b) and extract the APK inside.
2. Install it — it installs over m42 (and m39/m40/m41) if you already
   have one of those.
3. Open the app, pick your game ISO, play.

## Help wanted

This is a one-person project and it is open. Testing on phones other
than the one POCO F5 everything is measured on is the front line
right now — MediaTek phones above all, after this bug. Performance
and audio tuning, control style feedback and later-stage playtesting
all help too. See CONTRIBUTING.md for where help is needed most.

by ptraaa
