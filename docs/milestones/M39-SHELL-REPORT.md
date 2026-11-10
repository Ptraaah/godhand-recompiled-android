# M39 SHELL REPORT — native buttons, ready-to-play redesign, app icon (0.40-m39, versionCode 47)

Scope: SHELL ONLY. No performance or audio changes to the native core;
libgodhand.so / libpcsx2gs.so / libshaderc_shared.so are byte-identical
to m38 (arbitration in §5). This build answers Pablo's three m38
verdicts on the shell: (1) replace the custom-drawn buttons with native
Android buttons arranged by us, (2) redesign the whole appearance into
a ready-to-play app, (3) add an app logo / launcher icon.

Candidate: `godhand-0.40-m39-nativebuttons-CANDIDATE.apk`
(140,482,367 B, md5 d5f9814f2278e55f188bfb2a218d2282), zip alongside:
`godhand-0.40-m39-nativebuttons.zip` (35,863,245 B, md5
904a380a185e4f1936a84e034ddcbecb). NOT delivered to Pablo yet — his
device test (feel of the native buttons, layout at POCO F5 size, icon
on his launcher) is the gate. He also still owes the m38 performance
verdict's log for the separate performance/audio lane; this build does
not change game speed or sound at all.

Workbench: `nativebuttons-build-work/` (icon generator + master PNG in
`icon-src/`, javac/d8 logs, `package_m39.py`, stage/).

---

## 1. In-game overlay — native Android buttons (TouchOverlay rewritten)

What was wrong: the old overlay drew its own Canvas `PadButtonView` /
`StickView` controls, had NO D-pad buttons at all, and no L3/R3 touch
buttons.

What it is now (`godhand-recomp/android/shell/src/dev/ptraaa/godhand/TouchOverlay.java`):

- Every button is a real `android.widget.Button`: framework touch
  handling, real pressed state via `StateListDrawable` (dark
  translucent fill + light rim normally; the product accent orange
  fills the button while pressed), haptic tick
  (`performHapticFeedback(VIRTUAL_KEY)`) on press. Face labels keep
  PlayStation colours (△ green, ○ red, × blue, □ pink).
- The two sticks stay custom views (a stick cannot be a Button),
  recoloured to the same language: dark fill, light rim (amber in edit
  mode), accent-orange knob centre.
- EDIT/DONE and RESET are native Buttons too (accent pill / plain
  pill, click-driven; they never write to TouchPad, as before).
- Wiring preserved: buttons → `TouchPad.setTouchControl(controlId, …)`,
  sticks → `setLeftStick` / `setRightStick`; edit mode still drags any
  control, persists `lay_<id>_x/y`, restyles controls with an amber
  rim, and gates game input via `TouchPad.setEditing`. Opacity and
  button-size settings from the launcher apply exactly as before.
- `PadMapping.TOUCH_CONTROLS` extended with the six new controls
  (`btn_dpad_up/down/left/right` → DPAD_*, `btn_l3` → L3, `btn_r3` →
  R3), so they are remappable in the launcher like every other touch
  button, and the launcher's reset-layout list covers them too.
  Note: the left stick still ALSO derives D-pad presses past 0.55 tilt
  (pre-existing TouchPad behaviour, unchanged) — the new D-pad buttons
  are the explicit path.

### Default layout map (anchors; sizes before the owner's size scale)

| Control | Size (dp) | Anchor |
|---|---|---|
| Left stick (move) | 136×136 | bottom-left, 24 from left, 36 from bottom |
| D-pad ▲ | 44×44 | above left stick: left 82, bottom 284 |
| D-pad ◀ / ▶ | 44×44 | left 38 / 126, bottom 240 |
| D-pad ▼ | 44×44 | left 82, bottom 196 |
| L3 pill | 50×34 | bottom-left, left 170, bottom 50 |
| Right stick (dodge) | 108×108 | bottom-right area, 248 from right, 40 from bottom |
| R3 pill | 50×34 | bottom-right area, 366 from right, bottom 50 |
| △ Triangle | 64×64 | face diamond top: 102 from right, 160 from bottom |
| ○ Circle | 64×64 | diamond right: 28 from right, 96 from bottom |
| × Cross | 64×64 | diamond bottom: 102 from right, 32 from bottom |
| □ Square | 64×64 | diamond left: 176 from right, 96 from bottom |
| L1 / L2 | 68×44 | top-left edge, left 24 / 100, top 14 |
| R1 / R2 | 68×44 | top-right edge, right 24 / 100, top 14 |
| SELECT / START | 68×36 | top centre, centre−78 / centre+10, top 14 |
| EDIT/DONE | 64×36 | top centre, centre+94, top 14 |
| RESET (edit mode only) | 76×36 | top centre, centre+166, top 14 |
| FPS label | wrap | top centre, top margin 58 |

