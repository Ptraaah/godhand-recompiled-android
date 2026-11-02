# God Hand Extractor — one-tap generation-pack extractor (Milestone 1b)

Date: 2026-11-01. APK: `package/godhand-extractor.apk` (25,003 bytes,
package `dev.ptraaa.godhandextract`, versionName `0.1`, label
"God Hand Extractor", minSdk 26 / targetSdk 35, **no permissions, no
native code**, signed with the same debug keystore as the JFG builds —
signer SHA-256 `9791d26c…dbc9005e`, apksigner v2+v3 verified).

Purpose: Milestone 1a (`M1A.md` section 3) showed generation needs only
~9 MB out of the 1.57 GB disc — the boot ELF, GODHAND.FST, and the 81
`.rel` overlays inside _DATA.AFS — but extracting them on the phone was
the missing piece. This app is that piece: the owner taps one button,
picks his own game copy, and gets one small zip to upload. No Termux,
no scripting, nothing typed.

## What it does

1. The owner picks one of two inputs (two buttons, nothing else):
   - **"Pilih ISO game"** — the God Hand (USA) ISO through the system
     file picker (it lives in his `Download/God Hand (USA)` folder).
   - **"Pilih folder cache"** — an already-extracted copy of the game
     through the folder picker, if one is visible to the picker (the
     launcher's extracted copy in shared storage qualifies; the copy
     inside the other app's private storage does not — Android hides
     that from every picker, which is why the ISO path is the primary
     one).
2. The app reads the three generation inputs out of that source,
   **verifies SLUS_215.03 first**: exact size 3,607,008 bytes and
   SHA-256 `1742F95B…A64F3CD` — the same check `build.py` and the port
   run (M1A.md section 3). A different disc is refused on screen with
   the found vs expected values, and no zip is written.
3. It pulls the 81 `.rel` overlays out of _DATA.AFS (progress shown as
   "Menarik overlay n/81"), builds `manifest.json`, and writes
   **`godhand-genfiles.zip` into the phone's Download folder**
   (MediaStore; on Android < 10 it falls back to the app's own external
   folder and says the full path instead). The final screen states the
   zip's location and size, the overlay count, and the ELF hash result.

Zip layout (mirrors the tree `build.py` consumes):

```
SLUS_215.03
GODHAND.FST
rel/manifest.json
rel/<81 .rel files, loose, by name>
```

## The parsers are ports of the snr2 tooling, not rewrites

Pure Java (stdlib/Android APIs only), in
`extractor/src/dev/ptraaa/godhandextract/`. Every rule follows the
LasVola sources in `godhand-recomp/tools/snr2/`:

| Java class | Python source it follows |
|---|---|
| `Iso9660Root` | `snr2disc.py` `_read_root` (lines 39-63): PVD at sector 16, `CD001` check, root record at PVD+156 (extent LBA at +2, length at +10), record walk with the zero-byte sector-skip rule, names cut at `;`, latin-1; `read_root_file` (lines 65-68) |
| `AfsArchive` | `snr2disc.py` `Disc.__init__` AFS half (lines 24-35): `"AFS\0"`, u32 count, count × (u32 offset, u32 size) from the AFS start; `read_afs` (lines 70-75) incl. the `off + size > afs_size` overrun error |
| `GodhandFst` | `snr2disc.py` `fst_files` (lines 77-108): `"FST\0"`, strings_off at +8, entries_off at +12, 12-byte entries (type/flags/hint/str_off/value), recursive `walk` with depth cap 16 and the visited-table set; `rel_entries` (lines 110-114): paths ending `.rel` (case-insensitive), sorted by AFS index |
| `GenPack` manifest | `snr2.py` `cmd_extract` (lines 321-340): dict keys `name, fst_path, afs_index, size, sha256` in that order, `json.dump(..., indent=1)` shape, trailing newline — reproduced byte-for-byte (verified below) |
| `GameDisc.fromIso` | `Disc.__init__` (lines 19-35): refuses an image without `_DATA.AFS` in the root; the three root files are windows (extent LBA × 2048, recorded size) into the image |
| `GameDisc.fromFiles` | The folder variant M1A.md section 3 describes: root-file reads become loose-file reads, AFS offsets apply to the loose `_DATA.AFS` directly (a `GODHAND.AFS` name is also accepted, per the cache's naming in TEARDOWN.md section 9) |

Reads go through a tiny positional-read interface (`RandomSource`) so
the same core runs on SAF file descriptors on the phone and on plain
files in the host tests.

## Verification (no device; what is proven and what is not)

Fixture + differential test, all under `extractor/hosttest/`
(`run-tests.sh` reproduces everything):

1. `make_fixture.py` builds a synthetic disc from the documented
   formats only — no game content: an ISO9660 image whose root
   directory spans two sectors with a zero-padding gap (exercises the
   sector-skip rule), a 96-entry AFS with the 81 `.rel` files scattered
   among 15 non-`.rel` entries, and an FST whose tree order deliberately
   differs from AFS index order (exercises the sort), with decoy names
   (`fake.relx`, `notes.txt`) that must be filtered out.
2. The **real `snr2.py extract`** runs on that fixture as the reference.
3. The app's Java core, compiled on the host JDK, runs the full GenPack
   pipeline on the fixture twice — once through the ISO path, once
   through the loose-folder path. Result: **every output byte matches
   the reference** — `manifest.json` byte-identical, all 81 `.rel`
   files byte-identical, zip contains exactly the 84 expected entries,
   ELF/FST bytes round-trip unchanged. "ALL HOST TESTS PASSED".
4. Refusal paths, also tested: the fixture ELF against the app's real
   expected size/hash → refused; right size with a wrong hash →
   refused; an AFS entry with an inflated size → loud failure
   ("AFS entry 0 overruns _DATA.AFS"), never a truncated pack.
5. The APK itself: aapt2/javac/d8/zipalign/apksigner pipeline (same
   no-Gradle method as the JFG APKs, `extractor/build-apk.sh`),
   signature verified, signer identical to the JFG builds.

What only the owner's real disc can prove: that his ISO's root layout
and AFS/FST match the shapes snr2disc documents (they are the exact
shapes the private launcher already extracts successfully on his
phone, and the hash gate refuses anything unexpected before a zip is
written), and the SAF/MediaStore behaviour on his Android 15 build.
If his disc were somehow a different pressing, the app says so on
screen instead of producing a bad pack — that is the failure mode by
design.

## Owner steps (plain words)

1. Install `godhand-extractor.apk` (it sits next to the other builds;
   same signer, nothing to uninstall first).
2. Open "God Hand Extractor". Tap **Pilih ISO game** and pick the God
   Hand (USA) ISO from the Download folder.
3. Wait while it counts the overlays up to 81/81.
4. The screen ends with "Selesai" and the zip size. The file
   `godhand-genfiles.zip` is now in the Download folder.
5. Upload that one zip in chat. Done — nothing else to send.

(If the ISO is gone but the extracted copy in `Download/God Hand (USA)`
is still there, tap **Pilih folder cache** instead and pick that
folder — same zip comes out.)
