# M42 PLAYER-PERF + AUTO-DASH REPORT — Player mode runs the performance options for the player, and a full forward push dashes (0.43-m42-playerperf, versionCode 50)

Scope: SHELL ONLY. The native core is untouched: all three libraries
are byte-identical to the delivered m41 APK (arbitration in §5).

Pablo's orders implemented here:
- 2026-11-11 04:35 — in Player mode the performance features must be
  active for the player automatically, everything usable. (m41's
  Player mode only hid the toggles; GameActivity read the stored
  values straight from prefs with no mode check, so a fresh player
  ran with thread placement and disc prefetch OFF.)
- 2026-11-11 04:39 — auto-run on full forward: "pas tekan maju
  otomatis itu langsung gerak cepat, jadi ga perlu flick 2x lagi" —
  pushing the move stick forward must start the fast movement at
  once, with no double flick (§3).

Outputs:
- `godhand-0.43-m42-playerperf.apk` — 140,506,943 B, md5 `0dee15e5670138b421b5429dad268777`
- `godhand-0.43-m42-playerperf.zip` (APK alone inside) — 35,870,681 B, md5 `10ba7dcd64915a3a5bffbe2727ceab63`

Workbench: `m42playerperf-build-work/` (`package_m42.py` — the
whole lane in one file, adapted from m41's script: javac + d8,
aapt2, stage, align, sign; javac/d8 logs, `sources.txt`, stage/).
Sources changed: `GameActivity.java` (mode read moved up, Player
forcing at the read site, effective-value log line, version
strings), `LauncherActivity.java` (the automatic-performance note,
version strings), `TouchOverlay.java` (auto-dash feed on the shared
move stick, version string), and `AndroidManifest.xml` (version
50 / 0.43-m42-playerperf). The libs were staged out of the delivered
m41 APK itself, so they are identical by construction as well as by
measurement.

---

## 1. Player mode forces the performance options ON for the run

In `GameActivity.onCreate`, the app mode is now read FIRST (line
102–103), before the IsoHolder options are computed — m41 read it
afterwards, only to gate the FPS label. The two performance reads
then take the mode into account (lines 112–119):

```java
IsoHolder.optThreadPolicy = (!developerMode
        || prefs.getBoolean(LauncherActivity.KEY_SET_THREADS, false)) ? "auto" : "off";
...
IsoHolder.optDiscPrefetch = (!developerMode
        || prefs.getBoolean(LauncherActivity.KEY_SET_PREFETCH, false)) ? "on" : "off";
```

Semantics, case by case:

| Mode | Stored toggle | Effective value in the run |
|---|---|---|
| Player | off (the default) | **"auto" / "on" — forced** |
| Player | on | "auto" / "on" (same as stored) |
| Developer | off | "off" (stored rules, as m41) |
| Developer | on | "auto" / "on" (stored rules, as m41) |

**Stored preferences are never rewritten.** The forcing exists only
in these two read expressions; `GameActivity` contains no
preference write at all (verified: no `.edit()` / `putBoolean` /
`putString` anywhere in the file). The launcher's toggle handlers
are untouched, and `applyModeVisibility` still only shows/hides
views — so flipping back to Developer mode shows the owner's own
toggle values exactly as he left them, and they rule the next run,
exactly as m41. Pablo's standing rule (experimental toggles stay
switchable, never removed, defaults never stomp saved values) is
preserved in full.

The other options are untouched by the mode: scale, VSync, FPS
(the label itself stays Developer-only, the m40 rule), widescreen —
all read exactly as m41.

## 2. The GPU renderer toggle is deliberately NOT forced

`IsoHolder.optGpuRenderer` (GameActivity line 127) is the m41 line,
unchanged: it follows the stored `set_gpu` value in both modes.
Reason, recorded in the m41 defaults audit (§2, row 7) and restated
in a comment at the read site: this toggle selects an alternate
experimental renderer whose GL context creation is Windows-only in
this tree; it is not a performance booster on the shipped Path B
PCSX2 bridge path, and the shipped graphics do not run through it.
Forcing it "on" in Player mode would risk switching players off the
fast path for zero gain — the opposite of the order's intent. If a
future build makes the toggle select a real faster renderer, the
exclusion should be revisited then.

## 3. Auto-dash on a full forward push (the 04:39 order)

**The trigger, established from the existing stack.** Fast movement
in God Hand is the game's own reading of the left-stick stream: the
m40 report (§4) already records dash as "the game's own reading of
quick stick deflections". Concretely it is a double forward flick —
two quick pushes of the move stick to full forward with a return
toward neutral between them — detected by the game from the stick
values themselves. There is no dash button or flag anywhere in
PadMapping/TouchPad to set instead: the only faithful mechanism is
to put that exact pattern into the left-stick stream, which is what
this build does. (Forward is negative Y in this stack: StickView
passes screen coordinates straight through, and TouchPad derives
DPAD_UP from `y < -0.55`.)