## 2. Launcher — ready-to-play redesign (LauncherActivity)

Same activity, same preference keys, same copy/pick/launch/remap
behaviour underneath; only the visual structure changed. Native views
only, explicit colours on every view (the m2a/m2b invisible-views
lesson), page still scrolls, launcher portrait / game landscape.

Design language (shared with the overlay and the icon): near-black
background rgb(13,14,18), dark panel cards rgb(27,28,36) with 14dp
rounded corners, ONE accent — orange rgb(255,122,26) — big touch
targets, left-aligned row buttons rgb(38,40,48).

Sections in order:

1. **Hero header** — the app icon (the same mipmap asset the launcher
   icon uses, loaded by resource id), eyebrow "PTRAAA - RECOMPILED",
   big title "GOD HAND", subtitle "Ready to play on your phone",
   "Version 0.40-m39".
2. **GAME STATUS card** — the existing status line, now a bordered
   card: no ISO / picked / copying (the copy thread still writes its
   progress text here) / ready to play.
3. **Primary action — one big accent PLAY button** (72dp, bold 21sp).
   Label follows the ISO state via `updatePlayButton()` (refreshed
   from `refreshUi`/`refreshLogView`, which the copy callbacks already
   call): no ISO → "Pick an ISO to play"; ISO picked, no copy →
   "Copy ISO and play"; copying → "Copying ISO - please wait" (button
   disabled by the existing copy flow); copy ready → "PLAY". All four
   labels run the unchanged `onStartPressed()` flow (pick if needed →
   copy if needed → launch).
4. **Secondary** — "Pick God Hand (USA) ISO" (54dp, plain).
5. **PERFORMANCE card** — Internal resolution, Thread placement, Disc
   prefetch, GPU renderer rows.
6. **DISPLAY AND BUTTONS card** — FPS counter, VSync, Widescreen 16:9,
   Button opacity, Button size rows.
7. **CONTROLS - BUTTON LAYOUT card** — Reset button layout + the EDIT
   hint text.
8. **REMAP TOUCH BUTTONS card** and **REMAP GAMEPAD BUTTONS card** —
   the same cycle rows as before (touch card now also lists the new
   D-pad and L3/R3 rows), visually below settings; "Reset mapping"
   secondary button under them.
9. **BOOT LOG - DEVELOPER DETAIL card** (bottom, intentionally quiet)
   — Share boot log and Reload boot log as small half-width secondary
   buttons, log pane under them (220dp, own scroll).

Visible version strings bumped 0.19-m18 → 0.40-m39 in LauncherActivity,
GameActivity and TouchOverlay log lines.

## 3. Icon

Original logo, drawn programmatically — no game box art, logos or
copyrighted artwork used or traced. Generator:
`nativebuttons-build-work/icon-src/draw_icon.py` (PIL, supersampled
4×), master at `icon-src/ic_launcher_master_864.png`.

Motif: a bold front-facing FIST in flat primitives — four curled
fingers (short rounded bumps with dark knuckle gaps/creases), the
thumb wrapped across the front as a thick capsule in a deeper shade of
the accent, dark cuff band at the wrist — vertical accent-orange
gradient (255,148,48 → 244,96,8) on a near-black (16,17,22) ground
with a quiet darker-orange halo ring. First draft read as an open
palm; the shipped version shortens the fingers and adds the wrapped
thumb so it reads as a fist at launcher size (verified visually from
the master PNG).

