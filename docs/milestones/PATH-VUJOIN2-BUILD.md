# VU-join correction build (0.30-m29-vujoin2, versionCode 37)

**What this fixes and nothing else.** m28 (0.29-m28-vujoin) never
exercised the VU-join lever on the device: its core is the restored
mainline core, which carries no renderer seed, so it honoured the
owner's persistent app ini — the last two sessions of the appended
boot log (`~/workspace/gh-m28-log/files/godhand-boot.log`) both
resolve `[BOOT] settings: ini: PS2X_GS_RENDERER=native` with no env
override and no seed lines, and he re-tested the already-benched
native renderer (fence stalls to ~4.9 s/window) instead of the
pipelined PCSX2 path. This build is m28 with one variable changed:
the compiled-in variant default that forces the renderer to pcsx2
regardless of that ini, restored in the core. Core code (including
the live-register-reads patch, `PS2X_VU1_LIVE_REGS` default ON),
the pipelined bridge, shell, dex and resources are m28's bytes or
m28's tree; the per-file table below proves it from the final
signed APK. No performance outcome is claimed — the device log is
the arbiter, as always.

## Where the forcing mechanism lives

The b5/m27 mechanism, in three parts, all pre-existing in the tree:

1. **CMake option** `GODHAND_PATHB_DEFAULT_PCSX2` (top-level
   `CMakeLists.txt:59`, default OFF — "Path B variant: Android glue
   defaults PS2X_GS_RENDERER to pcsx2"). When ON,
   `src/CMakeLists.txt:242-245` adds the compile definition of the
   same name to the `godhand` target only.
2. **The seed block** it gates: `android/glue/godhand_android_main.cpp`
   line 509 (`#ifdef GODHAND_PATHB_DEFAULT_PCSX2`). The glue runs
   before `godhand_port_main`/loadSettings and seeds real
   environment variables — the layer loadSettings ranks **above**
   the internal `godhand.ini`. For the renderer it first reads the
   **external** ini (`/storage/emulated/0/Android/data/dev.ptraaa.
   godhand/files/godhand.ini`, next to the boot log): a renderer
   line there is passed through untouched (the documented escape
   hatch); no line means the variant default, `setenv("PS2X_GS_
   RENDERER","pcsx2",1)`.
3. **Resolution proof from this device's own log:** every variant
   session in the appended boot log (ten sessions, b5 through m27)
   shows the pair `native: path B variant: PS2X_GS_RENDERER=pcsx2
   (variant default; no renderer setting in the external ...)` and
   `[BOOT] settings: env: PS2X_GS_RENDERER=pcsx2 (wins over
   godhand.ini native)`. The internal ini has carried `native`
   throughout — it is still there in m28's last two sessions — and
   the external ini has never carried a renderer line. With the
   seed compiled back in, the same ini state resolves to pcsx2 by
   the same code path that produced those ten sessions. The
   internal ini's `native` line does **not** reach the escape
   hatch; only a line in the external file does.

## The one narrowing, and why (read this before crying foul)

The current source's seed block has four paragraphs; b5 shipped
only two of them. The extra two are later Path B stage additions
that m27 never had, and both are compiled **out** here (`#if 0`
around the two paragraphs, with the reason recorded in a comment
in the glue), leaving exactly b5's seed set:

- **Kept:** `PS2X_GS_RENDERER` seed (the fix) and the
  `PS2X_VU1_ASYNC=1` seed (stage B4, part of b5). The VU1 seed is
  disclosed, not smuggled: m28 resolved VU1 async to the compiled-
  in default **off** (`ps2_async_vif.cpp` `enabled()`: env, then
  `s_defaultEnabled{false}`; `setDefaultEnabled` has no callers;
  the internal ini carries no `PS2X_VU1_ASYNC` line — none appears
  in m28's settings dump). M27's baseline (queue_hw=2, crowd
  join_wait 507–1,694 ms/window) was measured in the seeded
  async-on configuration; without the seed there is no async
  pipeline for the live-regs lever to act on. Restoring the seed
  restores m27's measured configuration, which is the comparison
  this test exists to make.
- **Compiled out:** the `PS2X_WIDESCREEN` seed (stage B5/B6). It
  seeds from the shell toggle, and the glue's own settings line in
  recent sessions of this device's log reads `wide=on` — seeding
  it would set `PS2X_WIDESCREEN=1` and open the aspect gate at
  `src/main.cpp:778`, flipping the picture to the 16:9 patch group:
  a second variable against m28, whose protocol (and m27's) is
  wide off. Not shipped.
- **Compiled out:** the `PS2X_GS_WARMUP` seed (stage B7). Nothing
  in this configuration consumes the variable: grep finds no
  reader in the pipelined bridge (`path-b/compat/`, whose B6/B7
  blocks were removed in the pipeline reconstruction) and no
  reader in the core (`runtime/`, `src/`). Inert, but excluded
  anyway so the delta stays exactly the b5 set.

## Core delta proof (m28 core → m29 core)

| Check | m28 core | m29 core |
|---|---|---|
| md5 | `82fc27c5437d15144387b0cd0a384699` | `b2f053402530a928593f0dd30e8c5015` |
| size | 99,713,624 B | 99,718,856 B (**+5,232 B**) |
| `.text` | 82,535,880 B | 82,540,392 B (+4,512 B — the seed block) |
| seed strings | none | exactly the 4 renderer/VU1 seed format strings |

Strings diff (`strings -n 12`, sorted-unique, m28 vs m29 core):
added = the four seed format strings
(`...PS2X_GS_RENDERER=pcsx2 (variant default; no renderer setting
in the external %s)`, `...PS2X_GS_RENDERER=%s (from the external
%s)`, and the two `PS2X_VU1_ASYNC` twins), the `/godhand.ini`
literal the seed block builds the external path from, and three
mangled names identical to m28's except a lambda index
(`$_0` → `$_2` — the seed block's trim/lower lambdas now exist in
`main()`); removed = the two `$_0` spellings of those names.
Nothing else. No widescreen-seed and no warmup-seed strings are
present in the shipped core (grep-verified); the
`PS2X_VU1_LIVE_REGS` kill-switch string and the `[vu1] joins:`
attribution format are present — the live-regs lever is intact.
The generated VU tree is untouched: `diff -r generated/vu1
b5-backup/vu1-gen-orig` is clean after the rebuild.

**Determinism / tree restoration.** After the variant `.so` was
copied out, the glue was restored byte-identical (md5
`f7e7a5ca64494311f775cee54930dc14`; `git diff` on the file is
empty), the option was set back to OFF, and the target was
rebuilt (exit 0, log `m29-restore-build.log`): the rebuilt
`build/android-m2a/src/libgodhand.so` is md5
`82fc27c5437d15144387b0cd0a384699` — **m28's core reproduced byte
for byte**. The build tree is left in its normal mainline state;
the m29 core is the only artifact taken from the variant
configuration, and the md5 equality proves the variant delta is
the define-gated seed block and nothing else.

## Per-file md5 delta table (base m28 → this build, from the final signed APK)

Expected delta set: exactly {`libgodhand.so` (the disclosed core
fix), `AndroidManifest.xml` version fields, signature files}.
Observed delta set: **exactly that set — no unexpected delta.**

| File | m28 md5 | m29 md5 | Verdict |
|---|---|---|---|
| `lib/arm64-v8a/libgodhand.so` | `82fc27c5437d15144387b0cd0a384699` (99,713,624 B) | `b2f053402530a928593f0dd30e8c5015` (99,718,856 B) | **CHANGED — the one intended payload change** (variant seed block, see above) |
| `lib/arm64-v8a/libpcsx2gs.so` | `9e468a9082b5e51fdfaf435c9dad909a` | `9e468a9082b5e51fdfaf435c9dad909a` | **byte-identical** — the pipelined bridge, unchanged |
| `lib/arm64-v8a/libshaderc_shared.so` | `2793f5b6394868783852325a4295e1f2` | `2793f5b6394868783852325a4295e1f2` | **byte-identical** |
| `classes.dex` | `2d32e68be8c0eb911d80c700529277ee` | `2d32e68be8c0eb911d80c700529277ee` | **byte-identical** (m28 shell) |
| `resources.arsc` | `57ef0403aecfe9a84e3bee942fc1d080` | `57ef0403aecfe9a84e3bee942fc1d080` | **byte-identical** |
| `AndroidManifest.xml` | `69e38b34ba9e5f3519c63af024e5ffe4` (2,452 B) | `348c6f314a4a1d78ca868602a6df2acb` (2,452 B) | CHANGED — version fields only (versionCode 36 → **37**, versionName `0.29-m28-vujoin` → `0.30-m29-vujoin2`); `aapt2 dump xmltree` diff shows the two version attributes and nothing else |
| `META-INF/MANIFEST.MF` | `4366886c674c51304abcae8609ae3440` | `b20d6d010858622603d4e17e2943eced` | CHANGED — signature file (regenerated by re-signing) |
| `META-INF/ANDROIDD.SF` | `cb21c9a6ce0179619698eac2ee6f8a48` | `0a9fcfd9254b31479e255bde76397fad` | CHANGED — signature file |
| `META-INF/ANDROIDD.RSA` | `e276919181380caad010cd84141ce880` | `f61193905c55b620cd879fc665cea912` | CHANGED — signature file |

Entry sets and order are identical (9 entries in both APKs, same
order and compression methods: manifest deflated, all payload
stored) — method identical to the m28 repackage of m27.

## Deliverables

- `godhand-0.30-m29-vujoin2.apk` — 138,596,981 B, md5
  `fa65fb319b89bea5e83fb1dcc420dd68`; package
  `dev.ptraaa.godhand`, versionCode **37**, versionName
  **0.30-m29-vujoin2**, minSdk 26, targetSdk 35 (aapt2 badging) —
  installs as an update over m28 (versionCode 36).
- `godhand-0.30-m29-vujoin2.zip` — 34,912,315 B, md5
  `eeac29e1bb10c45bb731bd6606440763`; contains the single APK;
  `unzip -t` clean, no errors.
- `zipalign -c -p 4` passes on the final APK.
- Signer: apksigner **v2 + v3 verified, 1 signer** — DN
  `CN=Probe, O=ptraaa, C=ID`, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — the same shared debug signer as b5/m26/m27/m28, so the update
  installs in place.

## The lines that arbitrate the device run

Same protocol as m27/m28 (cool phone, wide off, pipeline on at
depth 2). Baseline: M27-LOG-ANALYSIS.md (crowd `[vu1] join_wait`
507–1,694 ms per 5 s window; crowd fields/s 39–49; pipeline
`ahead_max` never above 1). If the boot does not show the config
proof lines, stop — the run measures nothing.

- **Config proof (new this build, the fix itself):** the boot must
  print `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant
  default; no renderer setting in the external ...)` and
  `[BOOT] settings: env: PS2X_GS_RENDERER=pcsx2 (wins over
  godhand.ini native)` — the internal ini will still say native;
  the env line winning over it is the proof. It must also print
  `native: path B variant: PS2X_VU1_ASYNC=1 (variant default;
  ...)` and `[pcsx2-gs] pipeline on: ... depth 2 fields`. There
  must be **no** `path B variant: PS2X_WIDESCREEN` and no
  `PS2X_GS_WARMUP` seed line — their absence convicts nothing;
  their presence would mean the wrong core shipped.
- **`[vu1] join_wait` (the conviction metric):** crowd windows
  fall from 507–1,694 ms toward the light-window scale —
  **target < ~150 ms per 5 s window**. Light windows stay at
  35–45 ms or below.
- **`[vu1] joins:` (attribution):** with the lever on, **`regread`
  ≈ 0** outside download moments; wherever the remaining
  join_wait sits (`idle`, `regwrite`, `vumem`, ...) names the next
  wall — a successful measurement even if fields/s disappoints.
- **Fields/s:** crowd windows rise from 39–49 toward **55–60**;
  `[pathb] pipeline: ahead_max` touches 2 in crowd windows;
  `dropped_captures` stays 0.
- **Kill switches / A/B:** `PS2X_VU1_LIVE_REGS=0` in the ini
  restores the draining register reads (separates the lever from
  everything else in this same binary); `PS2X_GS_PIPELINE=0`
  re-isolates the bridge stage. The deliberate road back to the
  native renderer is unchanged from b5: a `PS2X_GS_RENDERER=
  native` line in the **external** `godhand.ini` — the internal
  ini's existing native line is overridden by the seed and does
  not trigger it.