**Where.** The move stick is ONE shared StickView for all three
schemes; its listener (TouchOverlay line 223) now routes every value
through `feedLeftStick` (line 486) before `TouchPad.setLeftStick`.
Per-scheme behaviour is therefore identical in A, B and C by
construction. The physical-gamepad stick path (`setPadLeftStick`)
is not involved — a pad player flicks physically, as before.

**The state machine** (constants at TouchOverlay lines 451–462, the
only dash knobs, all in one place):

| Constant | Value | Meaning |
|---|---|---|
| `DASH_TRIGGER_Y` | −0.85 | Forward axis reaches 85% of full travel: a committed push (within ~32° of straight forward). Walking tilts never reach it. |
| `DASH_REARM_Y` | −0.40 | The dash re-arms only after the stick eases back above 40% forward travel — the hysteresis that keeps one held push at exactly one dash. |
| `DASH_HOLD_MS` | 90 ms | Each synthesized flick holds full forward this long (several game frames, so per-frame pad polling cannot miss the edge). |
| `DASH_GAP_MS` | 70 ms | The neutral return between (and before) the flicks. |

On crossing the trigger line, `startDashSequence` (line 512)
synthesizes BOTH flicks into the stream, then hands it back:

| t | Stream gets | Why |
|---|---|---|
| 0 | neutral (y=0, x live) | the return the first flick needs |
| 70 ms | full forward (y=−1, x live) | flick 1 |
| 160 ms | neutral | the return between flicks |
| 230 ms | full forward | flick 2 — the game starts the run |
| 320 ms | the player's live value | still held forward, so the run continues while he keeps pushing |

Synthesizing both flicks instead of counting the player's push as
flick 1 makes the trigger independent of push speed: a slow ease to
full forward dashes exactly like a fast shove. Properties, each
tied to the code:

- **Once per push, never repeating.** After the sequence the machine
  is latched (state 2); values forward live and no new sequence can
  start until the stick eases past the re-arm line (state 0).
- **Abortable.** Easing off past the re-arm line mid-sequence bumps
  the generation counter (every scheduled step is
  generation-guarded, the flick/pulse pattern) and hands the stream
  straight back to the player.
- **Diagonal-safe.** The x axis always follows the player's live
  value, in the sequence and out of it; the machine only drives y.
- **No interference.** The machine writes only the left stick via
  `TouchPad.setLeftStick`. The dodge synthetics write the right
  stick (A/C DODGE pill, B's evade zone) and the pulses write
  buttons — disjoint state, merged only inside TouchPad's push,
  exactly as before. Scheme C's stick-knob tap (deflection < 0.25)
  and the dash trigger (0.85) cannot overlap. Partial-deflection
  walking is forwarded untouched — the machine only wakes at 85%.
- **Edit mode stands down.** While `TouchPad.isEditing()`, values
  forward raw and the machine resets (TouchPad drops them anyway
  while dragging).
- **Boot-log evidence.** The overlay logs one line at construction:
  `auto-dash (0.43-m42-playerperf): full forward push at y<=-0.85
  synthesizes the double-flick dash; re-arms at y>-0.4; pulse 90 ms
  hold / 70 ms gap`.

**One faithful side effect, stated plainly.** TouchPad derives
D-pad presses from the same left-stick stream (that is how menus
navigate without a D-pad cluster). The synthesized pattern
therefore also produces two brief DPAD_UP releases/presses — which
is exactly what a physical double flick does in a menu too. In a
menu, one full-forward push may step the cursor as if flicked. The
stream was kept faithful rather than special-cased; if it annoys on
device, the fix is a TouchPad variant, not different stick values.

**Honest limit.** Dash FEEL is device-test proof, like the START
button's reach and B's flick zone before it: the pattern, timing
and thresholds are host-derived from the documented trigger, but
only Pablo's phone can say whether 90/70 ms reads as instant and
whether 85% is the right line. All four numbers are named constants
in one block for exactly that adjustment.

## 4. The boot log settings line — effective values, marked

GameActivity's `settings loaded:` line (lines 147–151) now appends
the marker ` (player mode)` to the two forced values whenever the
run is in Player mode (`playerMark` is empty in Developer mode):

- Player mode, stored toggles off (the fresh-player case):
  `settings loaded: scale=1 threads=auto (player mode) vsync=1 fps=on prefetch=on (player mode) gpu=off wide=on mode=player controlStyle=a opacity=high`
- Developer mode (any toggle state): exactly the m41 format, stored
  values bare, e.g. `... threads=off ... prefetch=off ... mode=developer ...`

So a returned log can never read as "off in the launcher but on in
the run" without the reason sitting next to the value. (`fps=on` in
the Player example is the stored toggle value the line has always
printed; the in-game FPS LABEL remains forced off in Player mode by
the separate m40 rule, unchanged.)

## 5. Launcher side

One addition, the judgement call the brief allowed: in Player mode
the PERFORMANCE card shows, under the Internal resolution row and in
the space the hidden toggles leave, one quiet line in the CONTROLS
hint's style (13sp, log grey):

