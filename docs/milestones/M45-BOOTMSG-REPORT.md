# M45 — the game says why it did not start (0.46-m45-bootmsg)

Field case: GitHub issue #3. A player picked a modded ISO ("God Hand
Uncensor v1.0"). The boot log (issue3/godhand-boot.log) shows the port
refusing it — correctly — and the player seeing nothing at all:

```
[BOOT] ERROR boot executable hash mismatch (3192ce89...); the recompiled code was built from SLUS-21503 1742f95b...
native: godhand_port_main returned 3
```

The activity then just sat there with the touch overlay over a dead
surface, so the player filed it as "game not opening". The refusal
itself is correct and is NOT changed by this build: only the exact
original SLUS-21503 executable may boot. What was missing is any
player-visible explanation. m45 adds exactly that.

## How the failure propagated before m45 (the trace)

1. The check: `src/godhand/game_disc.cpp` — `verifyDiscAndPrepare`
   mounts the image, checks SYSTEM.CNF and the disc file list, then
   SHA-256s the boot ELF (`SLUS_215.03`). On a mismatch (line ~132-138)
   it returns `DiscCheckResult{ok=false, error="boot executable hash
   mismatch (...); the recompiled code was built from SLUS-21503
   1742f95b..."}`.
2. The exit: `src/main.cpp:657-661` — `if (!disc.ok) {
   GH_ERROR(Boot, disc.error); return kExitBadDisc; }`.
   `kExitBadDisc` is 3 (`src/main.cpp:83`; the table: 0 ok, 1 init
   failed, 2 no disc, 3 bad disc, 4 CPU, 5 missing function, 6 crash).
   The GH_ERROR line is the "[BOOT] ERROR ..." line in the log — the
   only place the reason existed.
3. The handover: `android/glue/godhand_android_main.cpp:889` — the
   glue's `main()` receives the 3 from `godhand_port_main`, logs
   "native: godhand_port_main returned 3" and returns it; the
   NativeActivity native thread simply ends.
4. The Java side knew nothing: no callback, no shared record, nothing
   to poll. GameActivity had already added the touch overlay (in the
   issue-3 log the overlay layout line prints AFTER the failure), and
   its only native poll, the FPS text, just returns empty forever.
5. What the player saw: the game screen with working touch controls
   and no picture, no sound, no text — indistinguishable from a hang.

## The mechanism (smallest reliable chain, all in-tree idioms)

Four hops, each one a pattern the tree already uses:

1. Classify at the source — `verifyDiscAndPrepare` now also fills a
   new `DiscCheckResult.failure` field (`src/godhand/game_disc.h`)
   with a small fixed vocabulary set at the exact refusal sites in
   game_disc.cpp: "hash" at the hash-mismatch return, "disc" at the
   SYSTEM.CNF missing / wrong-boot / disc-file-list / boot-ELF-missing
   returns, "image" at the cannot-mount return. Other refusals (e.g.
   cannot write the extracted ELF) stay unclassified on purpose.
   `verifyDiscAndPrepare` has exactly one consumer (main.cpp), so the
   new field disturbs nothing else; the checks, the error strings and
   the refusal behaviour are untouched.
2. Port → glue — at the `!disc.ok` return in `src/main.cpp`, the port
   also does `setEnv("GODHAND_BOOT_FAILURE", disc.failure)` when a
   kind was classified. The environment is the channel this process
   already uses in both directions (the glue setenvs the port's whole
   configuration). Desktop boots never read it.
3. Glue record + JNI — after `godhand_port_main` returns in the glue's
   `main()`, a non-zero return fills a file-scope
   `std::atomic<const char*>` record: the env value when it is one of
   "hash"/"disc"/"image", else the generic "failed". A clean return
   (0) records nothing, and the record is cleared before each run.
   The codes are fixed string literals, so the atomic pointer is the
   whole synchronisation (native thread stores once, UI thread
   reads). A new JNI export next to `nativeFpsText`,
   `Java_dev_ptraaa_godhand_TouchPad_nativeBootFailure`, returns the
   code or "". The glue also logs the proof line:
   `native: boot failure recorded for the shell: hash (port returned 3)`.
4. Shell — `TouchPad.nativeBootFailure()` is declared beside
   `nativeFpsText`. GameActivity polls it every 500 ms from a new
   `bootWatchTick` on the existing handler, posted in onResume and
   removed in onPause, in BOTH Player and Developer mode and regardless
   of the FPS toggle (this is player information, not a developer
   readout). The first non-empty code stops the watch, writes
   `[shell] m45: the game did not start (<code>) - telling the player
   why` to the boot log, and shows the message (below).

