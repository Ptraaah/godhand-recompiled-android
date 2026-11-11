package dev.ptraaa.godhand;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.StateSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// The on-screen controls, m40: THREE Android-native control schemes,
// chosen by the player in the launcher (Control Style setting, default
// A). No scheme clones the PS2 pad; each one is designed for thumbs,
// following control-mockups/DESIGN-NOTE.md:
//
//   A "Brawler Clean" - floating move stick; big labelled buttons:
//     HIT / ATK 2 / ATK 3 / USE, a wide DODGE pill (direction from the
//     move stick, centred stick = backflip), ROULETTE and GOD HAND
//     pills, small TURN / TAUNT / MAP.
//   B "Gesture Evade" - move stick + attack trio + USE + bottom-row
//     TAUNT / ROULETTE / GOD HAND pills; NO dodge button: a flick in
//     the right-side EVADE ZONE is the dodge. Map = tap the top-right
//     corner where the game draws its radar.
//   C "Radial Pro" - A's fight buttons on a right-thumb arc, but
//     Roulette / God Hand / Taunt / Map collapse into one SPECIAL hub:
//     hold it, swipe to a wedge, release to fire. The 180 turn is a
//     tap on the move-stick knob.
//
// Every scheme feeds the SAME PadMapping inputs the m39 overlay
// produced: the labelled buttons are the old touch controls under
// player names (HIT = btn_square, ATK 2 = btn_cross, ATK 3 =
// btn_triangle, USE = btn_circle, TURN = btn_l1, TAUNT = btn_l2,
// ROULETTE = btn_r1, GOD HAND = btn_r2, MAP = btn_select, START =
// btn_start), resolved through the owner's remap table exactly as
// before. A dodge is a synthetic right-stick flick (TouchPad.
// setRightStick pulsed for ~140 ms) - precisely what m39's right
// stick produced when flicked. The visible right stick, the D-pad
// cluster and L3/R3 buttons are gone in every scheme (the design note
// drops them: menus still navigate because TouchPad derives D-pad
// presses from a firmly tilted move stick, and L3/R3 remain reachable
// through the remap table and a physical gamepad).
//
// Buttons stay real native android.widget.Buttons (m39 pattern:
// StateListDrawable pressed states, VIRTUAL_KEY haptics). Layout is
// per-scheme: each control persists under its own "lay_<id>_x/y", the
// move stick's position is shared by all schemes, and EDIT mode drags
// the current scheme's controls (A and C button clusters, B's buttons,
// zone and radar target, C's hub) exactly as m39 did.
//
// m41: every scheme carries a proper START button. The m40 PAUSE pill
// (64x36 at 11sp) was too small for Start's real job - players skip
// cutscenes with it - so it is now a full-size 100x48 native Button
// labelled START, centred at the top of the screen: clear of both
// fight clusters, of scheme B's evade-zone flick area and of scheme
// C's hub, and the ONLY Start surface in each scheme. It sends
// btn_start through the same gameButton path as every other pad
// button (pressed state, haptic tick, hold semantics), and it is a
// gameplay control, so it shows in every scheme and in both app modes.
//
// m42: auto-dash on a full forward push. The game's fast movement is
// triggered by a double forward flick of the move stick (two quick
// pushes to full forward with a return toward neutral between them -
// the game reads that pattern out of the left-stick stream itself).
// On a phone that means flicking twice for every dash, so when the
// player's push crosses DASH_TRIGGER_Y of forward travel, the overlay
// synthesizes exactly that pattern into the left-stick stream once -
// neutral gap, full forward, neutral gap, full forward - and then
// hands the stream back to the still-held stick, so the run continues
// for as long as the player keeps pushing. See feedLeftStick below.
public final class TouchOverlay extends FrameLayout {
    public static final String ID_STICK_LEFT = "stick_left";
    public static final String ID_STICK_RIGHT = "stick_right";

    public static final String STYLE_A = "a";
    public static final String STYLE_B = "b";
    public static final String STYLE_C = "c";

    // Every layout id that has ever been persisted (m39's PS2-layout
    // ids included), so the launcher's "Reset button layout" clears
    // all schemes at once.
    public static final String[] ALL_LAYOUT_IDS = {
            ID_STICK_LEFT, ID_STICK_RIGHT,
            "btn_triangle", "btn_circle", "btn_cross", "btn_square",
            "btn_l1", "btn_l2", "btn_r1", "btn_r2", "btn_start", "btn_select",
            "btn_dpad_up", "btn_dpad_down", "btn_dpad_left", "btn_dpad_right",
            "btn_l3", "btn_r3",
            "a_hit", "a_atk2", "a_atk3", "a_use", "a_dodge",
            "a_roulette", "a_godhand", "a_turn", "a_taunt", "a_map", "a_pause",
            "b_zone", "b_radar", "b_hit", "b_atk2", "b_atk3", "b_use",
            "b_turn", "b_taunt", "b_roulette", "b_godhand", "b_pause",
            "c_hit", "c_atk2", "c_atk3", "c_use", "c_dodge", "c_hub", "c_pause",
            "a_start", "b_start", "c_start",
    };

    // Product accent (matches LauncherActivity): orange.
    static final int ACCENT = Color.rgb(255, 122, 26);
    private static final int EDIT_STROKE = Color.rgb(255, 210, 60);
    private static final int ACCENT_TEXT = Color.rgb(30, 17, 5);

    // Wedge indices for scheme C's radial hub.
    static final int WEDGE_NONE = -1;
    static final int WEDGE_UP = 0;
    static final int WEDGE_RIGHT = 1;
    static final int WEDGE_DOWN = 2;
    static final int WEDGE_LEFT = 3;

    private final float density;
    private final SharedPreferences prefs;
    private final float opacityFactor;
    private final float sizeScale;
    private final String controlStyle;

    private final Map<String, View> controls = new LinkedHashMap<>();
    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    private final Map<Button, PadBtn> padButtons = new LinkedHashMap<>();
    private final Map<Button, ActionBtn> actionButtons = new LinkedHashMap<>();
    private final Map<String, Integer> pulseGen = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean positionsApplied;
    private int appliedRootW;
    private int appliedRootH;
    private boolean editing;

    // The move stick's latest vector, recorded so scheme A/C's DODGE
    // pill can aim the synthetic right-stick flick.
    private float lastLeftX;
    private float lastLeftY;
    private int flickGeneration;

    // m42 auto-dash state (see feedLeftStick): 0 = armed, 1 = the
    // synthesized double-flick sequence is running, 2 = dash fired
    // and the stick is still held past the re-arm line.
    private int dashState;
    private int dashGeneration;

    private TextView fpsLabel;
    private Button editPill;
    private Button resetPill;
    private RadialMenuView radialMenu; // scheme C only

