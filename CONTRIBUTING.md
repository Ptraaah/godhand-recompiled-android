# Contributing

Thanks for stopping by. This project is one person building in the open, and
help is genuinely welcome — the game is playable now, but it is still
unstable, and more hands and more phones will get it further than I can
alone.

## Get the build

Grab the newest zip from the
[releases page](https://github.com/Ptraaah/godhand-recompiled-android/releases),
extract the APK, and install it. It installs over older builds, so updating
is just installing the new APK on top.

You need your own legally owned copy of the game: US version, SLUS-21503.
No game data is distributed here, ever. Open the app, pick your ISO, and it
plays.

## Test and report

The single most useful thing you can send is a **boot log from a bad
moment**:

1. In the launcher settings, switch Mode to **Developer** (Player mode
   hides the log on purpose).
2. Play until you hit the problem — a laggy crowd fight, choppy sound, a
   crash, whatever it is.
3. Back in the launcher, use **Share boot log** and attach that file to
   your issue, along with your phone model and what was happening on
   screen.

Reports with a log get fixed. Reports without one get guessed at.

## Where help is needed most

- **Performance and audio tuning.** Crowds still drop frames on the
  reference phone, and the sound is close but not all the way there. If
  you know PS2 graphics, Vulkan, or Android audio, that is the front
  line. The milestone reports in `docs/milestones/` (especially the m39
  core report) show what has been tried and measured, so nobody repeats a
  dead end.
- **Device testing on other phones.** Everything so far is measured on one
  POCO F5 (Snapdragon 7+ Gen 2, Adreno 725). Other chips, other Android
  versions, other screen sizes: all uncharted. Install it, play stage 1,
  send the log.
- **Control scheme feedback.** There are three selectable control styles
  (Brawler Clean, Gesture Evade, Radial Pro). Try them, say which fits
  your thumbs and what feels wrong — that feedback shapes the defaults.
- **Playthrough testing.** Upstream only verifies this game through
  stage 3. Stages 4 to the end need people actually playing them and
  reporting where it breaks.

## Working on the code

- The repo layout: the Android shell and the Path B bridge sources are
  here, along with all documentation. The recompiled game code itself is
  never committed — it is generated from your own disc, and `.gitignore`
  enforces that.
- Read `docs/PROGRESS.md` before changing behaviour. This project runs on
  measurements, not guesses: perf changes are expected to come with
  before/after numbers from a real device, the way the milestone reports
  do it.
- Keep commits small and say what the device showed, not just what the
  code does.

## Ground rules

- No game data in issues or pull requests either — no disc images, no
  extracted files, no screenshots of copyrighted art beyond fair gameplay
  captures for a bug report.
- GPL-3.0, same as the project.
- Be decent to each other. That is the whole code of conduct.
