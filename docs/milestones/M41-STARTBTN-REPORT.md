# M41 START-BUTTON + BEST-DEFAULTS REPORT — a proper START in every control style, widescreen on by default (0.42-m41-startbtn, versionCode 49)

Scope: SHELL ONLY. The native core is untouched: all three libraries
are byte-identical to the delivered m40/m39 APKs (arbitration in §4).

Pablo's orders implemented here:
- 2026-11-11 03:53 — every control style must carry a real START
  button: players use Start to skip cutscenes, so it cannot be a
  tiny afterthought (m40 shipped Start as a 64×36 "PAUSE" pill at
  11sp, top centre next to EDIT — exactly the afterthought he means).
- 2026-11-11 (follow-up) — best settings as defaults: widescreen
  (true 16:9) defaults ON, render scale defaults 1x, and every other
  user-facing default audited to the best known value for a POCO
  F5-class phone, consistent with the m39 research-backed fast
  configuration seeded in the core.

Outputs:
- `godhand-0.42-m41-startbtn.apk` — 140,502,847 B, md5 `7f04adfea6ba1159722156b25f2cf4de`
- `godhand-0.42-m41-startbtn.zip` (APK alone inside) — 35,869,734 B, md5 `0927f457938b8057bb492336cb499cf7`

Workbench: `m41startbtn-build-work/` (`package_m41.py` — the whole
lane in one file: javac + d8, aapt2, stage, align, sign; javac/d8
logs, `sources.txt`, stage/). Sources changed:
`TouchOverlay.java` (START button in all three scheme builders),
`LauncherActivity.java` (widescreen default + version strings),
`GameActivity.java` (widescreen default + version strings),
`IsoHolder.java` (widescreen pre-read initializer), and
`AndroidManifest.xml` (version 49 / 0.42-m41-startbtn).

---

## 1. The START button

One change, made identically in all three scheme builders of
`TouchOverlay` (methods `buildSchemeA`, `buildSchemeB`,
`buildSchemeC`): the m40 registration

```
register("a_pause", gameButton(context, "PAUSE", false, "btn_start", false, 11), 64, 36,
        new Anchor(CENTRE_TOP, 10, 14));
```

becomes

```
register("a_start", gameButton(context, "START", false, "btn_start", false, 14), 100, 48,
        new Anchor(CENTRE_TOP, -50, 8));
```

(and likewise `b_start` in scheme B, `c_start` in scheme C). What
that means, point by point:

- **Same input, same path.** `gameButton(...)` is the unchanged m39/
  m40 construction: a real native `android.widget.Button` with the
  shared StateListDrawable look (accent fill while pressed), a
  VIRTUAL_KEY haptic tick on press, and hold-to-send semantics on
  the `btn_start` touch control, resolved through the owner's
  PadMapping remap table exactly like m40's PAUSE did. Multi-touch
  is the platform's per-view pointer routing, the same as every
  other button: the button consumes only the pointer that lands on
  it, so it fires while the stick and fight buttons are held.
- **It supersedes PAUSE — there is exactly one Start surface per
  scheme.** The old registrations are deleted, not supplemented:
  the `"PAUSE"` label literal no longer exists anywhere in the dex
  (§4). The layout ids were renamed (`a_pause` → `a_start`, etc.);
  the old ids stay in `TouchOverlay.ALL_LAYOUT_IDS`, so the
  launcher's "Reset button layout" still clears any stale m40
  positions. Deliberate consequence: a position an owner dragged
  m40's small PAUSE pill to is NOT inherited by the new button —
  everyone gets the designed placement below for START in m41
  (and can drag it in EDIT mode, which persists under the new ids).
  Every other control's saved position is untouched.
- **Size.** 100×48 dp at 14sp bold — the same height class as the
  fight pills (DODGE 120×48, GOD HAND/ROULETTE 118×46), ~1.9× the
  area of the 64×36 pill it replaces, and it scales with the
  owner's Button size setting and fades with the Button opacity
  setting, because it goes through the same `register()` /
  `buttonBackground()` machinery as every other control.
- **Placement — top centre, exactly centred, in all three
  schemes.** The CENTRE_TOP anchor puts a view's left edge at
  screen-centre + mx, so mx = −50 centres the 100dp-wide button on
  the screen's vertical midline; top margin 8dp. Its bottom edge
  (8 + 48 = 56dp) stays clear of the FPS label (top margin 58dp),
  and its right edge (centre + 50dp) clears the EDIT pill
  (centre + 94dp, y 14dp). Top centre is the classic mobile
  pause/start spot: reachable by either thumb without crossing the
  screen, and it touches neither fight cluster.

Per scheme:

