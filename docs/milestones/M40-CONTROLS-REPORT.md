# M40 CONTROLS REPORT — all three Android-native control styles + Player/Developer mode (0.41-m40-controls, versionCode 48)

Scope: SHELL ONLY. The native core is untouched: all three libraries
are byte-identical to the delivered m39 APK (arbitration in §5).

Pablo's decisions implemented here:
- 2026-11-11 03:21 — do not pick one control design: build ALL THREE
  mockup schemes (A Brawler Clean, B Gesture Evade, C Radial Pro, per
  `control-mockups/DESIGN-NOTE.md`) into the app as a player-selectable
  Control Style, so each player chooses what fits them.
- 2026-11-11 03:24 — a Mode setting: Player mode (default) is the
  clean player-facing game; Developer mode shows the log and
  everything technical.

Outputs:
- `godhand-0.41-m40-controls.apk` — 140,502,847 B, md5 `d53ea0f025c2007bd211c67cf61286f8`
- `godhand-0.41-m40-controls.zip` (APK alone inside) — 35,869,748 B, md5 `518e05bea9416cdcc92722b4bb013a84`

Workbench: `m40controls-build-work/` (`package_m40.py`, javac/d8
logs, `sources.txt`, stage/). Sources changed:
`TouchOverlay.java` (rewritten around the three schemes),
`LauncherActivity.java` (Control Style + Mode settings, mode gating,
reset covers all schemes), `GameActivity.java` (hands style + mode to
the overlay; FPS label is developer-only), `AndroidManifest.xml`
(version 48 / 0.41-m40-controls).

---

## 1. Control Style setting