    private static final int LEFT_TOP = 0;
    private static final int LEFT_BOTTOM = 1;
    private static final int RIGHT_TOP = 2;
    private static final int RIGHT_BOTTOM = 3;
    private static final int CENTRE_TOP = 4;
    private static final int FULL_RIGHT = 5;

    private static final class Anchor {
        final int kind;
        final float mxDp;
        final float myDp;

        Anchor(int kind, float mxDp, float myDp) {
            this.kind = kind;
            this.mxDp = mxDp;
            this.myDp = myDp;
        }
    }

    // A view that can show an edit-mode look (the custom scheme views).
    interface EditableVisual {
        void setEditingVisual(boolean on);
    }

    // One native pad button plus the state its touch handling needs.
    private static final class PadBtn {
        final String controlId;
        final boolean round;
        boolean downSent;

        PadBtn(String controlId, boolean round) {
            this.controlId = controlId;
            this.round = round;
        }
    }

    // A native button that runs an action on press instead of sending
    // a pad control (the DODGE pill).
    private static final class ActionBtn {
        final Runnable action;
        boolean downSent;

        ActionBtn(Runnable action) {
            this.action = action;
        }
    }

    public TouchOverlay(Context context, SharedPreferences prefs, float opacityFactor, float sizeScale,
                        boolean fpsVisible, String controlStyle) {
        super(context);
        this.prefs = prefs;
        this.opacityFactor = opacityFactor;
        this.sizeScale = sizeScale;
        this.controlStyle = STYLE_B.equals(controlStyle) || STYLE_C.equals(controlStyle)
                ? controlStyle : STYLE_A;
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        BootLog.append(context, "[shell] m43: overlay clipChildren default (bisect)");

        // The move stick, shared by all three schemes (one view, one
        // persisted position). Its vector is recorded for DODGE, and
        // every value passes through the m42 auto-dash feed before it
        // reaches TouchPad (identical in all three schemes).
        StickView leftStick = new StickView(context, opacityFactor, (x, y) -> {
            lastLeftX = x;
            lastLeftY = y;
            feedLeftStick(x, y);
        });
        BootLog.append(context, "auto-dash (0.43-m42-playerperf): full forward push at y<="
                + DASH_TRIGGER_Y + " synthesizes the double-flick dash; re-arms at y>"
                + DASH_REARM_Y + "; pulse " + DASH_HOLD_MS + " ms hold / " + DASH_GAP_MS + " ms gap");
        register(ID_STICK_LEFT, leftStick, 136, 136, new Anchor(LEFT_BOTTOM, 24, 36));

        if (STYLE_B.equals(this.controlStyle))
            buildSchemeB(context, leftStick);
        else if (STYLE_C.equals(this.controlStyle))
            buildSchemeC(context, leftStick);
        else
            buildSchemeA(context);

        // The FPS label (text fed by GameActivity's poller).
        fpsLabel = new TextView(context);
        fpsLabel.setText("FPS ...");
        fpsLabel.setTextSize(15);
        fpsLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        fpsLabel.setTextColor(Color.WHITE);
        GradientDrawable fpsBg = new GradientDrawable();
        fpsBg.setColor(Color.argb(150, 10, 10, 14));
        fpsBg.setCornerRadius(dp(8));
        fpsLabel.setBackground(fpsBg);
        fpsLabel.setPadding(dp(10), dp(3), dp(10), dp(3));
        fpsLabel.setVisibility(fpsVisible ? VISIBLE : GONE);
        LayoutParams fpsParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        fpsParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        fpsParams.topMargin = dp(58);
        addView(fpsLabel, fpsParams);

        // EDIT / RESET: native Buttons, click-driven (they are toggles,
        // not game controls - they never write to TouchPad).
        editPill = pillButton(context, "EDIT", true);
        editPill.setOnClickListener(v -> setEditing(!editing));
        addView(editPill, new LayoutParams(dp(64 * sizeScale), dp(36 * sizeScale)));
        resetPill = pillButton(context, "RESET", false);
        resetPill.setOnClickListener(v -> resetLayout());
        resetPill.setVisibility(GONE);
        addView(resetPill, new LayoutParams(dp(76 * sizeScale), dp(36 * sizeScale)));

        // Scheme C's radial menu rides on top of everything and never
        // takes touches itself (not clickable, no listener): the hub
        // keeps its pointer and the menu is pure drawing.
        if (STYLE_C.equals(this.controlStyle)) {
            radialMenu = new RadialMenuView(context, density, opacityFactor);
            radialMenu.setClickable(false);
            radialMenu.setVisibility(GONE);
            addView(radialMenu, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    // ---- scheme A: Brawler Clean ------------------------------------------

    private void buildSchemeA(Context context) {
        // Specials stack on the right, under where the game draws its
        // radar: GOD HAND (solid accent) above ROULETTE.
        register("a_godhand", gameButton(context, "GOD HAND", false, "btn_r2", true, 14), 118, 46,
                new Anchor(RIGHT_TOP, 24, 118));
        register("a_roulette", gameButton(context, "ROULETTE", false, "btn_r1", false, 13), 118, 46,
                new Anchor(RIGHT_TOP, 24, 174));

        // Attack cluster, bottom-right: HIT biggest, ATK 2 above-right,
        // ATK 3 below-right, USE upper-left of the cluster.
        register("a_hit", gameButton(context, "HIT", true, "btn_square", false, 17), 80, 80,
                new Anchor(RIGHT_BOTTOM, 36, 40));
        register("a_atk2", gameButton(context, "ATK 2", true, "btn_triangle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 128, 108));
        register("a_atk3", gameButton(context, "ATK 3", true, "btn_cross", false, 14), 58, 58,
                new Anchor(RIGHT_BOTTOM, 134, 30));
        register("a_use", gameButton(context, "USE", true, "btn_circle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 216, 96));

        // The dodge: one wide pill, bottom centre-right.
        register("a_dodge", dodgeButton(context), 120, 48, new Anchor(RIGHT_BOTTOM, 250, 18));

        // TURN above the move stick (left thumb, no reach across);
        // TAUNT / MAP tiny at the top-left, under the game's HUD bars.
        register("a_turn", gameButton(context, "TURN", false, "btn_l1", false, 13), 92, 40,
                new Anchor(LEFT_BOTTOM, 36, 196));
        register("a_taunt", gameButton(context, "TAUNT", false, "btn_l2", false, 11), 64, 34,
                new Anchor(LEFT_TOP, 24, 84));
        register("a_map", gameButton(context, "MAP", false, "btn_select", false, 11), 56, 34,
                new Anchor(LEFT_TOP, 98, 84));

        // START (m41): full-size and centred on the top edge - the
        // CENTRE_TOP anchor puts the view's left edge at centre + mx,
        // so mx = -50 centres the 100dp-wide button exactly. Its
        // bottom edge (8 + 48 = 56dp) stays above the FPS label
        // (top margin 58dp), and its right edge (centre + 50dp)
        // clears the EDIT pill (centre + 94dp).
        register("a_start", gameButton(context, "START", false, "btn_start", false, 14), 100, 48,
                new Anchor(CENTRE_TOP, -50, 8));
    }

    // ---- scheme B: Gesture Evade ------------------------------------------

    private void buildSchemeB(Context context, StickView leftStick) {
        // The evade zone goes in FIRST (bottom of the scheme's
        // z-order): buttons added after it sit on top and take their
        // own touches, so flicks only ever start on empty screen.
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int zoneW = Math.round(metrics.widthPixels * 0.46f);
        int zoneH = metrics.heightPixels;
        EvadeZoneView zone = new EvadeZoneView(context, density, opacityFactor,
                (x, y) -> flickRightStick(x, y));
        controls.put("b_zone", zone);
        anchors.put("b_zone", new Anchor(FULL_RIGHT, 0, 0));
        addView(zone, new LayoutParams(zoneW, zoneH));

        // Attack trio + USE, same cluster shape as A but floated up to
        // leave the bottom row for the specials.
        register("b_hit", gameButton(context, "HIT", true, "btn_square", false, 17), 80, 80,
                new Anchor(RIGHT_BOTTOM, 36, 110));
        register("b_atk2", gameButton(context, "ATK 2", true, "btn_triangle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 140, 168));
        register("b_atk3", gameButton(context, "ATK 3", true, "btn_cross", false, 14), 58, 58,
                new Anchor(RIGHT_BOTTOM, 140, 96));
        register("b_use", gameButton(context, "USE", true, "btn_circle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 222, 158));

        // Bottom row of specials: TAUNT / ROULETTE / GOD HAND.
        register("b_godhand", gameButton(context, "GOD HAND", false, "btn_r2", true, 13), 118, 46,
                new Anchor(RIGHT_BOTTOM, 16, 16));
        register("b_roulette", gameButton(context, "ROULETTE", false, "btn_r1", false, 12), 112, 46,
                new Anchor(RIGHT_BOTTOM, 146, 16));
        register("b_taunt", gameButton(context, "TAUNT", false, "btn_l2", false, 12), 92, 46,
                new Anchor(RIGHT_BOTTOM, 270, 16));

        register("b_turn", gameButton(context, "TURN", false, "btn_l1", false, 13), 92, 40,
                new Anchor(LEFT_BOTTOM, 36, 196));
        // START (m41): the same full-size top-centre button as A/C.
        // It is registered after the evade zone, so it sits above the
        // zone in z-order and consumes its own touches exactly like
        // B's other buttons - a tap on START can never start a flick,
        // and the top-centre spot is outside the flick area in play.
        register("b_start", gameButton(context, "START", false, "btn_start", false, 14), 100, 48,
                new Anchor(CENTRE_TOP, -50, 8));

        // Map = tap the top-right corner, where the game draws its
        // radar. A transparent target layered there (draws itself
        // only in edit mode); a tap pulses SELECT.
        RadarTapView radar = new RadarTapView(context, density, opacityFactor,
                () -> pulseControl("btn_select"));
        register("b_radar", radar, 96, 96, new Anchor(RIGHT_TOP, 16, 10));
    }

    // ---- scheme C: Radial Pro ----------------------------------------------

    private void buildSchemeC(Context context, StickView leftStick) {
        // The 180 turn hides in the stick: a quick tap on the knob
        // (short touch, knob barely moved) pulses L1.
        leftStick.setTapAction(() -> pulseControl("btn_l1"));

        // The SPECIAL hub, right side: hold + swipe to a wedge.
        SpecialHubView hub = new SpecialHubView(context, density, opacityFactor, new SpecialHubView.HubListener() {
            @Override
            public void onHubDown(float centreX, float centreY) {
                if (radialMenu != null)
                    radialMenu.showAt(centreX, centreY);
            }

            @Override
            public void onHubDirection(int wedge) {
                if (radialMenu != null)
                    radialMenu.setSelected(wedge);
            }

            @Override
            public void onHubRelease(int wedge) {
                if (radialMenu != null)
                    radialMenu.hideMenu();
                if (wedge != WEDGE_NONE)
                    pulseControl(wedgeControl(wedge));
            }
        });
        register("c_hub", hub, 96, 96, new Anchor(RIGHT_BOTTOM, 96, 96));

        // Fight buttons on the right-thumb arc around the hub.
        register("c_hit", gameButton(context, "HIT", true, "btn_square", false, 17), 78, 78,
                new Anchor(RIGHT_BOTTOM, 196, 52));
        register("c_atk2", gameButton(context, "ATK 2", true, "btn_triangle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 24, 205));
        register("c_atk3", gameButton(context, "ATK 3", true, "btn_cross", false, 14), 58, 58,
                new Anchor(RIGHT_BOTTOM, 20, 20));
        register("c_use", gameButton(context, "USE", true, "btn_circle", false, 14), 62, 62,
                new Anchor(RIGHT_BOTTOM, 212, 168));

        register("c_dodge", dodgeButton(context), 120, 48, new Anchor(RIGHT_BOTTOM, 300, 16));
        // START (m41): the same full-size top-centre button as A/B,
        // far from the SPECIAL hub and the fight arc (bottom-right).
        register("c_start", gameButton(context, "START", false, "btn_start", false, 14), 100, 48,
                new Anchor(CENTRE_TOP, -50, 8));
    }

    private static String wedgeControl(int wedge) {
        switch (wedge) {
            case WEDGE_UP:
                return "btn_r1";    // ROULETTE
            case WEDGE_RIGHT:
                return "btn_r2";    // GOD HAND
            case WEDGE_DOWN:
                return "btn_l2";    // TAUNT
            case WEDGE_LEFT:
                return "btn_select"; // MAP
            default:
                return null;
        }
    }

    public String styleName() {
        if (STYLE_B.equals(controlStyle))
            return "Gesture Evade (B)";
        if (STYLE_C.equals(controlStyle))
            return "Radial Pro (C)";
        return "Brawler Clean (A)";
    }

    // ---- synthetic input: dodges, tap pulses, auto-dash ------------------

    // m42 auto-dash tuning constants - the only knobs for dash feel,
    // all in one place for the post-device-test adjustment. Forward is
    // NEGATIVE y in this stack (StickView passes screen coordinates
    // straight through; TouchPad derives DPAD_UP from y < -0.55).
    // Trigger: the forward axis reaches 85% of full travel - a real
    // committed push, never a walking tilt (walking lives well above
    // this line), and within about 32 degrees of straight forward.
    private static final float DASH_TRIGGER_Y = -0.85f;
    // Re-arm: the dash can fire again only after the stick eases back
    // above 40% forward travel. The gap between the two lines is the
    // hysteresis that keeps one held push at exactly one dash.
    private static final float DASH_REARM_Y = -0.40f;
    // One synthesized flick: full forward held for 90 ms, then a
    // 70 ms return to neutral before the next flick. Both are several
    // game frames long, so the port's per-frame pad polling cannot
    // miss an edge; the whole double-flick takes 320 ms, the shape of
    // a fast physical double flick.
    private static final long DASH_HOLD_MS = 90;
    private static final long DASH_GAP_MS = 70;

    // Every move-stick value from the (shared) StickView passes
    // through here before TouchPad. Normal values forward untouched;
    // a crossing of the trigger line runs the dash sequence:
    //
    //   t=0          neutral (the return the first flick needs)
    //   t=GAP        full forward (flick 1)
    //   t=GAP+HOLD   neutral (the return between flicks)
    //   t=2*GAP+HOLD full forward (flick 2)
    //   t=2*(GAP+HOLD) the player's live value again - still held
    //                  forward, so the run the dash started continues
    //
    // Synthesizing BOTH flicks (instead of counting the player's own
    // push as flick 1) makes the trigger independent of how fast the
    // player pushed: a slow ease to full forward dashes exactly like a
    // fast shove. While the sequence runs, an ease-off past the
    // re-arm line aborts it instantly and hands the stream back. The
    // x axis always follows the player's live value, so a diagonal
    // full push dashes diagonally. Only the left stick is written -
    // the dodge synthetics (right stick, all schemes) and the button
    // pulses share no state with this machine and cannot be disturbed
    // by it. In edit mode the machine stands down and values forward
    // raw (TouchPad drops them anyway while dragging).
    private void feedLeftStick(float x, float y) {
        if (TouchPad.isEditing()) {
            dashState = 0;
            ++dashGeneration;
            TouchPad.setLeftStick(x, y);
            return;
        }
        if (dashState == 1) {
            if (y > DASH_REARM_Y) {
                // Released or eased off mid-sequence: abort, hand the
                // stream straight back to the player.
                ++dashGeneration;
                dashState = 0;
                TouchPad.setLeftStick(x, y);
            }
            return; // otherwise the scheduled steps own the output
        }
        if (dashState == 2 && y > DASH_REARM_Y)
            dashState = 0; // held dash ended: re-arm for the next push
        if (dashState == 0 && y <= DASH_TRIGGER_Y) {
            startDashSequence();
            return;
        }
        TouchPad.setLeftStick(x, y);
    }

    private void startDashSequence() {
        dashState = 1;
        final int gen = ++dashGeneration;
        TouchPad.setLeftStick(lastLeftX, 0f);
        ui.postDelayed(() -> {
            if (gen == dashGeneration)
                TouchPad.setLeftStick(lastLeftX, -1f);
        }, DASH_GAP_MS);
        ui.postDelayed(() -> {
            if (gen == dashGeneration)
                TouchPad.setLeftStick(lastLeftX, 0f);
        }, DASH_GAP_MS + DASH_HOLD_MS);
        ui.postDelayed(() -> {
            if (gen == dashGeneration)
                TouchPad.setLeftStick(lastLeftX, -1f);
        }, 2 * DASH_GAP_MS + DASH_HOLD_MS);
        ui.postDelayed(() -> {
            if (gen == dashGeneration) {
                dashState = 2;
                TouchPad.setLeftStick(lastLeftX, lastLeftY);
            }
        }, 2 * (DASH_GAP_MS + DASH_HOLD_MS));
    }

    // A dodge is a right-stick flick: hold the direction for ~140 ms,
    // then centre. Identical semantics to flicking m39's right stick.
    private void flickRightStick(float x, float y) {
        final int gen = ++flickGeneration;
        TouchPad.setRightStick(x, y);
        ui.postDelayed(() -> {
            if (gen == flickGeneration)
                TouchPad.setRightStick(0f, 0f);
        }, 140);
    }

    // Scheme A/C DODGE pill: direction from the move stick at the tap;
    // a centred stick backflips (the dodge-in-place case).
    private void dodgeFromStick() {
        float x = lastLeftX;
        float y = lastLeftY;
        if (Math.hypot(x, y) < 0.35f) {
            flickRightStick(0f, 1f);
        } else if (Math.abs(x) >= Math.abs(y)) {
            flickRightStick(Math.signum(x), 0f);
        } else {
            flickRightStick(0f, Math.signum(y));
        }
    }

    // A momentary press of a pad control (~140 ms): radar taps, hub
    // wedges, stick-knob taps. Generation-guarded per control so a
    // fast re-tap never gets cut by the previous tap's release.
    private void pulseControl(final String controlId) {
        if (controlId == null)
            return;
        Integer current = pulseGen.get(controlId);
        final int gen = (current == null ? 0 : current) + 1;
        pulseGen.put(controlId, gen);
        TouchPad.setTouchControl(controlId, true);
        ui.postDelayed(() -> {
            Integer now = pulseGen.get(controlId);
            if (now != null && now == gen)
                TouchPad.setTouchControl(controlId, false);
        }, 140);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        ++dashGeneration; // any dash step still queued is dead with the window
        dashState = 0;
        if (radialMenu != null)
            radialMenu.hideMenu();
        super.onDetachedFromWindow();
    }

    // ---- native button construction --------------------------------------

    private int alpha(int base) {
        return Math.round(base * opacityFactor);
    }

    // The shared button look: dark translucent fill with a light rim;
    // while the framework pressed state is on, the accent fills it.
    // Accent buttons (GOD HAND) sit solid-orange and darken on press.
    // In edit mode a pad button's rim turns amber (draggable look).
    private StateListDrawable buttonBackground(boolean round, boolean editingLook, boolean accentFill) {
        GradientDrawable normal = new GradientDrawable();
        if (round)
            normal.setShape(GradientDrawable.OVAL);
        else
            normal.setCornerRadius(dp(18));
        if (accentFill) {
            normal.setColor(Color.argb(alpha(235), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
            normal.setStroke(dp(2), Color.argb(alpha(255), 255, 255, 255));
        } else {
            normal.setColor(Color.argb(alpha(135), 13, 14, 20));
            normal.setStroke(dp(editingLook ? 3 : 2),
                    editingLook ? EDIT_STROKE : Color.argb(alpha(215), 255, 255, 255));
        }
        GradientDrawable pressed = new GradientDrawable();
        if (round)
            pressed.setShape(GradientDrawable.OVAL);
        else
            pressed.setCornerRadius(dp(18));
        if (accentFill) {
            pressed.setColor(Color.argb(alpha(255), 190, 84, 10));
            pressed.setStroke(dp(2), Color.WHITE);
        } else {
            pressed.setColor(Color.argb(alpha(225), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
            pressed.setStroke(dp(2), Color.argb(alpha(255), 255, 255, 255));
        }
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(StateSet.WILD_CARD, normal);
        return states;
    }

    // A labelled game button: sends the given PadMapping touch control
    // for as long as it is held, exactly like m39's buttons.
    private Button gameButton(Context context, String label, boolean round, String controlId,
                              boolean accentFill, float textSizeSp) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(accentFill ? ACCENT_TEXT : Color.WHITE);
        b.setTextSize(textSizeSp);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        PadBtn pad = new PadBtn(controlId, round);
        padButtons.put(b, pad);
        b.setBackground(buttonBackground(round, false, accentFill));
        b.setOnTouchListener(gameTouchListener);
        return b;
    }

    // The DODGE pill: looks like a game button but fires the synthetic
    // flick instead of holding a control.
    private Button dodgeButton(Context context) {
        Button b = new Button(context);
        b.setText("DODGE");
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        actionButtons.put(b, new ActionBtn(this::dodgeFromStick));
        b.setBackground(buttonBackground(false, false, false));
        b.setOnTouchListener(actionTouchListener);
        return b;
    }

    private Button pillButton(Context context, String label, boolean accent) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(18));
        normal.setColor(accent ? ACCENT : Color.argb(alpha(150), 30, 31, 38));
        normal.setStroke(dp(2), Color.argb(alpha(230), 255, 255, 255));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(18));
        pressed.setColor(Color.rgb(190, 84, 10));
        pressed.setStroke(dp(2), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(StateSet.WILD_CARD, normal);
        b.setBackground(states);
        return b;
    }

    // Press/release for every pad button. A Button only ever receives
    // the pointers that land on it, so multi-touch across buttons works
    // view-by-view exactly as the old custom views did; downSent guards
    // against a duplicate DOWN re-sending the press.
    private final OnTouchListener gameTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            PadBtn pad = padButtons.get(view);
            if (pad == null)
                return false;
            Button b = (Button) view;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!pad.downSent) {
                        pad.downSent = true;
                        b.setPressed(true);
                        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        TouchPad.setTouchControl(pad.controlId, true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pad.downSent) {
                        pad.downSent = false;
                        b.setPressed(false);
                        TouchPad.setTouchControl(pad.controlId, false);
                    }
                    return true;
                default:
                    return true;
            }
        }
    };

    // Press handling for action buttons (DODGE): the action fires once
    // per press; release just clears the visual state.
    private final OnTouchListener actionTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            ActionBtn action = actionButtons.get(view);
            if (action == null)
                return false;
            Button b = (Button) view;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!action.downSent) {
                        action.downSent = true;
                        b.setPressed(true);
                        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        action.action.run();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    action.downSent = false;
                    b.setPressed(false);
                    return true;
                default:
                    return true;
            }
        }
    };

    private void releaseButton(Button b) {
        PadBtn pad = padButtons.get(b);
        if (pad != null && pad.downSent) {
            pad.downSent = false;
            TouchPad.setTouchControl(pad.controlId, false);
        }
        ActionBtn action = actionButtons.get(b);
        if (action != null)
            action.downSent = false;
        b.setPressed(false);
    }

    private void register(String id, View view, float wDp, float hDp, Anchor anchor) {
        controls.put(id, view);
        anchors.put(id, anchor);
        addView(view, new LayoutParams(dp(wDp * sizeScale), dp(hDp * sizeScale)));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    // ---- layout ----------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int w = right - left;
        int h = bottom - top;
        if (w > 0 && h > 0 && (!positionsApplied || w != appliedRootW || h != appliedRootH)) {
            applyPositions();
            positionsApplied = true;
            appliedRootW = w;
            appliedRootH = h;
        }
    }

    private void applyPositions() {
        int rootW = getWidth();
        int rootH = getHeight();
        if (rootW <= 0 || rootH <= 0)
            return;
        for (Map.Entry<String, View> entry : controls.entrySet()) {
            String id = entry.getKey();
            View view = entry.getValue();
            int w = view.getLayoutParams().width;
            int h = view.getLayoutParams().height;
            int x;
            int y;
            if (prefs.contains(layoutKeyX(id))) {
                x = clamp(prefs.getInt(layoutKeyX(id), 0), 0, Math.max(0, rootW - w));
                y = clamp(prefs.getInt(layoutKeyY(id), 0), 0, Math.max(0, rootH - h));
            } else {
                Anchor anchor = anchors.get(id);
                int mx = dp(anchor.mxDp);
                int my = dp(anchor.myDp);
                switch (anchor.kind) {
                    case LEFT_BOTTOM:
                        x = mx;
                        y = rootH - my - h;
                        break;
                    case RIGHT_BOTTOM:
                        x = rootW - mx - w;
                        y = rootH - my - h;
                        break;
                    case RIGHT_TOP:
                        x = rootW - mx - w;
                        y = my;
                        break;
                    case CENTRE_TOP:
                        x = rootW / 2 + mx;
                        y = my;
                        break;
                    case FULL_RIGHT:
                        x = rootW - w;
                        y = 0;
                        break;
                    case LEFT_TOP:
                    default:
                        x = mx;
                        y = my;
                        break;
                }
            }
            view.setX(x);
            view.setY(y);
        }
        editPill.setX(rootW / 2f + dp(94));
        editPill.setY(dp(14));
        resetPill.setX(rootW / 2f + dp(166));
        resetPill.setY(dp(14));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String layoutKeyX(String id) {
        return "lay_" + id + "_x";
    }

    private static String layoutKeyY(String id) {
        return "lay_" + id + "_y";
    }

    private void resetLayout() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String id : controls.keySet()) {
            editor.remove(layoutKeyX(id));
            editor.remove(layoutKeyY(id));
        }
        editor.apply();
        applyPositions();
        BootLog.append(getContext(), "overlay layout reset to defaults (0.43-m42-playerperf), style "
                + controlStyle);
    }

    public String describeLayout() {
        StringBuilder out = new StringBuilder();
        out.append("style=").append(controlStyle);
        out.append(" root=").append(getWidth()).append('x').append(getHeight());
        for (Map.Entry<String, View> entry : controls.entrySet()) {
            View view = entry.getValue();
            out.append(' ').append(entry.getKey()).append("=[")
                    .append(Math.round(view.getX())).append(',').append(Math.round(view.getY()))
                    .append(' ').append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        }
        out.append(" fps=").append(fpsLabel.getVisibility() == VISIBLE ? "shown" : "hidden");
        return out.toString();
    }

    // ---- edit mode ---------------------------------------------------------

    public boolean isEditing() {
        return editing;
    }

    public void leaveEditMode() {
        if (editing)
            setEditing(false);
    }

    private void setEditing(boolean on) {
        editing = on;
        TouchPad.setEditing(on);
        editPill.setText(on ? "DONE" : "EDIT");
        resetPill.setVisibility(on ? VISIBLE : GONE);
        if (on && radialMenu != null)
            radialMenu.hideMenu();
        for (Map.Entry<String, View> entry : controls.entrySet()) {
            View view = entry.getValue();
            if (view instanceof EditableVisual)
                ((EditableVisual) view).setEditingVisual(on);
            if (view instanceof Button) {
                Button b = (Button) view;
                if (on) {
                    releaseButton(b);
                    b.setOnTouchListener(dragListener);
                } else if (padButtons.containsKey(b)) {
                    b.setOnTouchListener(gameTouchListener);
                } else {
                    b.setOnTouchListener(actionTouchListener);
                }
                PadBtn pad = padButtons.get(b);
                if (pad != null)
                    b.setBackground(buttonBackground(pad.round, on, false));
            } else {
                // Sticks and the custom scheme views (evade zone, radar
                // target, radial hub) handle touches themselves in game
                // mode; in edit mode the drag listener takes over.
                view.setOnTouchListener(on ? dragListener : null);
            }
        }
        BootLog.append(getContext(), "overlay edit mode " + (on ? "ON - drag the controls, DONE to finish"
                : "off - layout saved") + " (style " + controlStyle + ")");
    }

    private final OnTouchListener dragListener = new OnTouchListener() {
        private float startViewX;
        private float startViewY;
        private float startRawX;
        private float startRawY;
        private String draggedId;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    draggedId = idOf(view);
                    startViewX = view.getX();
                    startViewY = view.getY();
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    view.bringToFront();
                    editPill.bringToFront();
                    resetPill.bringToFront();
                    if (radialMenu != null)
                        radialMenu.bringToFront();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int maxX = Math.max(0, getWidth() - view.getWidth());
                    int maxY = Math.max(0, getHeight() - view.getHeight());
                    view.setX(clamp(Math.round(startViewX + event.getRawX() - startRawX), 0, maxX));
                    view.setY(clamp(Math.round(startViewY + event.getRawY() - startRawY), 0, maxY));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggedId != null) {
                        prefs.edit()
                                .putInt(layoutKeyX(draggedId), Math.round(view.getX()))
                                .putInt(layoutKeyY(draggedId), Math.round(view.getY()))
                                .apply();
                        draggedId = null;
                    }
                    return true;
                default:
                    return false;
            }
        }
    };

    private String idOf(View view) {
        for (Map.Entry<String, View> entry : controls.entrySet())
            if (entry.getValue() == view)
                return entry.getKey();
        return null;
    }

    // ---- FPS label -----------------------------------------------------------

    public void setFpsText(String text) {
        if (text != null && !text.isEmpty())
            fpsLabel.setText(text);
    }

    // ---- the move stick (custom view; a stick cannot be a Button) -----------

    interface StickListener {
        void onStick(float x, float y);
    }

    // A virtual analog stick: a base ring and a knob that follows the
    // finger, clamped to the ring. Reports -1..1, down/right positive.
    // Colours match the native buttons: dark fill, light rim (amber in
    // edit mode), accent-orange knob centre. m40 adds an optional tap
    // action (scheme C's knob-tap 180 turn): a short touch that barely
    // moves the knob fires it on release.
    static final class StickView extends View implements EditableVisual {
        private final StickListener listener;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pointerId = -1;
        private float knobX;
        private float knobY;
        private boolean editingVisual;
        private Runnable tapAction;
        private long downTimeMs;
        private float maxDeflection;

        StickView(Context context, float opacityFactor, StickListener listener) {
            super(context);
            this.listener = listener;
            this.opacityFactor = opacityFactor;
            setClickable(true);
        }

        @Override
        public void setEditingVisual(boolean on) {
            editingVisual = on;
            invalidate();
        }

        void setTapAction(Runnable action) {
            tapAction = action;
        }

        private int alpha(int base) {
            return Math.round(base * opacityFactor);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (pointerId == -1) {
                        pointerId = event.getPointerId(0);
                        downTimeMs = event.getEventTime();
                        maxDeflection = 0f;
                        update(event, 0);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (pointerId == -1) {
                        int index = event.getActionIndex();
                        pointerId = event.getPointerId(index);
                        downTimeMs = event.getEventTime();
                        maxDeflection = 0f;
                        update(event, index);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int index = pointerId == -1 ? -1 : event.findPointerIndex(pointerId);
                    if (index >= 0)
                        update(event, index);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == pointerId)
                        release();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointerId != -1) {
                        if (tapAction != null
                                && event.getEventTime() - downTimeMs < 260
                                && maxDeflection < 0.25f)
                            tapAction.run();
                        release();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (pointerId != -1)
                        release();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void update(MotionEvent event, int index) {
            float range = Math.min(getWidth(), getHeight()) / 2f;
            if (range <= 0f)
                return;
            float dx = (event.getX(index) - getWidth() / 2f) / range;
            float dy = (event.getY(index) - getHeight() / 2f) / range;
            float length = (float) Math.hypot(dx, dy);
            if (length > 1f) {
                dx /= length;
                dy /= length;
                length = 1f;
            }
            if (length > maxDeflection)
                maxDeflection = length;
            knobX = dx;
            knobY = dy;
            if (length < 0.08f) {
                dx = 0f;
                dy = 0f;
            }
            listener.onStick(dx, dy);
            invalidate();
        }

        private void release() {
            pointerId = -1;
            knobX = 0f;
            knobY = 0f;
            listener.onStick(0f, 0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - 4f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha(115), 13, 14, 20));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(editingVisual ? Color.argb(255, 255, 210, 60)
                    : Color.argb(alpha(205), 255, 255, 255));
            canvas.drawCircle(cx, cy, radius, paint);
            float knobRadius = radius * 0.42f;
            float travel = radius - knobRadius;
            float kx = cx + knobX * travel;
            float ky = cy + knobY * travel;
            knobPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.argb(alpha(190), 235, 235, 240));
            canvas.drawCircle(kx, ky, knobRadius, knobPaint);
            knobPaint.setColor(Color.argb(alpha(235), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
            canvas.drawCircle(kx, ky, knobRadius * 0.34f, knobPaint);
        }
    }

    // ---- scheme B: the evade zone -------------------------------------------

    interface FlickListener {
        void onFlick(float x, float y);
    }

    // The right-side flick area of scheme B. A dodge fires only from a
    // real flick: at least 56 dp of travel, done within 450 ms, at an
    // average speed of at least 0.30 dp/ms - a tap, a slow drag, or a
    // finger resting on the zone never dodges. Buttons sit on top of
    // the zone and consume their own touches, so attack taps can never
    // start a flick. Direction is the gesture's dominant axis: up =
    // duck (0,-1), down = backflip (0,1), left/right = side dodges.
    // The zone paints only a faint hint (the mockup's ghost pad); the
    // design's fade-after-learning needs game state the shell cannot
    // see, so the hint simply stays very faint.
    static final class EvadeZoneView extends View implements EditableVisual {
        private static final float MIN_DISTANCE_DP = 56f;
        private static final long MAX_DURATION_MS = 450;
        private static final float MIN_SPEED_DP_PER_MS = 0.30f;

        private final FlickListener listener;
        private final float density;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean editingVisual;
        private int pointerId = -1;
        private float startX;
        private float startY;
        private long startMs;
        private boolean fired;

        EvadeZoneView(Context context, float density, float opacityFactor, FlickListener listener) {
            super(context);
            this.listener = listener;
            this.density = density;
            this.opacityFactor = opacityFactor;
            setClickable(true);
        }

        @Override
        public void setEditingVisual(boolean on) {
            editingVisual = on;
            invalidate();
        }

        private int alpha(int base) {
            return Math.round(base * opacityFactor);
        }

        private float dp(float value) {
            return value * density;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (pointerId == -1) {
                        pointerId = event.getPointerId(0);
                        startX = event.getX(0);
                        startY = event.getY(0);
                        startMs = event.getEventTime();
                        fired = false;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int index = pointerId == -1 ? -1 : event.findPointerIndex(pointerId);
                    if (index >= 0)
                        maybeFire(event, index);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == pointerId) {
                        maybeFire(event, event.getActionIndex());
                        pointerId = -1;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointerId != -1) {
                        int index = event.findPointerIndex(pointerId);
                        if (index >= 0)
                            maybeFire(event, index);
                        pointerId = -1;
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pointerId = -1;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void maybeFire(MotionEvent event, int index) {
            if (fired || pointerId == -1)
                return;
            float dx = event.getX(index) - startX;
            float dy = event.getY(index) - startY;
            float distance = (float) Math.hypot(dx, dy);
            long elapsed = Math.max(1, event.getEventTime() - startMs);
            if (distance < dp(MIN_DISTANCE_DP))
                return;
            if (elapsed > MAX_DURATION_MS)
                return;
            if (distance / elapsed < dp(MIN_SPEED_DP_PER_MS))
                return;
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (Math.abs(dx) >= Math.abs(dy))
                listener.onFlick(Math.signum(dx), 0f);
            else
                listener.onFlick(0f, Math.signum(dy));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dp(6);
            RectF bounds = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha(editingVisual ? 60 : 26), 255, 255, 255));
            canvas.drawRoundRect(bounds, dp(18), dp(18), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            if (editingVisual) {
                paint.setColor(Color.argb(255, 255, 210, 60));
            } else {
                paint.setColor(Color.argb(alpha(90), 255, 255, 255));
                paint.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{dp(10), dp(8)}, 0));
            }
            canvas.drawRoundRect(bounds, dp(18), dp(18), paint);
            paint.setPathEffect(null);

            // The ghost hint: dashed circle with four direction arrows,
            // echoing the PS2 right stick this zone replaces.
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.30f;
            float radius = dp(52);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(alpha(editingVisual ? 200 : 80), 255, 255, 255));
            paint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(7), dp(6)}, 0));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha(editingVisual ? 230 : 110),
                    Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
            drawArrow(canvas, cx, cy - radius * 0.55f, 0); // up
            drawArrow(canvas, cx, cy + radius * 0.55f, 1); // down
            drawArrow(canvas, cx - radius * 0.55f, cy, 2); // left
            drawArrow(canvas, cx + radius * 0.55f, cy, 3); // right
            canvas.drawCircle(cx, cy, dp(7), paint);

            textPaint.setColor(Color.argb(alpha(editingVisual ? 255 : 120), 255, 255, 255));
            textPaint.setTextSize(dp(12));
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(editingVisual ? "EVADE ZONE - drag to move" : "FLICK TO DODGE",
                    cx, dp(30), textPaint);
        }

        // A small filled triangle pointing up(0)/down(1)/left(2)/right(3).
        private void drawArrow(Canvas canvas, float cx, float cy, int direction) {
            float s = dp(9);
            Path path = new Path();
            switch (direction) {
                case 0:
                    path.moveTo(cx, cy - s);
                    path.lineTo(cx - s * 0.8f, cy + s * 0.6f);
                    path.lineTo(cx + s * 0.8f, cy + s * 0.6f);
                    break;
                case 1:
                    path.moveTo(cx, cy + s);
                    path.lineTo(cx - s * 0.8f, cy - s * 0.6f);
                    path.lineTo(cx + s * 0.8f, cy - s * 0.6f);
                    break;
                case 2:
                    path.moveTo(cx - s, cy);
                    path.lineTo(cx + s * 0.6f, cy - s * 0.8f);
                    path.lineTo(cx + s * 0.6f, cy + s * 0.8f);
                    break;
                default:
                    path.moveTo(cx + s, cy);
                    path.lineTo(cx - s * 0.6f, cy - s * 0.8f);
                    path.lineTo(cx - s * 0.6f, cy + s * 0.8f);
                    break;
            }
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    // ---- scheme B: the radar tap target --------------------------------------

    // A transparent tap target over the top-right corner, where the
    // game draws its radar: tapping it opens the map (a SELECT pulse).
    // Invisible in game (the game's own radar art is the affordance);
    // it outlines itself in edit mode so it can be found and dragged.
    static final class RadarTapView extends View implements EditableVisual {
        private final Runnable tapAction;
        private final float density;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean editingVisual;
        private long downMs;
        private float downX;
        private float downY;

        RadarTapView(Context context, float density, float opacityFactor, Runnable tapAction) {
            super(context);
            this.tapAction = tapAction;
            this.density = density;
            this.opacityFactor = opacityFactor;
            setClickable(true);
        }

        @Override
        public void setEditingVisual(boolean on) {
            editingVisual = on;
            invalidate();
        }

        private float dp(float value) {
            return value * density;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downMs = event.getEventTime();
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - downMs < 300
                            && Math.hypot(event.getX() - downX, event.getY() - downY) < dp(24)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        tapAction.run();
                    }
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!editingVisual)
                return;
            float inset = dp(4);
            RectF bounds = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(255, 255, 210, 60));
            paint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(8), dp(6)}, 0));
            canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(11));
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("MAP (radar tap)", getWidth() / 2f, getHeight() / 2f, paint);
        }
    }

    // ---- scheme C: the SPECIAL hub and its radial menu ------------------------

    // The hub itself: a round hold-target. It tracks its own pointer,
    // reports the swipe direction (from the hub's centre, past a 40 dp
    // dead radius) as a wedge, and fires on release. The drawing of the
    // menu belongs to RadialMenuView; the hub only draws its face.
    static final class SpecialHubView extends View implements EditableVisual {
        interface HubListener {
            void onHubDown(float centreX, float centreY);

            void onHubDirection(int wedge);

            void onHubRelease(int wedge);
        }

        private final HubListener listener;
        private final float density;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean editingVisual;
        private int pointerId = -1;
        private float centreX;
        private float centreY;
        private int wedge = WEDGE_NONE;

        SpecialHubView(Context context, float density, float opacityFactor, HubListener listener) {
            super(context);
            this.listener = listener;
            this.density = density;
            this.opacityFactor = opacityFactor;
            setClickable(true);
        }

        @Override
        public void setEditingVisual(boolean on) {
            editingVisual = on;
            invalidate();
        }

        private int alpha(int base) {
            return Math.round(base * opacityFactor);
        }

        private float dp(float value) {
            return value * density;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (pointerId == -1) {
                        pointerId = event.getPointerId(0);
                        centreX = getX() + getWidth() / 2f;
                        centreY = getY() + getHeight() / 2f;
                        wedge = WEDGE_NONE;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.onHubDown(centreX, centreY);
                        track(event, 0);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int index = pointerId == -1 ? -1 : event.findPointerIndex(pointerId);
                    if (index >= 0)
                        track(event, index);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == pointerId) {
                        pointerId = -1;
                        listener.onHubRelease(wedge);
                        wedge = WEDGE_NONE;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointerId != -1) {
                        pointerId = -1;
                        listener.onHubRelease(wedge);
                        wedge = WEDGE_NONE;
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (pointerId != -1) {
                        pointerId = -1;
                        wedge = WEDGE_NONE;
                        listener.onHubRelease(WEDGE_NONE);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void track(MotionEvent event, int index) {
            float fx = getX() + event.getX(index);
            float fy = getY() + event.getY(index);
            float dx = fx - centreX;
            float dy = fy - centreY;
            int next = WEDGE_NONE;
            if (Math.hypot(dx, dy) > dp(40)) {
                double angle = Math.toDegrees(Math.atan2(dy, dx));
                if (angle >= -45 && angle < 45)
                    next = WEDGE_RIGHT;
                else if (angle >= 45 && angle < 135)
                    next = WEDGE_DOWN;
                else if (angle >= -135 && angle < -45)
                    next = WEDGE_UP;
                else
                    next = WEDGE_LEFT;
            }
            if (next != wedge) {
                wedge = next;
                if (wedge != WEDGE_NONE)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                listener.onHubDirection(wedge);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(3);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha(150), 13, 14, 20));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(editingVisual ? Color.argb(255, 255, 210, 60)
                    : Color.argb(alpha(255), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
            canvas.drawCircle(cx, cy, radius, paint);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(dp(13));
            canvas.drawText("SPECIAL", cx, cy - dp(2), textPaint);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            textPaint.setTextSize(dp(9));
            textPaint.setColor(Color.argb(alpha(200), 255, 255, 255));
            canvas.drawText("hold + swipe", cx, cy + dp(13), textPaint);
        }
    }

    // The radial menu drawn while the hub is held. Pure drawing: it is
    // never clickable and holds no listener, so the hub keeps its
    // pointer stream underneath it.
    static final class RadialMenuView extends View {
        private final float density;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float centreX;
        private float centreY;
        private int selected = WEDGE_NONE;
        private boolean shown;

        RadialMenuView(Context context, float density, float opacityFactor) {
            super(context);
            this.density = density;
            this.opacityFactor = opacityFactor;
        }

        void showAt(float centreX, float centreY) {
            this.centreX = centreX;
            this.centreY = centreY;
            selected = WEDGE_NONE;
            shown = true;
            setVisibility(VISIBLE);
            invalidate();
        }

        void setSelected(int wedge) {
            if (wedge != selected) {
                selected = wedge;
                invalidate();
            }
        }

        void hideMenu() {
            shown = false;
            selected = WEDGE_NONE;
            setVisibility(GONE);
        }

        private int alpha(int base) {
            return Math.round(base * opacityFactor);
        }

        private float dp(float value) {
            return value * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!shown)
                return;
            float radius = dp(118);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha(205), 13, 14, 20));
            canvas.drawCircle(centreX, centreY, radius, paint);

            // The selected wedge's slice.
            if (selected != WEDGE_NONE) {
                float start;
                switch (selected) {
                    case WEDGE_RIGHT:
                        start = -45;
                        break;
                    case WEDGE_DOWN:
                        start = 45;
                        break;
                    case WEDGE_LEFT:
                        start = 135;
                        break;
                    default:
                        start = -135;
                        break;
                }
                paint.setColor(Color.argb(alpha(170), Color.red(ACCENT), Color.green(ACCENT),
                        Color.blue(ACCENT)));
                canvas.drawArc(new RectF(centreX - radius, centreY - radius,
                        centreX + radius, centreY + radius), start, 90, true, paint);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(alpha(230), 255, 255, 255));
            canvas.drawCircle(centreX, centreY, radius, paint);
            // Divider spokes at the four sector boundaries.
            paint.setStrokeWidth(dp(1.5f));
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(45 + i * 90);
                canvas.drawLine(centreX, centreY,
                        centreX + (float) (Math.cos(a) * radius),
                        centreY + (float) (Math.sin(a) * radius), paint);
            }
            // The dead zone the finger must leave before a wedge arms.
            paint.setColor(Color.argb(alpha(120), 255, 255, 255));
            canvas.drawCircle(centreX, centreY, dp(40), paint);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(dp(12));
            float labelR = radius * 0.70f;
            drawLabel(canvas, "ROULETTE", centreX, centreY - labelR, selected == WEDGE_UP);
            drawLabel(canvas, "GOD HAND", centreX + labelR, centreY, selected == WEDGE_RIGHT);
            drawLabel(canvas, "TAUNT", centreX, centreY + labelR, selected == WEDGE_DOWN);
            drawLabel(canvas, "MAP", centreX - labelR, centreY, selected == WEDGE_LEFT);
        }

        private void drawLabel(Canvas canvas, String text, float x, float y, boolean active) {
            textPaint.setColor(active ? Color.WHITE
                    : Color.argb(alpha(190), 255, 255, 255));
            float baseline = y - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(text, x, baseline, textPaint);
        }
    }
}