## The player text (exact)

Title for every case: **"The game could not start"**, one button: OK.

- code "hash":
  "This disc image is not the original release. Modded, patched or
  translated images cannot run, and neither can discs from other
  regions. Please use a clean dump of the original US disc
  (SLUS-21503)."
- code "disc":
  "This disc image is not the original US release of the game. Please
  use a clean dump of the original US disc (SLUS-21503)."
- code "image":
  "This file could not be read as a disc image. Please pick a
  complete, undamaged dump of the original US disc (SLUS-21503)."
- any other code ("failed" — init failure, missing function, crash,
  no disc, and the unclassified disc refusals):
  "The game could not start. Please try another disc image. The boot
  log in the app folder says exactly what went wrong."

Presentation: an AlertDialog, because it stays on screen until the
player dismisses it — no timer can run it away mid-read. OK calls
`finish()`, back at the launcher, where they can pick another image.
Dismissing the dialog instead (Back) does not trap them either: the
native thread is already gone, so Back leaves the activity as usual.
If the dialog itself throws, the same text falls back to a long
Toast and the failure is logged. Nothing about the refusal changed:
same checks, same exit codes, same log lines — a boot that worked in
m44 works identically in m45 and never sees any of this (a non-zero
return is the only trigger, and a normal play session's port does not
return until the session ends by the player's own stop, which is a
destroy path that records rc 0 / never reaches the record).

One pre-existing wrongness observed in the issue-3 log, deliberately
NOT fixed here (out of scope): after the failure, the Java lifecycle
still logs "native: app resumed - emulation running" when the
activity resumes, although nothing is running.

## Source changes

Originals snapshotted in m45bootmsg-build-work/orig/. Diff sizes are
+/- lines against the m44 tree state:

- src/godhand/game_disc.h (+7/-0): the `failure` field and its comment.
- src/godhand/game_disc.cpp (+8/-0): `result.failure = ...` at the
  five refusal groups listed above. No check logic touched.
- src/main.cpp (+7/-0): the GODHAND_BOOT_FAILURE setEnv at the
  existing `!disc.ok` return.
- android/glue/godhand_android_main.cpp (+42/-0): `<atomic>` include;
  the record + `nativeBootFailure` JNI beside `nativeFpsText`; the
  clear-before / record-after around the `godhand_port_main` call.
- android/shell/.../TouchPad.java (+7/-0): the `nativeBootFailure`
  declaration.
- android/shell/.../GameActivity.java (+76/-0): AlertDialog /
  DialogInterface / Toast imports; `bootFailureShown` field; the
  `bootWatchTick` poller; its post/remove in onResume/onPause;
  `showBootFailure` (the four texts, dialog, toast fallback). No
  existing line changed — every hunk is an addition.
- android/shell/AndroidManifest.xml: versionCode 52→53, versionName
  0.45-m44-gsfallback → 0.46-m45-bootmsg (the standing per-build bump).

## Builds

