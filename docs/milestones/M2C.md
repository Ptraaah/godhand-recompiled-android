# God Hand Android — Milestone 2c: the native side gets its voice (0.3-m2c)

Date: 2026-11-02. Follows M2B.md. Trigger: the owner's on-device test of
0.2-m2b on his POCO F5 (Android 15 / HyperOS 2) — two runs, identical
ladder:

```
[00:20:15] launcher opened (0.2-m2b)
[00:20:19] iso picker opened
[00:20:35] iso picked and stored
[00:20:42] start pressed, iso=set
[00:20:42] GameActivity launch requested
[00:20:42] GameActivity onCreate entered - preparing ISO before super.onCreate()
[00:20:42] iso fd opened: /proc/self/fd/186
[00:20:42] calling super.onCreate() - NativeActivity loads libgodhand.so now
[00:20:42] super.onCreate() returned - libgodhand.so loaded, native thread starting
... then nothing native. Second run 24 s later identical (fd/192).
```

The whole Java ladder works in 2b. The trail ends exactly where the
native side should start speaking: no "native: JNI_OnLoad" line, no
main() banner. The two missing lines have ONE root cause, proven below,
plus a second-order effect that explains the banner.

## 1. Root cause of the missing JNI_OnLoad line: it was never called

M2B.md §3 states the premise the 2b glue was built on: "JNI_OnLoad —
which runs the moment NativeActivity loads the library". That premise
is wrong, and it is the bug.

How android.app.NativeActivity actually loads the app library: the
framework's native code (android_app_NativeActivity.cpp) dlopen()s the
library named by the android.app.lib_name meta-data and dlsym()s
ANativeActivity_onCreate. A plain dlopen does NOT invoke JNI_OnLoad.
JNI_OnLoad is called only when a library is loaded through the Java
runtime — Runtime.loadLibrary / System.loadLibrary, whose native path
(jniLoadNativeLibrary in libnativehelper) runs JNI_OnLoad after the
dlopen. Our shell never called loadLibrary for "godhand" (grep of the
2b shell: none), so on the phone JNI_OnLoad simply never executed.

Evidence this is the right diagnosis, not a theory:

