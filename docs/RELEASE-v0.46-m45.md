# v0.46-m45 — clear message when a disc image is refused

Release page: https://github.com/Ptraaah/godhand-recompiled-android/releases/tag/v0.46-m45-bootmsg
(pre-release, tag v0.46-m45-bootmsg). These are the release notes as published.

God Hand, recompiled to run natively on Android. Not an emulator: the
game's code is translated ahead of time into native ARM code, and the app
around it is built for phones from the start.

You bring your own legally owned copy of the game (US version,
SLUS-21503). There is no game data in this download, and there never
will be.

## What is new in this build

- Issue #3: a player picked a disc image, pressed start, and the game
  simply never started — no picture, no message, nothing to act on.
  The image was a modded one (an "Uncensor" patch), and the port had
  refused it, correctly: it is rebuilt from the original disc's code
  and checks that code before it boots. But the refusal only existed
  in the boot log, where no player would ever see it.
- Now the app says so on screen. When the game cannot start because
  the image is not the original release, a message explains why and
  what it needs: a clean dump of the original US disc (SLUS-21503).
  One OK button takes the player back to the launcher, where they can
  pick another image.
- The message covers the refusal cases: modded, patched or translated
  images, discs from other regions, files that are not the original
  US release, and files that cannot be read as a disc image at all.
  Any other failed start gets a plain could-not-start message that
  points at the boot log.
- The refusal itself has not changed in any way: same check, same
  rule — only the exact original SLUS-21503 executable boots. A boot
  that worked in m44 works identically here and never sees the
  message.
- Everything else is the m44 build: the MediaTek (Mali GPU)
  black-screen fallback, the GPU name in the boot log, the three
  control styles, Player mode with automatic performance, the
  auto-dash, the proper START button, widescreen on and 1x resolution
  as the shipped defaults.

## Honest state

The build is clean, and the new code path is verified in the shipped
artifacts — the message texts and the record that carries the refusal
reason are in the APK — but it has not yet been run on a real phone.
The proof is simple, and it is exactly what this release asks for:
install it, pick an image the port refuses (a modded one, for
example), and the message should appear within about a second of
pressing play, saying the image is not the original release. A normal
image must boot and play exactly as in m44, with no message.

If you try it, please say what happened — on issue #3 if that was
your report, or in a new issue with your boot log attached: switch to
Developer mode in the settings, start the game once, then use Share
boot log in the launcher and attach the file, along with your phone
model.

Playable, still unstable, still a pre-release — busy areas still drop
frames and the sound is about 80% of the way there, same as m44.

## Install

1. Download godhand-0.46-m45-bootmsg.zip (35,571,469 bytes, md5
   fe277d9e836fda0a9dbc903a2ab9016e) and extract the APK inside.
2. Install it — it installs over m44 (and m42 and the earlier builds)
   if you already have one of those.
3. Open the app, pick your game ISO, play.

## Help wanted

This is a one-person project and it is open. Testing on phones other
than the one POCO F5 everything is measured on is the front line
right now — MediaTek phones above all, after the m44 bug. Performance
and audio tuning, control style feedback and later-stage playtesting
all help too. See CONTRIBUTING.md for where help is needed most.

by ptraaa