- Core: godhand-recomp/build/android-m2a, cache
  GODHAND_PATHB_DEFAULT_PCSX2 flipped ON for the variant build (the
  standing discipline — the owner's phone carries a persistent
  PS2X_GS_RENDERER=native ini, so the pcsx2 default must be compiled
  in), `ninja src/libgodhand.so`: 5 steps, clean — exactly the three
  edited TUs (android glue, game_disc.cpp, main.cpp) + relink. Cache
  flipped back OFF afterwards; the variant .so was staged first.
  Variant marker string ("path B variant: PS2X_GS_RENDERER=pcsx2
  (variant default; ...") present in both the m44 and the new lib.
  The new lib is +1,008 B over m44's — the code delta, not a
  configuration change. New JNI export verified in the shipped lib
  (llvm-nm -D): `Java_dev_ptraaa_godhand_TouchPad_nativeBootFailure`
  present; absent from the m44 lib. The strings "GODHAND_BOOT_FAILURE"
  and "native: boot failure recorded for the shell: %s (port returned
  %d)" are present in the shipped lib.
- Shell: javac + d8 in the packaging script — clean. Unlike m44
  (which shipped m43's dex verbatim because it changed no shell
  file), m45 SHIPS its freshly compiled dex: the feature lives in
  Java. Same flow the m43 package shipped its dex with. Dex-level
  proof on the shipped classes.dex: `nativeBootFailure` is declared
  and the dialog title + all player texts are present verbatim
  (strings), including the full "hash" text.
- Bridge: not rebuilt — m45 touches no bridge source; the staged m44
  bridge lib is asserted byte-equal to the delivered m44 APK entry
  inside the packaging script (md5 ce07badc…).
- Packaging: m45bootmsg-build-work/package_m45.py (the m44 pattern:
  aapt2 compile → link, entries re-zipped STORED in the shipped
  order, zipalign -p, apksigner).

## Arbitration vs the delivered m44 APK (entry-for-entry)

Entry set, order and STORED compression identical (19 entries).

| entry | m44 | m45 | verdict |
|---|---|---|---|
| AndroidManifest.xml | a2a65f6f…, 2,548 B | b9da1120…, 2,544 B | DIFF — version strings only (aapt2 dump xmltree diff = exactly the versionCode 52→53 and versionName lines) |
| classes.dex | 917b4993…, 87,812 B | b7e13b1d…, 90,040 B | DIFF — intended: the ONLY shell change (GameActivity boot watch + dialog, TouchPad declaration; source diff above, all additions) |
| lib/arm64-v8a/libgodhand.so | d05376c8…, 101,492,728 B | b62c3978…, 101,493,736 B | DIFF — intended: the boot-failure record (game_disc + main + glue, +1,008 B) |
| lib/arm64-v8a/libpcsx2gs.so | ce07badc…, 28,793,912 B | ce07badc…, 28,793,912 B | IDENTICAL |
| lib/arm64-v8a/libshaderc_shared.so | 2793f5b6…, 10,007,448 B | 2793f5b6…, 10,007,448 B | IDENTICAL |
| resources.arsc + all res entries | — | — | IDENTICAL, all |
| META-INF/*.SF/.RSA/MANIFEST.MF | — | — | DIFF — expected: signature digests of the changed entries; same signer (below) |

Gates: apksigner verify v2+v3 true; signer SHA-256
9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e (the
m39–m44 signer — installs over m44); zipalign -c -p 4 successful;
aapt2 dump badging: dev.ptraaa.godhand, versionCode 53, versionName
0.46-m45-bootmsg. The zip's inner APK md5 equals the delivered APK's.

## Test status — honest

The full chain cannot be exercised end-to-end on this host: there is
no Android device or emulator here, and no configured host build of
the port to run the disc check in (configuring one compiles the whole
recomp — disproportionate for three small TUs that the Android build
already compiles in their real tree). What IS proven on host:

- Both changed sides compile clean in their real build flows (ninja
  core build; javac/d8 in packaging).
- The record's endpoints exist in the shipped artifacts: the JNI
  symbol exported from libgodhand.so, the env-var name and the
  record log format string in the same lib, and in classes.dex the
  native declaration plus the exact player texts.
- The refusal path itself is untouched code: same checks, same
  GH_ERROR strings, same return codes — the only new statements on
  that path are the classification stores, the setEnv, and the
  after-return record.

The on-device proof still owed, and exactly what proves it: install
m45 over m44, pick the same kind of modded image the issue-3 player
used (any image whose SLUS_215.03 differs from the original). Within
about a second of pressing play the dialog must appear with the title
"The game could not start" and the "hash" text above; OK must land
back on the launcher. The returned boot log must show, in order: the
unchanged "[BOOT] ERROR boot executable hash mismatch ..." line,
"native: godhand_port_main returned 3",
"native: boot failure recorded for the shell: hash (port returned 3)",
and "[shell] m45: the game did not start (hash) - telling the player
why". A good-image boot on the same install must show none of the
m45 lines and play as in m44 — worth one confirmation boot alongside,
because the promise is that successful boots are untouched. Once a
device run confirms it, issue #3 can be answered with this build.

## Deliverables

- godhand-0.46-m45-bootmsg.apk — 140,506,559 B, md5
  1777abdd0d988cebd9e97bade142c55f
- godhand-0.46-m45-bootmsg.zip — 35,571,469 B, md5
  fe277d9e836fda0a9dbc903a2ab9016e (inner APK md5 verified identical)
- Work dir: m45bootmsg-build-work/ (orig/ source snapshots,
  package_m45.py, configure/build/package logs, staged
  libgodhand-m45.so, pkg/ intermediates)