- The shipped 0.2-m2b libgodhand.so EXPORTS JNI_OnLoad (llvm-nm -D:
  `T JNI_OnLoad`, alongside `T ANativeActivity_onCreate` and `T main`)
  and contains the exact log string ("native: JNI_OnLoad -
  libgodhand.so loaded" found by strings). The code was shipped; it
  just never ran.
- The Java ladder proves the library DID load and the native activity
  DID start ("super.onCreate() returned - libgodhand.so loaded,
  native thread starting") — the only step in the chain that produces
  no line is precisely the one whose trigger (a Java-side load) never
  happened.
- The suspect named in the brief — a method-vs-field mismatch in the
  glue — does NOT hold up against the source: IsoHolder declares BOTH
  a public static String field `logDir` AND an accessor method
  `logDir()` (legal in Java; separate namespaces), and the glue's
  append reads the FIELD via GetStaticFieldID while the accessor
  methods are what GetStaticMethodID caches for main(). Both exist;
  nothing mismatched. The lookup code was fine; its caller never ran.

## 2. Root cause of the missing main() banner: a downstream symptom

The other named suspect — raylib's android_main waiting for
APP_CMD_INIT_WINDOW before calling main() — is refuted by the vendored
source. deps-src/raylib/src/platforms/rcore_android.c (raylib 5.5):

```c
void android_main(struct android_app *app)
{
    char arg0[] = "raylib";
    platform.app = app;
    (void)main(1, (char *[]) { arg0, NULL });   // called immediately
    ...
}
```

main() is invoked at once, on the native thread the standard
android_native_app_glue spawns from ANativeActivity_onCreate. The
INIT_WINDOW wait exists, but inside raylib's InitWindow — which main()
only reaches AFTER its own first lines. So a window problem cannot
explain a missing banner.

What can, completely: main()'s first act is
callStaticString(g_filesDir / g_logDir / g_isoFdPath), and that helper
early-returns an empty string when g_vm, g_isoHolder or the method ID
is null — and ALL of those are initialised only inside JNI_OnLoad.
With §1 in effect, main() most likely DID run on the phone, but every
lookup came back empty, so it skipped its only file write (the banner
is behind `if (!logDir.empty())`), skipped the chdir, passed NO --iso
argument, and never dup2'd stdout/stderr onto the log. The port then
ran disc-less — the same silent "no disc image found" exit the desktop
build performs — with all of its output going to logcat, after which
android_main calls ANativeActivity_finish and the activity closes.
That matches the owner's experience (a black game screen he backed
out of, then a second START 24 s later) and leaves no other step
unexplained.

## 3. What 0.3-m2c changes

Shell (android/shell/src/dev/ptraaa/godhand/GameActivity.java):

- After IsoHolder's fields are set and the ISO fd is opened, and
  BEFORE super.onCreate(), GameActivity now calls
  System.loadLibrary("godhand"), bracketed by BootLog lines
  ("loading libgodhand.so via System.loadLibrary so JNI_OnLoad runs"
  / "System.loadLibrary(godhand) returned - JNI_OnLoad has run" /
  a FAILED line with stack trace). This runs JNI_OnLoad through the
  VM; super.onCreate()'s own dlopen then finds the library already
  loaded and proceeds to ANativeActivity_onCreate as before.

Glue (android/glue/godhand_android_main.cpp):

- A new bootLogLine() helper appends a line with zero dependencies:
  raw open/write (O_APPEND) to the resolved log path if JNI already
  provided one, else the literal fallback
  /sdcard/Android/data/dev.ptraaa.godhand/files/godhand-boot.log
  (exactly what getExternalFilesDir returns for this package), and the
  same line always goes to logcat (tag "godhand-boot"). No JNI, no
  stdio buffering, nothing that can silently no-op together with
  something else.
- JNI_OnLoad now factors its IsoHolder caching into cacheJni(), with
  an exception clear after every lookup group (a failed lookup can no
  longer poison the next JNI call), and writes its line through
  bootLogLine() AFTER caching, so the line lands whether the class
  lookup worked (resolved path) or not (fallback path + logcat).
- main()'s FIRST statement is a bootLogLine() "native: main() entered
  (android_main thread, 0.3-m2c)" — before any JNI use. If the JavaVM
  is still null there, a second line says so explicitly
  ("JavaVM is null: JNI_OnLoad did not run before main()"), and main()
  then falls back to the literal paths (fallback log file for the
  banner + dup2 target, /data/data/dev.ptraaa.godhand/files as the
  working directory) instead of skipping everything.
- Banner bumped to "===== godhand android 0.3-m2c boot =====".

Unchanged: package dev.ptraaa.godhand, same debug keystore (script
signs with recomp-android/probe/debug.jks as before; apksigner v2+v3
verified), 120 s time limit, CPU rasteriser default, fd-based ISO,
no touch input yet.

## 4. Deliverable

- package/godhand-android.apk — 96,535,052 bytes, versionCode 3,
  versionName 0.3-m2c, minSdk 26 / targetSdk 35. Installs as an UPDATE
  over 0.2-m2b (same signer), preferences (picked ISO) kept.
- Embedded libgodhand.so is byte-identical to the rebuilt build-tree
  library (SHA-256
  5e4bfab59226d63480be4afbdd00c2e8a5caa8d2277044082d7299c50981801c,
  96,511,024 bytes); zipalign -c -p passes; the packaged dex contains
  the new loadLibrary ladder strings; the packaged .so contains the
  0.3-m2c native strings.
- package/godhand-android-0.2-m2b.apk.bak (+ .idsig) — previous build,
  kept. (0.1-m2a remains as godhand-android-0.1-m2a.apk.bak.)

## 5. How to read the next boot log

Lines in order; the first MISSING line names the step that died:

1. "[time] launcher opened (0.3-m2c)", "start pressed, iso=set" —
   the shell (unchanged, known good).
2. "[time] GameActivity onCreate entered...", the ISO fd line.
3. NEW: "[time] loading libgodhand.so via System.loadLibrary so
   JNI_OnLoad runs".
4. NEW: "native: JNI_OnLoad - libgodhand.so loaded" — written by the
   library itself DURING step 3. If step 3's "returned" line appears
   but this one does not, JNI_OnLoad ran but its append failed even
   via the fallback (near-impossible: logcat tag godhand-boot would
   still hold it).
5. NEW: "[time] System.loadLibrary(godhand) returned - JNI_OnLoad
   has run", then "calling super.onCreate()..." / "super.onCreate()
   returned...".
6. NEW: "native: main() entered (android_main thread, 0.3-m2c)" —
   the native thread reached main(). Absence now really would mean
   android_main never called main (the §2 refutation would need
   revisiting). A following "JavaVM is null" line would mean step 4
   silently didn't happen despite the Java lines.
7. "===== godhand android 0.3-m2c boot =====" + "iso: /proc/self/fd/N"
   — the JNI bridge delivered real values this time.
8. "native: calling godhand_port_main now" — everything after this
   is the port itself. Wherever ITS lines stop is the new frontier,
   and per this milestone's scope it is reported, not chased.

## 6. Owner test steps

1. Install package/godhand-android.apk — it updates the existing app
   in place (same signer); no uninstall, the picked ISO is kept.
2. Open "God Hand (recompiled)" — title now reads 0.3-m2c.
3. Tap "2. Start the game" (the ISO is already picked from last
   time; if it asks, pick the same God Hand (USA) ISO again).
4. Leave it up to ~2 minutes; the app closing itself when a boot
   ends is expected.
5. Reopen the app, tap "Share boot log", send the log back in chat.
   (The file also lives at
   Android/data/dev.ptraaa.godhand/files/godhand-boot.log.)
6. If the game screen shows anything new (a logo, text, a picture —
   or still black), that observation is worth sending too: with the
   log now covering every handover, what the screen did completes
   the picture.
