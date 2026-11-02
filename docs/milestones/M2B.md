# God Hand Android — Milestone 2b: hardened shell (0.2-m2b)

Date: 2026-11-01. Follows M2A.md. Trigger: the owner's on-device test of
0.1-m2a on his POCO F5 (Android 15 / HyperOS 2).

## 1. What the owner saw on 0.1-m2a

- A header reading "God Hand (recompiled)" (the app label), then only
  three buttons — "2. START THE GAME", "SHARE BOOT LOG", "RELOAD BOOT
  LOG" — and the line "(no boot log yet - start the game first)".
- The title text, the status line, and the "1. Pick your God Hand (USA)
  ISO" button never appeared (looked and tapped around: nothing).
- Pressing START produced nothing visible, and godhand-boot.log was
  never created.

## 2. Why — evidence, not guesses

The pick button was NOT missing from the shipped app. Checks run against
the actual 0.1-m2a APK in package/ (now kept as
godhand-android-0.1-m2a.apk.bak):

- The classes.dex inside the APK is byte-identical (SHA-256 def674b7…)
  to a fresh compile of the shell source quoted in the 2a work.
- dexdump of that dex shows LauncherActivity.onCreate building, in
  order: title TextView ("God Hand — recompiled (0.1-m2a)"), status
  TextView, Button "1. Pick your God Hand (USA) ISO" with its picker
  listener, then the three buttons the owner did see, then the log
  ScrollView — all addView()ed to the same LinearLayout before
  setContentView. Nothing in the code can skip exactly the first three
  siblings and keep the rest.
- The packaged manifest confirms the application theme was the legacy
  framework Theme.Material WITH an action bar (@0x01030224). What the
  owner read as a "header" was that action bar showing the app label.
  The visible buttons even rendered in ALL CAPS although the source
  strings are mixed case — Material's button style (textAllCaps) was
  driving the rendering, i.e. the theme, not the code, owned how this
  screen looked.
- Conclusion: the views were in the hierarchy; the top slice of the
  window — title + status + pick button, the first ~120 dp of content —
  was obscured in the status-bar/action-bar region on this ROM. The app
  targets SDK 35, where Android 15 enforces edge-to-edge drawing, and
  the legacy framework action-bar theme no longer insets content the
  way it did when this pattern was normal; HyperOS's own window
  handling sits on top of that. (The exact pixel accounting on his ROM
  cannot be reproduced from here; what is proven is that the failure
  was confined to the top-of-window region of an action-bar-themed
  activity, and 2b removes that whole class of failure instead of
  arguing with it — see §3.)
- No log on START: in 2a the ONLY writer of godhand-boot.log was the
  native glue, deep inside main() — reached only after GameActivity
  started, NativeActivity loaded libgodhand.so, and raylib's
  android_main thread ran. No ISO had ever been picked (the button was
  unreachable), START launched GameActivity disc-less, and the boot
  died somewhere before the glue's first fopen — the library load /
  NativeActivity init is the step with no other explanation left —
  while the Java side wrote nothing and caught nothing, so the death
  left zero evidence. That instrumentation hole is the real 2a bug;
  2b closes it (§3).

## 3. What 0.2-m2b changes

Shell (android/shell/, ours as before):

- NoActionBar: application theme is now Theme.Material.NoActionBar
  (packaged as @0x0103022e); the launcher's own title TextView is the
  only header.
- Every view has explicit colors set in code: near-black background,
  white title, amber status text on a dark panel (with padding and a
  48 dp minimum height), blue Pick button, green Start button, dark
  grey Share/Reload buttons, white button text with setAllCaps(false),
  every button 58 dp tall with rounded solid backgrounds (darker when
  pressed), log view light-grey monospace on black.
- The content pads itself for the system bars in code: a status-bar
  height fallback at build time, replaced by the exact window insets
  when they are delivered.
- New BootLog helper: appends timestamped lines to the same
  godhand-boot.log from Java. The launcher logs when it opens; START
  writes "start pressed, iso=set|unset" BEFORE anything else can fail;
  the GameActivity launch is wrapped in try/catch and any exception is
  appended to the log and shown on the status line.
- START with no ISO stored now opens the ISO picker first; picking one
  stores it and launches the game by itself. Button 1 still picks on
  its own. Picker failures are also logged and shown.
