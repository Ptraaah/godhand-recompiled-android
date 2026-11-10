# God Hand Android — native control scheme designs

Date: 2026-11-11. Status: DESIGN ONLY. Three mockups, Pablo picks one, then it gets built.
Mockups: `mockup-a.png` (Brawler Clean), `mockup-b.png` (Gesture Evade), `mockup-c.png`
(Radial Pro) in this folder. Grounded in the current port surface
(`PadMapping.java` / `TouchPad.java`: 14 button bits + left/right stick axes + L2/R2
trigger axes) and the real PS2 God Hand layout.

## One correction first: God Hand has no guard button

The original game has no block/guard at all. Defence IS the right-stick dodge
(duck / backflip / side dodges), plus the 180° turn (L1) and the radar. So no
scheme below wastes a big button on "guard" — the dodge gets that prominence
instead. The camera is also not free: it sits behind Gene's back, which is why
the original needs no camera stick, only the 180° turn.

## The game's real action set (PS2 original)

| Function | PS2 original |
|---|---|
| Move | Left stick |
| Dash | Double-tap up on left stick |
| Evade: duck / backflip / dodge L / dodge R | Flick right stick up / down / left / right |
| Combo attack chain (customisable) | Square |
| Technique 2 (customisable) | Cross |
| Technique 3 (customisable) | Triangle |
| Action: pick up / throw item / reaction commands / confirm | Circle |
| 180° turn | L1 |
| Taunt | L2 |
| Roulette (God Reel): open, cycle with R1, confirm with Cross | R1 |
| Unleash God Hand (needs full tension gauge) | R2 |
| Pause / setup screens | Start |
| Map | Select |
| Menus | D-pad + face buttons |

What a native Android version can drop or merge, in every scheme below:

- The visible right stick disappears. It only ever did one thing — 4-way
  flicks — so it becomes a button-plus-direction (A, C) or a screen flick (B).
- L3 / R3 buttons disappear (the game never uses them meaningfully).
- Select disappears as a button; the map is a rarely used screen, not a combat
  control.
- The D-pad disappears during gameplay. Menus become direct touch (tap the
  menu item), which is what a native Android game would do; a small temporary
  pad can appear inside menu screens only, if a menu ever needs one.
- The four shoulder buttons collapse: L1 becomes one small TURN control, L2
  (taunt) becomes tiny or moves into a hub, R1/R2 become labelled Roulette and
  God Hand controls that say what they do.
- Interact stays one context button (USE): the game itself decides whether it
  means pick up, throw, or a reaction command, and the button can grow/pulse
  when a reaction prompt is on screen.

---

## Scheme A — Brawler Clean (mockup-a.png)

Philosophy: the classic mobile-brawler layout (the shape used by most native
Android fighting games). Few, big, labelled buttons; everything important is a
tap; nothing needs a gesture you have to learn.

| Function | Where it lives in A |
|---|---|
| Move | Floating left stick (appears where the left thumb lands) |
| Dash | Double-tap up on the stick (unchanged from PS2) |
| Evade (all 4) | Big DODGE pill, bottom centre-right. Direction = wherever the move stick is pointing at the tap; stick centred = backflip |
| Combo attack | HIT — biggest button, orange ring, bottom-right |
| Technique 2 | ATK 2 — medium button above-right of HIT |
| Technique 3 | ATK 3 — medium button below-right of HIT |
| Action / pick up / reaction | USE — medium button upper-left of the attack cluster |
| 180° turn | TURN pill above the left stick (left thumb, no reach across) |
| Roulette | ROULETTE pill, top-right stack. Tap: the wheel opens as a touch overlay — tap the move you want, or tap ROULETTE again to shuffle like the original R1 cycling |
| Unleash God Hand | GOD HAND pill, solid orange, top of the right stack. Greyed until the tension gauge is full |
| Taunt | Tiny TAUNT pill, top-left under the HUD |
| Map | Tiny MAP pill next to taunt |
| Pause | PAUSE pill, top centre-right |
| Menus | Direct touch |

Removed/merged: right stick (became DODGE + stick direction), L3/R3, Select
(became the MAP pill), D-pad in gameplay, all four shoulder buttons (became
TURN pill + the two labelled special pills). On-screen control count in a
fight: stick + 7 real buttons.

Why it works for God Hand: the dodge is the game's most pressed defensive
input and it is one big always-there target — no flick timing, no gesture
recognition to fail mid-boss. HIT/ATK 2/ATK 3 keep the exact 3-attack
structure of the combo-customisation system, just named by job instead of by
PlayStation shapes.

Weakness: DODGE direction depends on the left stick, so a dodge while running
goes where you're running. That matches how most players dodge (away/along
movement), and centred-stick backflip covers the "dodge in place" case, but a
sideways dodge while running forward needs a quick stick roll first.

## Scheme B — Gesture Evade (mockup-b.png)

Philosophy: the screen is the dodge stick. No right stick AND no dodge
button — evade by flicking anywhere on the right half of the screen, exactly
the motion the PS2 right stick asked for, done with the whole thumb.

