# Vblank-bound build (0.31-m30-vblank, versionCode 38)

**What this build is and nothing else.** m29 (0.30-m29-vujoin2)
with one variable changed: the core. The shipped core is the
vblank stage's core (PATH-VBLANK.md — mainline + live-register-
reads + the bounded vblank barrier, `PS2X_VU1_VBLANK_BOUND` and
`PS2X_VU1_LIVE_REGS` both default ON) put through the exact
packaging step m29 used: the define-gated Path B variant seed
(narrowed to b5's two seeds) compiled in, so the boot resolves
the renderer to pcsx2 over the owner's persistent ini. The
pipelined bridge, shell, dex and resources are m29's bytes; the
per-file table below proves it from the final signed APK. No
performance outcome is claimed — the device log is the arbiter,
as always.

## Core provenance — the stage artifact and the shipped artifact

The stage left its linked core in the tree's normal mainline
state (variant option OFF): md5 `cf08fa3bf06723084a6947bf61910b80`,
99,717,800 B. That artifact cannot ship as-is: without the seed
it would honour the owner's ini and boot the native renderer —
the m28 failure. So the m29 packaging procedure was repeated on
the vblank tree, step for step:

1. The glue seed block was narrowed exactly as in m29: `#if 0`
   around the widescreen (B5/B6) and warmup (B7) seed paragraphs
   in `android/glue/godhand_android_main.cpp`, with the reason
   recorded in a comment, leaving b5's seed set —
   `PS2X_GS_RENDERER` + `PS2X_VU1_ASYNC=1`.
2. `GODHAND_PATHB_DEFAULT_PCSX2=ON`, `ninja godhand`, variant
   core copied out: md5 **`17ba5dc9628185de05d3e9c2a83425b0`**,
   99,723,032 B.
3. Glue restored byte-identical (md5
   `f7e7a5ca64494311f775cee54930dc14`, the same value m29's
   restore produced), option set back to OFF, rebuilt: the tree
   reproduces the stage artifact **byte for byte**
   (`cf08fa3bf06723084a6947bf61910b80`). The build tree is left
   in its normal state; the variant core is the only artifact
   taken from the variant configuration.

The delta is therefore disclosed and triple-checked:

| Check | Stage core (option OFF) | Shipped core (variant) |
|---|---|---|
| md5 | `cf08fa3bf06723084a6947bf61910b80` | `17ba5dc9628185de05d3e9c2a83425b0` |
| size | 99,717,800 B | 99,723,032 B (**+5,232 B**) |

- **+5,232 B is the same size delta the identical packaging step
  produced for m29** (99,713,624 → 99,718,856 B): the seed block
  and nothing else.
- Cross-check against m29's shipped core: 99,723,032 −
  99,718,856 = **+4,176 B — exactly the vblank stage's own code
  delta** over its base (PATH-VBLANK.md §6). Both cores carry
  the identical seed; the only code difference between the m29
  and m30 cores is the stage's change.
- Strings diff (`strings -n 12`, sorted-unique, stage vs
  shipped): added = the four seed format strings
  (`...PS2X_GS_RENDERER=pcsx2 (variant default; no renderer
  setting in the external %s)`, `...PS2X_GS_RENDERER=%s (from
  the external %s)`, and the two `PS2X_VU1_ASYNC` twins), the
  `/godhand.ini` literal, and three mangled lambda names
  (index renames from the seed block's lambdas in `main()`);
  removed = the two pre-rename spellings. Nothing else.
- **No widescreen-seed and no warmup-seed strings are present**
  in the shipped core (grep-verified, count 0 for both
  `path B variant: PS2X_WIDESCREEN` and `path B variant:
  PS2X_GS_WARMUP`).
- **Both levers and the attribution are present** in the shipped
  core (grep-verified): `PS2X_VU1_VBLANK_BOUND`,
  `PS2X_VU1_LIVE_REGS`, the `barrier=%.1f ms (%llu)` format,
  and the wins-over-ini settings line format.
- The generated VU tree is untouched: `diff -r generated/vu1
  b5-backup/vu1-gen-orig` clean after both rebuilds.

## Per-file md5 delta table (base m29 → this build, from the final signed APK)

Expected delta set: exactly {`libgodhand.so` (the disclosed core
swap), `AndroidManifest.xml` version fields, signature files}.
Observed delta set: **exactly that set — no unexpected delta.**

| File | m29 md5 | m30 md5 | Verdict |
|---|---|---|---|
| `lib/arm64-v8a/libgodhand.so` | `b2f053402530a928593f0dd30e8c5015` (99,718,856 B) | `17ba5dc9628185de05d3e9c2a83425b0` (99,723,032 B) | **CHANGED — the one intended payload change** (vblank stage core + same variant seed, see above) |
| `lib/arm64-v8a/libpcsx2gs.so` | `9e468a9082b5e51fdfaf435c9dad909a` | `9e468a9082b5e51fdfaf435c9dad909a` | **byte-identical** — the pipelined bridge, unchanged |
| `lib/arm64-v8a/libshaderc_shared.so` | `2793f5b6394868783852325a4295e1f2` | `2793f5b6394868783852325a4295e1f2` | **byte-identical** |
| `classes.dex` | `2d32e68be8c0eb911d80c700529277ee` | `2d32e68be8c0eb911d80c700529277ee` | **byte-identical** (m29 shell) |
| `resources.arsc` | `57ef0403aecfe9a84e3bee942fc1d080` | `57ef0403aecfe9a84e3bee942fc1d080` | **byte-identical** |
| `AndroidManifest.xml` | `348c6f314a4a1d78ca868602a6df2acb` (2,452 B) | `36881126bbc5c758f11221805bde027e` (2,452 B) | CHANGED — version fields only (versionCode 37 → **38**, versionName `0.30-m29-vujoin2` → `0.31-m30-vblank`); `aapt2 dump xmltree` diff shows the two version attributes and nothing else |
| `META-INF/MANIFEST.MF` | `b20d6d010858622603d4e17e2943eced` | `27a9387632f78b2cd0cba6478dccb95c` | CHANGED — signature file (regenerated by re-signing) |
| `META-INF/ANDROIDD.SF` | `0a9fcfd9254b31479e255bde76397fad` | `86e0025983081621d24eb1f735c3eaa1` | CHANGED — signature file |
| `META-INF/ANDROIDD.RSA` | `f61193905c55b620cd879fc665cea912` | `d08ff1824c8234047cff0349181334f0` | CHANGED — signature file |

Entry sets, order and compression methods are identical
(9 entries in both APKs: manifest deflated, all payload stored,
signature files deflated) — method identical to the m29
repackage of m28.

## Deliverables

- `godhand-0.31-m30-vblank.apk` — 138,596,981 B, md5
  `74f6c7ae41ed7c65f0e2dcd398b17095`; package
  `dev.ptraaa.godhand`, versionCode **38**, versionName
  **0.31-m30-vblank**, minSdk 26, targetSdk 35 (aapt2 badging) —
  installs as an update over m29 (versionCode 37).
- `godhand-0.31-m30-vblank.zip` — 35,205,488 B, md5
  `8920166dba5dcb271b8db1d157afd2e1`; contains the single APK;
  `unzip -t` clean, no errors.
- `zipalign -c -p 4` passes on the final APK.
- Signer: apksigner **v2 + v3 verified, 1 signer** — DN
  `CN=Probe, O=ptraaa, C=ID`, cert SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — the same shared debug signer as b5/m26/m27/m28/m29, so the
  update installs in place.

## The lines that arbitrate the device run

Same protocol as m27/m29 (cool phone, wide off, pipeline on at
depth 2, the b5 route into a crowd). Baselines:
M27-LOG-ANALYSIS.md and M29-LOG-ANALYSIS.md; stage expectations:
PATH-VBLANK.md §5 and §7. If the boot does not show the config
proof lines, stop — the run measures nothing.

- **Config proof (unchanged from m29):** the boot must print
  `native: path B variant: PS2X_GS_RENDERER=pcsx2 (variant
  default; no renderer setting in the external ...)` and
  `[BOOT] settings: env: PS2X_GS_RENDERER=pcsx2 (wins over
  godhand.ini native)` — the internal ini will still say native;
  the env line winning over it is the proof. It must also print
  `native: path B variant: PS2X_VU1_ASYNC=1 (variant default;
  ...)` and `[pcsx2-gs] pipeline on: ... depth 2 fields`. There
  must be **no** `path B variant: PS2X_WIDESCREEN` and no
  `PS2X_GS_WARMUP` seed line.
- **`[vu1] joins:` — the conviction line.** `regread` stays
  **0.0 ms** (live-regs non-regression). The m29 crowd signature
  — `idle` 664–1,589 ms per 5 s window — must *move*: residual
  `idle` collapses to the pacing-only scale (near zero in
  crowds, where the EE runs behind wall clock), and the barrier
  wait that remains appears as the new **`barrier=`** figure,
  expected to be a **fraction of the old `idle`** (the previous
  field's tail only, per PATH-VBLANK §5 — it is not required to
  reach light-window levels to count as the predicted shape).
- **`[vu1] join_wait` total:** crowd windows fall from
  671–1,590 ms toward **< ~150 ms per 5 s window**; light
  windows stay at the m29 scale (~6 ms) or below.
- **Fields/s:** crowd `[ee-work]` windows rise from 31–51
  toward **55–60**; light windows unchanged (57.9–59.9).
  `[pathb] pipeline: ahead_max` touches 2 in crowd windows;
  `dropped_captures` stays 0.
- **Judder watch (PATH-VBLANK §5's named risk):** the bound
  trades the full drain for at most one field of decoupling;
  if God Hand's pacing keys off vblank-vs-FINISH ordering more
  tightly than the stage's correctness argument credits, the
  symptom is a *new* judder pattern at similar fields/s, not a
  clean gain. Arbitrate on `[frame-split]` **present-age
  variance** — a bimodal early/late spread (the +0.01/+0.97
  slip in miniature) convicts the bound even if the mean
  improves; a tight present-age distribution at higher fields/s
  clears it.
- **Audio:** no separate metric; choppiness should track the
  crowd fields/s rise and the join_wait collapse.
- **Kill switches / A/B (all in the external `godhand.ini`):**
  `PS2X_VU1_VBLANK_BOUND=0` restores the m29 barrier exactly
  (expect `idle` to re-absorb the wait and `barrier` to read
  0); `PS2X_VU1_LIVE_REGS=0` restores the draining register
  reads (re-isolates the previous stage); `PS2X_GS_PIPELINE=0`
  re-isolates the bridge stage. The deliberate road back to the
  native renderer is unchanged from b5: a `PS2X_GS_RENDERER=
  native` line in the **external** `godhand.ini`.