- GameActivity logs its ladder before/inside onCreate: activity
  entered → dirs set → ISO fd opened (with the /proc/self/fd path) or
  the open failure's stack trace → "calling super.onCreate()" → either
  "super.onCreate() THREW: <stack>" or "returned - libgodhand.so
  loaded".

Native glue (android/glue/godhand_android_main.cpp):

- JNI_OnLoad — which runs the moment NativeActivity loads the library,
  and can already read IsoHolder.logDir because GameActivity sets it
  before super.onCreate() — appends "native: JNI_OnLoad -
  libgodhand.so loaded" to the boot log.
- main() banner now reads "===== godhand android 0.2-m2b boot ====="
  and adds "native: main() entered (raylib android_main thread)"; a
  "native: calling godhand_port_main now" line marks the handover to
  the port, and a return line follows if the port ever returns.
- The library was rebuilt for this (the missing register_functions
  object was recompiled too, so the Android build tree is whole again
  and incremental builds are cheap). New libgodhand.so: 96,508,768
  bytes, SHA-256 617c0d2dc37c5bfaacd55c4d7942491c5efbf82746d1357198ec859e6a541b4e,
  ELF64 AArch64, all PT_LOAD aligned 0x4000, exports main /
  ANativeActivity_onCreate / JNI_OnLoad, 28,192 func_* symbols (same
  count as 2a). The 2a library is kept at libgodhand-0.1-m2a.so.bak.

Unchanged from M2A, as briefed: same package dev.ptraaa.godhand, same
debug keystore (signer SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e — so
this installs as an UPDATE over 0.1-m2a, no uninstall, preferences
kept), 120 s time limit, CPU rasteriser default (PS2X_GS_GPU=0),
fd-based ISO passing, no touch input yet.

## 4. Deliverable

- package/godhand-android.apk — 96,535,052 bytes, versionCode 2,
  versionName 0.2-m2b, minSdk 26 / targetSdk 35. apksigner v2+v3
  verified; zipalign -c -p passes; the embedded libgodhand.so is
  byte-identical to the rebuilt build-tree library (SHA-256 above);
  the packaged dex contains the new log strings ("start pressed",
  "GameActivity onCreate entered", "calling super.onCreate()").
- package/godhand-android-0.1-m2a.apk.bak — the previous build, kept.

## 5. How to read the next boot log

The lines arrive in this order; the first MISSING line names the step
that died:

1. "[time] launcher opened (0.2-m2b)" — the shell itself is alive.
2. "[time] start pressed, iso=unset|set" — the tap registered.
3. (iso=unset only) picker lines: "iso picker opened", then "iso
   picked and stored" or "iso pick cancelled or returned nothing".
4. "[time] GameActivity launch requested" — startActivity did not throw.
5. "[time] GameActivity onCreate entered..." then the ISO fd line.
6. "[time] calling super.onCreate()..." then either "super.onCreate()
   THREW: ..." (library load / native init failure, with stack) or
   "super.onCreate() returned - libgodhand.so loaded...".
7. "native: JNI_OnLoad - libgodhand.so loaded" (written during step 6).
8. "===== godhand android 0.2-m2b boot =====" + "native: main()
   entered..." + "iso: /proc/self/fd/N".
9. "native: calling godhand_port_main now" — everything after this is
   the port itself (disc mount, SYSTEM.CNF, boot ELF, and wherever it
   stops is the 2c starting point).

## 6. Owner test steps

1. Install package/godhand-android.apk — it updates the existing app
   in place (same signer); no uninstall needed.
2. Open "God Hand (recompiled)". Expect: dark screen, the title
   "God Hand — recompiled (0.2-m2b)", an amber status line, FOUR
   buttons (blue 1. Pick…, green 2. Start…, Share boot log, Reload
   boot log), and the log area at the bottom already showing a
   "launcher opened" line.
3. Either tap 1 and pick the same God Hand (USA) ISO as before, then
   tap 2 — or just tap 2 and pick the ISO when asked; the game then
   starts by itself.
4. Wait up to ~2 minutes. The app closing itself is expected when a
   boot ends (the port exits by design).
5. Reopen the app and tap "Share boot log", and send the log back in
   chat. The file also lives at
   Android/data/dev.ptraaa.godhand/files/godhand-boot.log.
6. If anything still looks wrong (a view missing, a button dead), say
   which step — the log now records each step separately, so one look
   at it says where it stopped.