| Function | Where it lives in B |
|---|---|
| Move | Floating left stick |
| Dash | Double-tap up on the stick |
| Evade (all 4) | Flick on the right-side EVADE ZONE: flick up = duck, down = backflip, left/right = side dodges. The ghost pad in the mockup is only a teaching hint that fades after the first fights |
| Combo attack | HIT — biggest button, bottom-right |
| Technique 2 | ATK 2 — medium button, upper right |
| Technique 3 | ATK 3 — smaller button under ATK 2 |
| Action / pick up / reaction | USE — medium button left of ATK 2 |
| 180° turn | TURN pill above the left stick |
| Roulette | ROULETTE pill in the bottom row; wheel opens as a touch overlay, tap a move |
| Unleash God Hand | GOD HAND pill, solid orange, end of the bottom row |
| Taunt | TAUNT pill, start of the bottom row |
| Map | Tap the radar (top-right) — the radar is already on screen, so it becomes the map button |
| Pause | PAUSE pill, top centre-right |
| Menus | Direct touch |

Removed/merged: everything from A, plus the DODGE button itself, plus the MAP
button (radar tap). Fewest controls of the three: stick + 6 buttons + one
gesture zone. Cleanest screen, most of the view is game.

Weakness, honestly: gesture recognition is the risk. A flick that starts on a
button must not dodge; a fast attack tap-sequence near the zone edge must not
be read as a flick. Good native games solve this (flicks only count from
empty area, with a minimum speed/length), but God Hand dodges are
life-or-death inputs at high difficulty levels, and a 5% misread rate would
feel terrible. This scheme also has the steepest learning curve — nothing on
screen says "dodge" until the hint is learned.

## Scheme C — Radial Pro (mockup-c.png)

Philosophy: keep A's reliable buttons for the fight itself, but collapse all
four "occasional" systems (Roulette, God Hand, Taunt, Map) into ONE specials
hub. Hold the hub, swipe to a wedge, release — the same hold-and-swipe radial
native Android games use for weapon wheels.

| Function | Where it lives in C |
|---|---|
| Move | Floating left stick |
| Dash | Double-tap up on the stick |
| Evade (all 4) | DODGE pill, bottom centre — direction from the move stick, centred = backflip (same as A) |
| Combo attack | HIT — biggest button, lower-left of the hub |
| Technique 2 | ATK 2 — button on the thumb arc, top-right |
| Technique 3 | ATK 3 — button on the thumb arc, bottom-right |
| Action / pick up / reaction | USE — button on the thumb arc, upper-left of the hub |
| 180° turn | Tap the stick knob (the tap is the old L3 press, remapped to the turn — the mapping is data in this port) |
| Roulette | Hub wedge UP: hold SPECIAL, swipe up, release. Opens the roulette wheel overlay; tap a move |
| Unleash God Hand | Hub wedge RIGHT (orange). Only fires when the tension gauge is full |
| Taunt | Hub wedge DOWN |
| Map | Hub wedge LEFT |
| Pause | PAUSE pill, top centre-right |
| Menus | Direct touch |

Removed/merged: everything from A, plus the TURN pill (became the stick-knob
tap), plus all four special buttons (became one hub). On-screen control count
in a fight: stick + 6 targets, and the right side forms one arc under the
right thumb instead of two separate clusters.

Weakness: hold-and-swipe is two motions, so God Hand unleash — which in the
original is an instant R2 press, often hit reactively — becomes slightly
slower. Roulette doesn't suffer (time slows while it opens, in the original
too), but the unleash timing cost is real. The knob-tap for 180° turn is also
undiscoverable until taught.

---

## Recommendation

**Build A (Brawler Clean) first.** Reasons:

1. God Hand is a game of reactions. Ducking a high attack or backflipping a
   grab is a ~200 ms decision, and A is the only scheme where every combat
   input is a single tap on a fixed, labelled, always-visible target. B's
   flicks and C's hold-and-swipe both add a recognition step between the
   player and the dodge/unleash.
2. A keeps the game's real structure visible: 3 attack buttons for the
   custom combo system, a dodge that is clearly THE defensive button, and
   specials that say their names (ROULETTE / GOD HAND) instead of hiding
   behind R1/R2 labels a phone player has no reason to know.
3. A is the smallest change from what m39 already proved on the phone: it is
   still native buttons on the same TouchPad seam — the shell translates
   DODGE + stick direction into the right-stick flick the game expects, and
   the roulette overlay translates taps into the original R1-cycle + confirm.
   Nothing in the core changes.
4. B is the most beautiful and the most dangerous; C is a good second step.
   If A tests well and Pablo wants an even cleaner screen later, C's hub can
   replace A's two special pills without moving a single fight button, and
   B's flick-evade can be added as an optional alternative dodge method in
   settings rather than the only one.

One shared detail worth keeping in all three: the USE button should visibly
grow/pulse when the game shows a reaction prompt — on PS2 those prompts flash
the Circle button; on a phone, the button itself flashing orange is the
native equivalent, and it teaches the layout without a tutorial.