> Performance options run automatically in Player mode.

It is `perfAutoNote` (LauncherActivity: field line 159, created
lines 417–426, visibility line 628–629): VISIBLE only in Player
mode, GONE in Developer mode where the toggles speak for
themselves. No other visibility rule changed — the toggles stay
hidden in Player mode, the Mode row stays reachable in both modes,
and nothing else in the launcher moved.

## 6. Verification gates — all PASS

- **Host compile clean**: javac (source/target 8, android-36 jar)
  exit 0 with only the two standard notes (the bootstrap-classpath
  warning and the pre-existing LauncherActivity deprecation note —
  the same javac output as m40/m41). d8 exit 0, silent.
  classes.dex 87,780 B, md5 `b336172055b94a6a044f8891505fe5ca`
  (m41: 85,308 B — the growth is the dash machine, the forcing
  reads, the log marker and the note view).
- **Badging** (aapt2 dump badging): `package: name='dev.ptraaa.
  godhand' versionCode='50' versionName='0.43-m42-playerperf'`,
  minSdk 26, targetSdk 35.
- **Signature**: `apksigner verify` passes (v2 + v3, 1 signer);
  signer certificate SHA-256
  `9791d26c1a80ea62c2570d54da7f8f6baa0d4e97180eb6ba09c85cc7dbc9005e`
  — identical to the delivered m41 APK, so m42 installs over m41.
  `zipalign -c -p 4`: OK.
- **Per-entry arbitration vs `godhand-0.42-m41-startbtn.apk`**
  (16 non-META-INF entries in both, identical name sets): exactly
  two entries differ —
  - `classes.dex` (85,308 → 87,780 B) — the m42 shell, intended;
  - `AndroidManifest.xml` (2,544 → 2,548 B) — proven version-only
    by semantic diff (`aapt2 dump xmltree` on both): the ONLY
    differing lines are versionCode (49 → 50) and versionName
    ("0.42-m41-startbtn" → "0.43-m42-playerperf"). The byte count
    moved because the new versionName is 2 characters longer and
    shifts the string pool; the decoded tree is otherwise identical.
  Everything else is byte-identical, including `resources.arsc`,
  all `res/` icon entries, and the three libraries:
  - `lib/arm64-v8a/libgodhand.so` md5 `2f21b07128c4a02916995af089871ab1` ✓ (required value)
  - `lib/arm64-v8a/libpcsx2gs.so` md5 `d14eedbd247e7d7a16dc5975b39c60b8` ✓ (required value)
  - `lib/arm64-v8a/libshaderc_shared.so` md5 `2793f5b6394868783852325a4295e1f2` ✓ (required value)
- **Dex evidence** (`strings` on the built dex): the version
  literal `0.43-m42-playerperf` appears in 6 strings (m41's five
  sites plus the new auto-dash boot-log line); ` (player mode)`,
  `Performance options run automatically in Player mode.`,
  `auto-dash (0.43-m42-playerperf): full forward push at y<=`,
  `feedLeftStick` and `DASH_TRIGGER_Y` are all present; the old
  version literal is absent (0 occurrences).
- **Code evidence for §1–§3** (source, this tree): mode read at
  GameActivity lines 102–103; forcing expressions at lines 112
  (threads) and 118 (prefetch); the GPU line at 127 is the m41 line
  verbatim; `playerMark` at line 147, used at lines 149 and 151;
  no preference write exists anywhere in GameActivity (grep for
  `.edit()` / `putBoolean` / `putString`: no matches). Dash:
  constants at TouchOverlay lines 451–462, `feedLeftStick` at 486,
  `startDashSequence` at 512, the shared stick listener routed
  through the feed at line 223.

## 7. Honest limitations

- **Host-side proof only, as with m39–m41.** The forcing is proven
  at the read site by construction and by the log format; the dash
  pattern is proven against the documented trigger. What they FEEL
  like — Player-mode performance with threads auto + prefetch on,
  and the dash firing the instant a thumb commits — is Pablo's
  device test, as always.
- **Thread placement is forced "auto", and "auto" is still the M6
  governor.** Forcing it on in Player mode is Pablo's explicit
  04:35 order ("everything usable"); the m41 audit kept its DEFAULT
  off because it is unproven on device, and that evidence note
  stands — m42 changes who decides (the mode), not the evidence. If
  it hurts on device, Developer mode with the toggle off is the
  standing kill switch, and the boot log now shows which ran.
- **Prefetch in Player mode costs what prefetch costs.** A
  whole-ISO background read uses RAM and battery (the m41 audit's
  reason for default-off). In Player mode that trade is now made
  for the player automatically, per the same order.
- **The dash's menu side effect** (§3): menu cursor steps from a
  full push mirror a physical double flick; noted, not hidden.
- Release stays ON HOLD per Pablo, as before. The core — audio,
  graphics, speed — is m41's, byte for byte; the next performance
  and sound round is still the one waiting on his m39-line boot log.