Files added (in the shell tree, `godhand-recomp/android/shell/res/`):
`mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi/ic_launcher.png` at
48/72/96/144/192 px, plus `ic_launcher_round.png` (circular-masked
variant) at the same densities. Manifest wires
`android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"`. The redesigned
launcher hero shows the same asset, so icon, launcher and overlay read
as one product.

## 4. Build proof

Dex (same javac/d8 steps as build-apk.sh; toolchain build-tools
36.0.0, android-36 jar, toolchain JDK):

```
find shell/src -name '*.java' > sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp android.jar -d classes @sources.txt
  -> exit 0 (1 warning: bootstrap class path with -source 8; deprecation
     note for LauncherActivity, pre-existing)
d8 --lib android.jar --output dex <classes>
  -> classes.dex 65,564 B, md5 b0190d757db2cb15c1783e624a79917c
     (m38's dex was 61,456 B)
```

Dex content check: strings include `btn_dpad_up/down/left/right`,
`btn_l3`, `btn_r3`, `0.40-m39`, `ic_launcher`.

Packaging (`nativebuttons-build-work/package_m39.py`): the m36–m38
AXML string-patch pattern CANNOT add a resource — m38's
resources.arsc is 40 bytes (empty). So m39 builds the manifest +
resources properly with aapt2 (the working alternative the brief
allowed): `aapt2 compile --dir shell/res`, `aapt2 link` with the
shell's text AndroidManifest.xml (versionCode 47, versionName
0.40-m39, icon/roundIcon refs) → base APK; stage = base entries + new
classes.dex + m38's three .so; stored zip, `zipalign -p 4`,
`apksigner sign` with the same probe debug.jks as m38, verify OK.

Verification of the final candidate:

- `aapt2 dump badging`: `package: name='dev.ptraaa.godhand'
  versionCode='47' versionName='0.40-m39'`, minSdk 26, targetSdk 35,
  `application-icon-160/240/320/480: 'res/mipmap-*/ic_launcher.png'` —
  the icon resolves at every density.
- `apksigner verify`: OK; signer certificate SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e` —
  IDENTICAL to m38's, so it installs over m38.
- `zipalign -c -p 4`: OK.

## 5. Packaging arbitration vs m38 (per-entry content hashes)

| Entry | Verdict |
|---|---|
| lib/arm64-v8a/libgodhand.so (101,483,384 B) | SAME (md5 2bcd4ed0…) |
| lib/arm64-v8a/libpcsx2gs.so (28,791,224 B) | SAME (md5 0fc367db…) |
| lib/arm64-v8a/libshaderc_shared.so (10,007,448 B) | SAME (md5 2793f5b6…) |
| classes.dex 61,456 → 65,564 B | DIFFERS (the m39 shell, intended) |
| AndroidManifest.xml 2,460 → 2,528 B | DIFFERS (version 47/0.40-m39 + icon refs, intended) |
| resources.arsc 40 → 1,544 B | DIFFERS (real resource table for the icon, intended) |
| res/mipmap-*/ic_launcher.png + ic_launcher_round.png (10 entries) | ADDED (the icon) |
| META-INF/* | regenerated by apksigner, same key |

Nothing else differs; no entry was removed.

## 6. Risks / open points

- **Multi-touch on native Buttons is host-unproven here.** Android
  dispatches each pointer to the view it lands on, so simultaneous
  presses on different Buttons should behave like the old custom
  views, but only Pablo's device test proves it (e.g. holding the
  stick + tapping face buttons + a shoulder at once). If a button ever
  misses an UP (finger slides off), ACTION_CANCEL also releases it in
  the listener.
- D-pad default position sits above the left stick; on smaller
  heights it could crowd the L1/L2 row — it is draggable in EDIT mode
  and the layout is persisted, same as every other control.
- The aapt2-linked manifest is regenerated from the shell's text
  manifest rather than byte-patched from m38's; badging shows the same
  package/sdk/label/activities, and only version + icon attributes
  were changed in the source manifest.
- Release stays ON HOLD per Pablo, as before. Performance and audio
  are untouched by this build — still-laggy on m38 stands until the
  separate lane's device log comes back.