| Scheme | Where START sits | Why it is safe there |
|---|---|---|
| A Brawler Clean | Top centre (100×48, centred, 8dp from the top) | The fight cluster is bottom-right, the specials stack starts 118dp down the right edge, TURN is bottom-left — the top-centre strip holds only START and the EDIT pill, 44dp apart. |
| B Gesture Evade | Same top-centre button, registered after the evade zone | It sits above the zone in z-order and consumes its own touches, exactly like B's other buttons — a tap on START can never start a flick, and flicks in play start on the empty right-side screen, not the top-centre strip. |
| C Radial Pro | Same top-centre button | The SPECIAL hub and its radial menu live at the bottom-right (hub 96dp from the corner, menu drawn around the hub); top centre is as far from them as the screen allows. |

- **Both app modes.** The overlay is a gameplay surface: Game
...[truncated 8188 chars]Activity builds
  the overlay the same way in Player and Developer mode (the mode
  gates launcher surfaces and the FPS label only — both untouched),
  so START is present in every scheme and every mode.

---

## 2. Best settings as defaults — the audited table

Every user-facing setting the launcher exposes was inventoried
(LauncherActivity's settings keys, consumed by GameActivity into
IsoHolder / the overlay) and each fallback default checked against
the m39 fast configuration and this phone class. Exactly one
default changed; one was verified already-correct per Pablo's
requirement; the rest were audited and deliberately kept, with
reasons:

| # | Setting (launcher label) | Key | Old default | New default | Reason |
|---|---|---|---|---|---|
| 1 | Widescreen 16:9 | `set_wide` | OFF | **ON** | Pablo's explicit m41 order: true 16:9 is the app's shipped face. The B7 off-default was a 4:3-performance-floor precaution from the pathb5 era; his standing requirement is speed and true widescreen together. Changed at all three read sites (launcher toggle, launcher row label, GameActivity) plus IsoHolder's pre-read initializer (`"off"` → `"on"`). |
| 2 | Internal resolution | `set_scale` | 1x | 1x (unchanged — verified) | Pablo's requirement is 1x by default: audited, and it already is — the fallback is `"1"` at all three read sites (launcher cycle row, launcher label, GameActivity) and in IsoHolder's initializer. Matches the core's own `PS2X_GS_SCALE` 1x default (M39 core report, item 7). Nothing in the shell ships defaulting higher. |
| 3 | VSync | `set_vsync` | ON | ON (unchanged) | The tear-free standard; nothing in the m39 research configuration calls for vsync off, and tearing is a worse shipped face than the negligible cost. |
| 4 | FPS counter | `set_fps` | ON | ON (unchanged) | Since m40 this is a Developer-mode-only surface (Player mode forces the in-game label off in GameActivity regardless), so the default costs a player nothing; for the owner it is the primary on-device performance evidence. |
| 5 | Thread placement (performance cores) | `set_threads` | OFF | OFF (unchanged) | The Milestone 5 governor was explicitly returned to opt-in in Milestone 6 "until it is proven on device"; it is still unproven and is not part of the research-backed fast config. Shipping it on by default would contradict the recorded evidence rule. |
| 6 | Disc prefetch (experimental) | `set_prefetch` | OFF | OFF (unchanged) | Experimental since M7: a whole-ISO background read costs RAM and battery on a phone, and the internal ISO copy (M3) already removed the descriptor bottleneck it compensated for. Not in the fast config. |
| 7 | GPU renderer (experimental) | `set_gpu` | OFF | OFF (unchanged) | Experimental since M10: its GL context creation is Windows-only in this tree, while the shipped graphics path is the Path B PCSX2 bridge, which this toggle does not select. On-by-default would ship an unproven renderer. |
| 8 | Button opacity | `set_opacity` | high | high (unchanged) | Best visibility for the schemes' labelled buttons (including the new START) over bright game scenes; a comfort setting the owner can lower. |
| 9 | Button size | `set_btnsize` | 100% | 100% (unchanged) | All three schemes — and the new 100×48 START — were designed and laid out at exactly this scale. |
| 10 | Control style | `set_control_style` | A — Brawler Clean | A (unchanged) | The designer's recommendation from the design note; the per-scheme device verdict is still Pablo's to give, and the choice is one tap in the launcher. |
| 11 | Mode | `set_mode` | Player | Player (unchanged) | Pablo's own m40 design: the clean player face by default, Developer strictly opt-in. |
| 12 | Touch + gamepad remap tables | PadMapping keys | standard layout | standard layout (unchanged) | Not a scalar default: with nothing stored, every control sends its namesake PS2 input — the known-good mapping all three schemes were designed around. |

Not shell settings, and therefore not in the table: the m39 fast
configuration itself — async 48 kHz audio, GS readbacks off
(`hw_download=2`), blending Minimum, SPU2 nearest interpolation
with reverb off. Those are compiled-in core variant seeds with
godhand.ini kill switches (see M39-CORE-REPORT.md); the shell has
no toggles for them, duplicates none of them, and contradicts none
of them.

