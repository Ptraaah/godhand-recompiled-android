# Path B — b5 revival (0.27-m26-b5revival, versionCode 34)

**Deltas from the original b5 deliverable, flagged up front as required.**
This revival is a binary revival: the payload of the original
`godhand-0.25-m24-pathb5.apk` (stage B4's output, the only build the owner
ever called playable) is shipped byte-for-byte. Exactly three deltas exist,
all forced by the install-over requirement, none functional: **(1)**
`versionCode` 29 → **34** and `versionName` `0.25-m24-pathb5` →
**0.27-m26-b5revival** in the binary manifest (an `aapt2 dump xmltree`
diff of old vs new manifest shows these two attributes and nothing else;
the manifest grew 2,452 → 2,456 B). **(2)** The APK is re-signed — with the
*same* key (signer cert SHA-256 identical, below), so it installs as an
update; the signature bytes and META-INF entries are necessarily new
because the manifest changed. APK total 136,696,581 B vs b5's 136,696,533 B
(+48 B: +4 manifest, rest signature/container). **(3)** Zip container
cosmetics (entry order, timestamps) differ from the 2026-11-04 packaging.
One brief-vs-artifact discrepancy, resolved in favour of the artifact: the
commissioning brief describes b5 as having "widescreen default off with
toggle kept" — **the widescreen feature does not exist in b5 at all** (zero
`PS2X_WIDESCREEN` strings in its libgodhand.so; the feature arrived in
stage B5 / pathb6, the shell toggle in pathb8). In this build widescreen is
not off-by-default, it is *absent* — permanently the 4:3 presentation b5
shipped, which is what b5 fidelity requires. Everything else post-b5 is
likewise absent by construction, not by removal: the stage-B5 VU diet
(dead-norm elimination + -O3 VU library), the present-layer aspect fix, and
the b8 bridge warm-up (`WarmUpCaptures`) were never in these binaries.

## Why binary revival instead of a rebuild

A rebuild could not reproduce b5 today: the bridge currently staged at
`path-b/build-b1/libpcsx2gs.so` has md5 `e86c76527b7e796828621488f1e05de9`,
which is **not** b5's bridge (`96a5efde…`) — the tree's bridge was rebuilt
by later stages. The preserved b5 APK, however, is intact and its contents
match the stage-B4 report (PATH-B-B4.md) exactly, so the faithful route is
to repackage the original binaries with only the version fields bumped.
No compilation of any payload component was performed; "compiler flags"
for every payload file are therefore b5's own (variant build,
`-DGODHAND_PATHB_DEFAULT_PCSX2=ON`, project -O2; the -O3 VU library is a
stage-B5 change and is absent here).

## Provenance table (component by component)

| Component | b5 original (PATH-B-B4.md record) | This revival | Verdict |
|---|---|---|---|
| `lib/arm64-v8a/libgodhand.so` | 98,493,672 B, md5 `155df71be5157b86af1d7f2730962c4c` | extracted from the preserved b5 APK, md5 `155df71be5157b86af1d7f2730962c4c`; also matches the loose archive copy `libgodhand-0.25-m24-pathb5.so` | **byte-identical** |
| `lib/arm64-v8a/libpcsx2gs.so` (bridge) | md5 `96a5efdea52b2b8d9b4543d8ca2a0db0` ("byte-identical to B3's") | extracted from the preserved b5 APK, md5 `96a5efdea52b2b8d9b4543d8ca2a0db0` | **byte-identical** — b5's bridge, pre-warm-up (0 `warmup` strings; b8's bridge, md5 `e86c76527b7e796828621488f1e05de9` — the same binary as the tree's currently staged bridge — has them and is not used) |
| `lib/arm64-v8a/libshaderc_shared.so` | md5 `2793f5b6394868783852325a4295e1f2` (unchanged across B3–B5) | md5 `2793f5b6394868783852325a4295e1f2` | **byte-identical** |
| `classes.dex` (shell/launcher) | shipped in b5 APK | md5 `2d32e68be8c0eb911d80c700529277ee`, extracted from b5 APK and repacked unmodified | **byte-identical** — b5 shell behaviour/defaults by construction |
| `resources.arsc` | shipped in b5 APK (40 B) | md5 `57ef0403aecfe9a84e3bee942fc1d080` | **byte-identical** |
| `AndroidManifest.xml` | versionCode 29 / `0.25-m24-pathb5`, 2,452 B | versionCode **34** / `0.27-m26-b5revival`, 2,456 B; binary string-pool rebuild + in-place int patch; xmltree diff = version attrs only | **delta (flagged above)** |
| Variant seeds in libgodhand.so | renderer + VU1 async seeds | strings-verified in the shipped binary: `PS2X_GS_RENDERER=pcsx2 (variant default…)` and `PS2X_VU1_ASYNC=1 (variant default…)` — the MTVU-on, pcsx2-default configuration the owner's 44–52 fields/s run used; no widescreen seed exists | **identical to b5** |
| VU generated tree | pre-B5 tree preserved at `b5-backup/vu1-gen-orig` (+ `vu1_recomp.cpp`, `ps2_vu1_impl.inl` backups) | informational only — the shipped .so *is* b5's binary, so its compiled VU code is b5's by construction. Source-side, the current `godhand-recomp/generated/vu1` diffs clean against `b5-backup/vu1-gen-orig` (0 differing files), consistent with the b8 restore | **consistent** |
| Signer | shared ptraaa debug signer, cert SHA-256 `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e` | signed with the same keystore (`recomp-android/probe/debug.jks`) via the pipeline's own build-tools 36.0.0; apksigner: **v2 + v3 verified, 1 signer, cert SHA-256 `9791d26c…c9005e`** | **same signer** |

## Deliverables

- `godhand-0.27-m26-b5revival.apk` — 136,696,581 B, md5
  `3b59b047ee4f416233f681131d345245`; package `dev.ptraaa.godhand`,
  versionCode **34**, versionName **0.27-m26-b5revival**, minSdk 26,
  targetSdk 35 — installs over 0.26-m25 (versionCode 33) in place.
- `godhand-0.27-m26-b5revival.zip` — 34,836,807 B, md5
  `3ccb9250f1835f6ec87d0785619be773`; contains the single APK (same shape
  as the b5 delivery zip); `unzip -t` clean.
- `zipalign -c -p 4` passes on the final APK. All payload files were
  md5-compared after extracting the *final signed* APK, not the staging
  dir — the table above reflects the shipped bytes.

## Owner-run note (matters for a clean verdict)

The variant seeds are defaults that an external `godhand.ini` line
overrides. The owner's ini is known to carry a renderer line from the
native experiments (pathb8's log showed `godhand.ini native`). If
`PS2X_GS_RENDERER=native` is still in the external
`/storage/emulated/0/Android/data/dev.ptraaa.godhand/files/godhand.ini`,
this build will honour it and boot the benched native renderer. For the
b5 verdict, that line must be removed (the B4 owner steps asked for the
same clean-ini condition). Boot-log proof of the intended configuration:
the two `path B variant` seed lines, `[vu1] async on` windows, and
gameplay fields/s against the pathb5 baseline of 44–52.

No performance outcome is claimed. The device run is the arbiter, as
always — this report claims only that what ships is, component for
component, what pathb5 shipped.
