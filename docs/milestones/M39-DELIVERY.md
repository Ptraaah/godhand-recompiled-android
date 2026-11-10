# M39 delivery — final combined build (0.40-m39)

Built 2026-11-11 by combining the m39 shell candidate (native buttons +
launcher redesign + icon) with the m39 core libs, using the same
packaging method as nativebuttons-build-work/package_m39.py (stored
entries, zipalign -p 4, probe debug.jks signer). Packaging script:
m39final-build-work/package_m39_final.py. The candidate APK and the
shell lane's files were not modified.

## Outputs

- ~/workspace/godhand-android/godhand-0.40-m39.apk
  - 140,482,367 B, md5 b0b48fe81767c450a32c4db4c6d5e317
- ~/workspace/godhand-android/godhand-0.40-m39.zip (APK alone inside)
  - 35,860,324 B, md5 dbee10691ba435a9387c8822d1341aa6

## Verification gates — all PASS

- Badging (aapt2): package dev.ptraaa.godhand, versionName 0.40-m39,
  versionCode 47, minSdk 26, targetSdk 35.
- apksigner verify: passes (v2 + v3 schemes, 1 signer).
- Signer cert SHA-256: 9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e
  — identical for the final APK and the candidate APK, so it installs
  over m38 / the candidate.
- Per-entry arbitration vs godhand-0.40-m39-nativebuttons-CANDIDATE.apk:
  same 16 non-META-INF entries; exactly 2 differ —
  lib/arm64-v8a/libgodhand.so and lib/arm64-v8a/libpcsx2gs.so.
  The other 14 entries (classes.dex, resources.arsc, AndroidManifest.xml,
  all res/, lib/arm64-v8a/libshaderc_shared.so) are byte-identical.
- The two swapped entries match the verified m39-core files:
  - lib/arm64-v8a/libgodhand.so md5 2f21b07128c4a02916995af089871ab1
    (= m39-core/libgodhand-m39.so, 101,489,680 B)
  - lib/arm64-v8a/libpcsx2gs.so md5 d14eedbd247e7d7a16dc5975b39c60b8
    (= m39-core/libpcsx2gs-m39.so, 28,791,480 B)

No source code was changed in this packaging step.