Launcher → CONTROLS card → "Control style: Brawler Clean" row cycles
**Brawler Clean → Gesture Evade → Radial Pro**. Preference key
`set_control_style` (`"a"`/`"b"`/`"c"`, same SharedPreferences string
pattern as the other settings), **default `a` / Brawler Clean** (the
designer's recommendation). `GameActivity` reads it at game start and
hands it to the `TouchOverlay` constructor; switching takes effect on
the next game start. The boot log records it twice ("settings loaded:
… controlStyle=b", and the overlay layout line starts `style=b …`),
so a returned log proves which scheme a test ran.

All three schemes feed the **same PadMapping inputs** the m39 overlay
produced — the labelled buttons are the old touch controls under
player names, resolved through the owner's remap table exactly as
before, so remapping and the physical-gamepad path are unchanged:

| Player name | Touch control | PS2 function |
|---|---|---|
| HIT | btn_square | combo attack chain |
| ATK 2 | btn_triangle | technique 3 |
| ATK 3 | btn_cross | technique 2 |
| USE | btn_circle | pick up / throw / reaction |
| TURN | btn_l1 | 180° turn |
| TAUNT | btn_l2 | taunt (trigger axis, as in m39) |
| ROULETTE | btn_r1 | roulette open / cycle |
| GOD HAND | btn_r2 | unleash (trigger axis, as in m39) |
| MAP | btn_select | map |
| PAUSE | btn_start | pause |

(ATK numbering follows the design note: ATK 2 is the second technique
slot. The note's table lists Technique 2 = Cross and Technique 3 =
Triangle; the mockups place ATK 2 upper and ATK 3 lower in the
cluster, so ATK 2 = Triangle and ATK 3 = Cross — a naming-vs-position
mismatch inherited from the mockups. The functions are all present
and remappable; only the label order differs from the note's table.)

A **dodge** is a synthetic right-stick flick: `TouchPad.setRightStick`
pulsed in the dodge direction for ~140 ms, then centred — exactly
what m39's visible right stick produced when flicked. Direction
semantics are the game's own: flick up = duck, down = backflip,
left/right = side dodges.

Every scheme drops the visible right stick, the D-pad cluster and
the L3/R3 buttons, per the design note. Nothing is lost:
- **Menus** still navigate — `TouchPad.setLeftStick` derives D-pad
  presses from a firmly tilted move stick (pre-existing behaviour,
  unchanged), and face buttons confirm/cancel.
- **L3/R3** have no dedicated on-screen button (the design note drops
  them; the game never uses them meaningfully). They remain reachable
  through the launcher remap table (any touch control can be pointed
  at L3/R3) and through a physical gamepad.

## 2. Scheme A — Brawler Clean (default)

Floating move stick (shared view/position across schemes). Controls
(layout ids `a_*`, dp before the owner's size scale):

| Control | Surface | Size | Default anchor |
|---|---|---|---|
| HIT | native Button, round | 80×80 | bottom-right (36, 40) |
| ATK 2 | native Button, round | 62×62 | above-right of HIT (128, 108) |
| ATK 3 | native Button, round | 58×58 | below-right of HIT (134, 30) |
| USE | native Button, round | 62×62 | upper-left of cluster (216, 96) |
| DODGE | native Button, pill | 120×48 | bottom centre-right (250, 18) |
| GOD HAND | native Button, solid accent pill | 118×46 | right stack top (24, 118 from top) |
| ROULETTE | native Button, pill | 118×46 | right stack (24, 174 from top) |
| TURN | native Button, pill | 92×40 | above move stick (36, 196 from bottom) |
| TAUNT | native Button, tiny pill | 64×34 | top-left under HUD (24, 84 from top) |
| MAP | native Button, tiny pill | 56×34 | next to TAUNT (98, 84 from top) |
| PAUSE | native Button, pill | 64×36 | top centre (next to EDIT) |

DODGE direction = the move stick's direction at the tap (dominant
axis); a centred stick (< 0.35 deflection) backflips. ROULETTE is a
held-for-as-long-as-pressed R1: tapping opens the game's own wheel,
tapping again cycles it, ATK 3 (Cross) confirms — the original
R1-cycle + Cross-confirm flow on labelled buttons. The shell cannot
draw its own wheel (it cannot see the game's roulette state), so the
mockup's "tap the move you want" is realised through the game's wheel
UI itself; see §6.

## 3. Scheme B — Gesture Evade

Move stick + attack trio + USE (cluster floated up), bottom row
TAUNT / ROULETTE / GOD HAND pills, TURN pill above the stick, PAUSE
top centre. **No dodge button, no right stick.** Controls (`b_*`):

| Control | Surface | Notes |
|---|---|---|
| EVADE ZONE (`b_zone`) | custom view, right 46% of the screen, full height | flick = dodge |
| HIT / ATK 2 / ATK 3 / USE | native Buttons, round | 80 / 62 / 58 / 62 dp, cluster bottom-right, floated above the bottom row |
| TAUNT / ROULETTE / GOD HAND | native Button pills, 92/112/118×46 | bottom row, right side |
| TURN | native Button pill 92×40 | above move stick |
| PAUSE | native Button pill 64×36 | top centre |
| Radar target (`b_radar`) | transparent custom view, 96×96, top-right corner | tap = MAP (SELECT pulse) |

Flick recognition (`EvadeZoneView`): a dodge fires only when a touch
travels **≥ 56 dp within ≤ 450 ms at ≥ 0.30 dp/ms average speed** —
checked during the move and again at release, once per touch. A tap,
a press-and-hold, or a slow drag never dodges. Direction is the
gesture's dominant axis (up duck / down backflip / left / right side
dodge). The zone is added **below** every button in z-order, and
Android gives each pointer to the view it lands on, so a flick can
only start on empty screen: attack taps that land on buttons are
consumed by the buttons and never reach the zone. The converse risk
is real and is stated in §6.

The zone paints the mockup's hint (very faint dashed outline + ghost
circle with four arrows + "FLICK TO DODGE"). The design note's
fade-after-learning is NOT implemented: the shell has no signal for
"the player has learned it", so the hint stays, at ~10–25% opacity.

Map has no button in B (per the design): the transparent radar target
sits over the corner where the game draws its own radar; a tap
(< 300 ms, < 24 dp of movement) pulses SELECT. It draws nothing in
game — the game's radar art is the affordance — and outlines itself
in EDIT mode so it can be found and dragged.

## 4. Scheme C — Radial Pro

Fight buttons on the right-thumb arc + one SPECIAL hub (`c_*`):

| Control | Surface | Notes |
|---|---|---|
| SPECIAL hub (`c_hub`) | custom round view, 96×96, right side (96, 96 from bottom-right) | hold + swipe + release |
| HIT | native Button, round 78 | lower-left of hub (196, 52) |
| ATK 2 | native Button, round 62 | upper right edge (24, 205) |
| ATK 3 | native Button, round 58 | bottom-right corner (20, 20) |
| USE | native Button, round 62 | upper-left of hub (212, 168) |
| DODGE | native Button pill 120×48 | bottom centre (300, 16), same stick-direction rule as A |
| PAUSE | native Button pill 64×36 | top centre |

Holding the hub opens a radial menu (a full-screen, non-touchable
drawing layer — the hub keeps its pointer) centred on the hub: four
90° wedges with a 40 dp dead radius. Swipe direction arms a wedge
(haptic tick on change, wedge highlights accent); release fires it
as a ~140 ms pulse; release inside the dead radius or a cancel fires
nothing.

| Wedge | Fires | Control |
|---|---|---|
| UP | ROULETTE | btn_r1 pulse |
| RIGHT | GOD HAND | btn_r2 pulse |
| DOWN | TAUNT | btn_l2 pulse |
| LEFT | MAP | btn_select pulse |

The 180° turn is a **tap on the move-stick knob**: `StickView` gained
an optional tap action — a touch released within 260 ms whose knob
deflection never reached 0.25 pulses L1. A press that turns into
movement, or a held press, never fires it. (Dash is unaffected: it is
the game's own reading of quick stick deflections, which are above
the tap threshold.)

## 5. Mode setting (Player / Developer)

Launcher → CONTROLS card, first row: **"Mode: Player"** ⇄
**"Mode: Developer"**. Preference key `set_mode` (`"player"` /
`"developer"`), **default Player**. The row lives in the CONTROLS
card, which Player mode keeps, so the way back to Developer is always
visible. Switching re-renders the launcher immediately
(`applyModeVisibility()` on the same click; no restart, and it only
reads/writes its own key, so no other preference can be disturbed).
`GameActivity` also reads the mode: in Player mode the in-game FPS
label is forced off (`fpsOn = set_fps && developerMode`), and the
boot log's settings line records `mode=` + `controlStyle=`.

Per-mode visibility inventory:

| Surface | Player (default) | Developer |
|---|---|---|
| Hero header (icon, name, version) | shown | shown |
| GAME STATUS card | shown | shown |
| PLAY button + Pick ISO | shown | shown |
| PERFORMANCE — Internal resolution | shown | shown |
| PERFORMANCE — Thread placement | **hidden** | shown |
| PERFORMANCE — Disc prefetch | **hidden** | shown |
| PERFORMANCE — GPU renderer | **hidden** | shown |
| DISPLAY — FPS counter toggle | **hidden** | shown |
| DISPLAY — VSync / Widescreen / Opacity / Button size | shown | shown |
| CONTROLS — Mode + Control Style + Reset layout + hint | shown | shown |
| REMAP TOUCH BUTTONS card | **hidden** | shown |
| REMAP GAMEPAD BUTTONS card + Reset mapping | **hidden** | shown |
| BOOT LOG card (log text, Share, Reload) | **hidden** | shown |
| In-game FPS label | **hidden** (forced) | follows the FPS toggle |

Gating evidence (host-side): `LauncherActivity.applyModeVisibility()`
reads `KEY_SET_MODE` and sets `View.GONE`/`VISIBLE` on
`threadsButton`, `prefetchButton`, `gpuButton`, `fpsButton`,
`touchRemapCard`, `padRemapCard`, `resetMappingButton`, `logCard`;
the dex contains `set_mode`, `Mode: `, and `Player`/`Developer`
strings (see §7 string check). Hidden is GONE, not disabled: in
Player mode those views occupy no space and cannot be reached.

Judgement calls, stated plainly: the remap cards and the FPS toggle
are classed as technical (raw input tables and a diagnostic readout);
button layout reset stays in Player mode because layout is a player
comfort feature of the new schemes; resolution/VSync/widescreen/
opacity/size stay because they are the player-facing graphics and
comfort settings.

## 6. Edit mode, per scheme

EDIT/DONE and RESET work as in m39, per scheme: positions persist
under per-scheme layout ids (`lay_a_hit_x`, `lay_b_zone_x`, …); the
move stick's position is shared by all schemes. The launcher's
"Reset button layout" now iterates `TouchOverlay.ALL_LAYOUT_IDS`
(all three schemes + the retired m39 ids), and the overlay's own
RESET clears the current scheme's ids + the shared stick.

- Schemes A and C: every button drags, as in m39 (amber-rim restyle
  in edit mode for pad buttons).
- Scheme B: all buttons drag; the evade zone also drags (it outlines
  itself amber and labels itself in edit mode) — only its position
  moves; its size stays 46% × full height, there is no resize.
- Scheme C: the hub drags like any other control (it outlines amber;
  its own hold/swipe handling is suspended while the drag listener
  owns its touches). The radial menu is not a control and is never
  shown in edit mode.
- Scheme B's radar target drags too (it only becomes visible in edit
  mode — see the honesty note in §7).

## 7. Verification gates — all PASS

- **Host compile clean**: javac (source/target 8, android-36 jar)
  exit 0 with only the two standard notes (bootstrap classpath
  warning; the pre-existing LauncherActivity deprecation note). d8
  exit 0. classes.dex 85,240 B, md5 `f494d898cc6d56d42b804dbe784fc665`
  (m39: 65,564 B).
- **Dex content evidence** (`strings` on the built dex): `Brawler
  Clean`, `Gesture Evade`, `Radial Pro`, `Control style: `,
  `set_control_style`, `set_mode`, `Mode: `, `0.41-m40-controls`,
  layout ids `a_hit`, `a_dodge`, `b_zone`, `b_radar`, `c_hub`, and the
  scheme literals `SPECIAL`, `FLICK TO DODGE`, `GOD HAND`, `ROULETTE`
  all present.
- **Badging** (aapt2 dump badging): `package: name='dev.ptraaa.
  godhand' versionCode='48' versionName='0.41-m40-controls'`,
  minSdk 26, targetSdk 35, icon resolves at every density.
- **Signature**: `apksigner verify` passes (v2 + v3, 1 signer);
  signer certificate SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — identical to the delivered m39 APK, so m40 installs over m39.
  `zipalign -c -p 4`: OK.
- **Per-entry arbitration vs `godhand-0.40-m39.apk`** (16 non-META-INF
  entries in both): exactly two entries differ —
  - `classes.dex` (65,564 → 85,240 B) — the m40 shell, intended;
  - `AndroidManifest.xml` (2,528 → 2,544 B) — the version bump
    (47 → 48, `0.40-m39` → `0.41-m40-controls`) only, intended.
  Everything else is byte-identical, including `resources.arsc`, all
  ten `res/mipmap-*` icon entries, and the three libraries:
  - `lib/arm64-v8a/libgodhand.so` md5 `2f21b07128c4a02916995af089871ab1` ✓ (required value)
  - `lib/arm64-v8a/libpcsx2gs.so` md5 `d14eedbd247e7d7a16dc5975b39c60b8` ✓ (required value)
  - `lib/arm64-v8a/libshaderc_shared.so` md5 `2793f5b63948…` — unchanged from m39 ✓
  (The libs were staged out of the delivered m39 APK itself, so this
  is identical by construction as well as by measurement.)

Per-scheme control inventory (every m39-overlay function, per
scheme) — MOVE: stick (all). DASH: game-side stick read (all).
EVADE ×4: A/C — DODGE pill + stick direction (centred = backflip);
B — zone flicks in 4 directions. HIT/ATK 2/ATK 3/USE: labelled
buttons (all). TURN: A/B pill; C knob tap. TAUNT: A/B pill; C hub
DOWN. ROULETTE: A/B pill (R1 hold semantics); C hub UP pulse. GOD
HAND: A/B accent pill; C hub RIGHT pulse. MAP: A pill; B radar tap;
C hub LEFT pulse. PAUSE: pill (all). Menus: stick-derived D-pad +
face buttons (all, unchanged TouchPad behaviour). EDIT/RESET: top
centre (all). FPS label: developer mode only, as in §5.

## 8. Honest limitations

- **Gesture flick (B) and the radial hub (C) are host-unproven.**
  They compile and follow the platform's touch rules, but only
  Pablo's device test proves recognition quality: whether 56 dp /
  450 ms / 0.30 dp-per-ms is the right flick window for his thumbs,
  whether hub wedge selection tracks a real thumb arc, and whether
  the knob-tap turn in C ever fires accidentally during play.
- **Scheme B's residual risk is the mirror of its protection.**
  Buttons consume their own touches, but a flick that starts on
  empty zone *between* buttons during a fast attack sequence will
  dodge — that is the scheme working as designed, and it is also
  exactly the misread the design note warned about. The thresholds
  are deliberately conservative; if device testing says otherwise,
  they are three constants in `EvadeZoneView`.
- **Multi-touch, per scheme.** A: buttons are separate native views
  (m39 pattern) and the stick tracks its own pointer — stick + HIT +
  DODGE simultaneously should work, still device-unproven as in
  m39. B: the zone tracks ONE pointer; a second finger landing in
  the zone mid-flick is ignored rather than stealing the gesture —
  but holding a finger in the zone blocks flicks from that finger's
  touch stream until it lifts. C: the hub tracks one pointer; while
  it is held, its finger is committed — fight buttons need the other
  fingers, which is the scheme's known cost (the design note's
  unleash-timing warning stands: a hub wedge is hold+swipe+release,
  slower than A's direct R2 pill).
- **Roulette is the game's wheel, not a shell wheel** (see §2): the
  mockup language "tap the move you want" is realised as
  ROULETTE (cycle) + ATK 3/Cross (confirm) on the game's own UI,
  because the shell cannot observe the game's roulette state. In C
  the hub's UP wedge only *opens* the wheel (an R1 pulse); cycling
  and confirming still happen on the fight buttons afterwards.
- **GOD HAND has no gauge awareness**: the shell cannot see the
  tension gauge, so the accent pill is never greyed out; pressing it
  early sends R2 and the game itself ignores it, exactly like the
  PS2 original.
- **B's radar target is a blind overlay**: it assumes the game's
  radar sits in the top-right 96×96 dp corner (true for God Hand's
  HUD). If a game screen moves the radar, the tap still pulses
  SELECT (the map opens regardless of what is drawn beneath) — the
  risk is only that the target area might cover something else on
  non-gameplay screens; in gameplay nothing else lives in that
  corner of the overlay (PAUSE is top-centre).
- **Layout inheritance**: owners who dragged m39's PS2-layout buttons
  keep those saved positions harmlessly (unused by the schemes);
  their dragged move-stick position carries into all three schemes.
  Each scheme's own controls start from the defaults above.
- Release stays ON HOLD per Pablo, as before. Performance and audio
  are untouched by this build.