## 3. Defaults semantics — saved values are never overridden

Every setting above is read through the SharedPreferences
fallback form, `prefs.getBoolean(key, DEFAULT)` /
`prefs.getString(key, DEFAULT)`. m41 changed only fallback
arguments (and IsoHolder's matching pre-read initializer for
widescreen). Nothing in this build writes, migrates, seeds or
clears a preference at install, first run or any other time:

- **Fresh install / a setting never touched:** the new defaults
  apply — widescreen ON, scale 1x, and the audited values above.
- **Existing install (including Pablo's phone):** any value already
  saved is read back exactly as stored and wins over every default
  in the table. If widescreen was deliberately switched off on an
  m40 install, it stays off after this update until the owner
  changes it; his tuned scale, opacity, size, mode, style and
  remaps all survive untouched.

---

## 4. Verification gates — all PASS

- **Host compile clean**: javac (source/target 8, android-36 jar)
  exit 0 with only the two standard notes (the bootstrap-classpath
  warning and the pre-existing LauncherActivity deprecation note —
  byte-for-byte the same javac output as m40). d8 exit 0, silent.
  classes.dex 85,308 B, md5 `d3150b652449cf79266a4709b3a22cc9`
  (m40: 85,240 B).
- **Badging** (aapt2 dump badging): `package: name='dev.ptraaa.
  godhand' versionCode='49' versionName='0.42-m41-startbtn'`,
  minSdk 26, targetSdk 35.
- **Signature**: `apksigner verify` passes (v2 + v3, 1 signer);
  signer certificate SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — identical to the delivered m40 APK, so m41 installs over m40.
  `zipalign -c -p 4`: OK.
- **Per-entry arbitration vs `godhand-0.41-m40-controls.apk`**
  (16 non-META-INF entries in both, identical name sets): exactly
  two entries differ —
  - `classes.dex` (85,240 → 85,308 B) — the m41 shell, intended;
  - `AndroidManifest.xml` (2,544 → 2,544 B) — proven version-only
    by byte diff: exactly 11 bytes differ, 10 of them the
    versionName string-pool characters (`0.41-m40-controls` →
    `0.42-m41-startbtn`, same length) and 1 the versionCode
    integer (48 → 49).
  Everything else is byte-identical, including `resources.arsc`,
  all `res/` icon entries, and the three libraries:
  - `lib/arm64-v8a/libgodhand.so` md5 `2f21b07128c4a02916995af089871ab1` ✓ (required value)
  - `lib/arm64-v8a/libpcsx2gs.so` md5 `d14eedbd247e7d7a16dc5975b39c60b8` ✓ (required value)
  - `lib/arm64-v8a/libshaderc_shared.so` md5 `2793f5b6394868783852325a4295e1f2` ✓ (unchanged from m39/m40)
  (The libs were staged out of the delivered m40 APK itself, so
  this is identical by construction as well as by measurement.)
- **Dex evidence for START in all three schemes** (`strings` on
  the built dex): the `START` label literal is present; the layout
  ids `a_start`, `b_start`, `c_start` are all present; the old
  `PAUSE` label literal is absent (0 occurrences); the version
  literal `0.42-m41-startbtn` appears in 5 strings. In source,
  the wiring is one registration per scheme builder in
  `TouchOverlay` — `buildSchemeA` (line 293),
  `buildSchemeB` (line 338), `buildSchemeC` (line 393) — each
  `register("X_start", gameButton(context, "START", false,
  "btn_start", false, 14), 100, 48, new Anchor(CENTRE_TOP, -50, 8))`.
  On device, the boot log's overlay-layout line will list
  `a_start=` / `b_start=` / `c_start=` with the button's bounds,
  as it listed `a_pause=` under m40.

## 5. Honest limitations

- **Host-side proof only, as with m39/m40.** The START button
  compiles, packages and follows the platform's touch rules, but
  its feel — thumb reach at top centre during a cutscene, presses
  landing while other fingers hold the stick and fight buttons —
  is proven only by Pablo's device test. If the spot fights him,
  it drags in EDIT mode like every other control.
- **At button sizes above 100% the top strip gets crowded.**
  START grows with the Button size setting from its top-centre
  anchor; at 125–140% its lower edge can reach the FPS label's
  band (58dp) when the FPS label is shown (Developer mode only).
  Cosmetic stacking only — the label draws above and both stay
  readable — and the designed 100% size has a clean 2dp gap.
- **Widescreen now defaults ON, with B7's caveat inherited.**
  The B7 decision made 4:3 the default because the pathb5-era
  performance floor was proven there first. m41 reverses the
  default on Pablo's explicit order, and the kill switch is the
  same as always: the launcher toggle, or a saved preference —
  which, per §3, an existing install keeps.
- Release stays ON HOLD per Pablo, as before. Performance and
  audio are untouched by this build (the core is m40's, byte for
  byte).
