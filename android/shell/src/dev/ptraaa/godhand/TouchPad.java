package dev.ptraaa.godhand;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

// Player 1's pad state, and the merge point of its two sources: the
// on-screen overlay (TouchOverlay) and a physical gamepad (GameActivity's
// key/motion overrides). Every change re-pushes the merged state through
// JNI into ps2_android_touch (runtime/ps2xRuntime/src/lib/
// ps2_android_touch.h), which the port reads as host gamepad 0 - the
// "SDL-0" device player 1's default controls.ini bindings already use.
//
// Merge rules: buttons are the OR of both sources (either can press,
// neither can release the other's press). Each stick is owned by the
// source that last pushed it firmly; when the owner centres, a still-
// deflected other source takes over, so resting one thumb does not
// zero a stick the other source is holding. Triggers take the max.
//
// Milestone 5: what each on-screen control and each gamepad button MEANS
// is data (PadMapping, from SharedPreferences, loaded by GameActivity via
// loadMapping before the game starts). The overlay calls
// setTouchControl(controlId, down); GameActivity resolves gamepad keys to
// targets and calls applyPadTarget / routePadTrigger. A target is either
// a bit below or one of the two trigger axes (the port's bindings read
// L2/R2 as axes). While the overlay's edit mode is on, overlay writes are
// dropped (setEditing), so dragging buttons around cannot play the game.
//
// Bit numbers are ps2_host_gamepads::Button (SDL numbering), the order
// the native side reads.
public final class TouchPad {
    public static final int CROSS = 0;    // FaceSouth
    public static final int CIRCLE = 1;   // FaceEast
    public static final int SQUARE = 2;   // FaceWest
    public static final int TRIANGLE = 3; // FaceNorth
    public static final int SELECT = 4;   // Back
    public static final int START = 6;    // Start
    public static final int L3 = 7;       // LeftStick press
    public static final int R3 = 8;       // RightStick press
    public static final int L1 = 9;       // LeftShoulder
    public static final int R1 = 10;      // RightShoulder
    public static final int DPAD_UP = 11;
    public static final int DPAD_DOWN = 12;
    public static final int DPAD_LEFT = 13;
    public static final int DPAD_RIGHT = 14;

    // Source 0: the overlay. stickDpad is derived from its left stick.
    private static int touchButtons;
    private static int touchDpad;
    private static float touchLX, touchLY, touchRX, touchRY, touchLT, touchRT;

    // Source 1: the physical gamepad.
    private static int padButtons;
    private static float padLX, padLY, padRX, padRY, padLT, padRT;

    // Which source last pushed each stick firmly (0 = overlay, 1 = gamepad).
    private static int leftStickOwner;
    private static int rightStickOwner;

    private static boolean overlayPresent;
    private static boolean gamepadPresent;

    // Milestone 5: the loaded remapping (control/key name -> target name),
    // and the overlay edit-mode gate.
    private static final Map<String, String> touchMap = new HashMap<>();
    private static final Map<String, String> padMap = new HashMap<>();
    private static boolean editing;

    private TouchPad() {
    }

    public static synchronized void loadMapping(SharedPreferences prefs) {
        touchMap.clear();
        for (String control : PadMapping.TOUCH_CONTROLS)
            touchMap.put(control, PadMapping.touchTarget(prefs, control));
        padMap.clear();
        for (String key : PadMapping.PAD_KEYS)
            padMap.put(key, PadMapping.padTarget(prefs, key));
    }

    public static synchronized String touchTargetFor(String control) {
        String target = touchMap.get(control);
        return target != null ? target : PadMapping.defaultTouchTarget(control);
    }

    public static synchronized String padTargetFor(String key) {
        String target = padMap.get(key);
        return target != null ? target : PadMapping.defaultPadTarget(key);
    }

    public static synchronized void setEditing(boolean on) {
        editing = on;
        if (on)
            releaseTouchLocked();
        push();
    }

    public static synchronized boolean isEditing() {
        return editing;
    }

    // ---- overlay input -------------------------------------------------

    // One on-screen button, through the loaded mapping.
    public static synchronized void setTouchControl(String control, boolean down) {
        if (editing)
            return;
        applyTarget(touchTargetFor(control), down, true);
        push();
    }

    private static void applyTarget(String target, boolean down, boolean touchSource) {
        if (PadMapping.isTrigger(target)) {
            float value = down ? 1f : 0f;
            if (touchSource) {
                if (PadMapping.LEFT_TRIGGER.equals(target))
                    touchLT = value;
                else
                    touchRT = value;
            } else {
                if (PadMapping.LEFT_TRIGGER.equals(target))
                    padLT = value;
                else
                    padRT = value;
            }
            return;
        }
        int bit = PadMapping.targetBit(target);
        if (bit < 0)
            return;
        if (touchSource) {
            if (down)
                touchButtons |= (1 << bit);
            else
                touchButtons &= ~(1 << bit);
        } else {
            if (down)
                padButtons |= (1 << bit);
            else
                padButtons &= ~(1 << bit);
        }
    }

    public static synchronized void setLeftStick(float x, float y) {
        if (editing)
            return;
        touchLX = x;
        touchLY = y;
        if (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f)
            leftStickOwner = 0;
        // This game's menus move with the D-pad (the desktop layout puts
        // the D-pad on the arrow keys for exactly that reason, and the
        // stick alone does not navigate them), so a firmly pushed stick
        // also presses the matching D-pad direction.
        touchDpad = 0;
        if (x > 0.55f)
            touchDpad |= 1 << DPAD_RIGHT;
        if (x < -0.55f)
            touchDpad |= 1 << DPAD_LEFT;
        if (y > 0.55f)
            touchDpad |= 1 << DPAD_DOWN;
        if (y < -0.55f)
            touchDpad |= 1 << DPAD_UP;
        push();
    }

    public static synchronized void setRightStick(float x, float y) {
        if (editing)
            return;
        touchRX = x;
        touchRY = y;
        if (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f)
            rightStickOwner = 0;
        push();
    }

    public static synchronized void releaseTouch() {
        releaseTouchLocked();
        push();
    }

    private static void releaseTouchLocked() {
        touchButtons = 0;
        touchDpad = 0;
        touchLX = touchLY = touchRX = touchRY = touchLT = touchRT = 0f;
    }

    // ---- physical gamepad input ----------------------------------------

    // One digital gamepad button, through the loaded mapping.
    public static synchronized void applyPadTarget(String target, boolean down) {
        applyTarget(target, down, false);
        push();
    }

    // An analog trigger source (a trigger key OR its axis) routed through
    // the mapping of the physical key it belongs to: a trigger target
    // takes the analog value, a button target is pressed past halfway.
    public static synchronized void routePadTrigger(String target, float value) {
        if (PadMapping.isTrigger(target)) {
            if (PadMapping.LEFT_TRIGGER.equals(target))
                padLT = value;
            else
                padRT = value;
        } else {
            applyTarget(target, value > 0.5f, false);
        }
        push();
    }

    public static synchronized void setPadButton(int bit, boolean down) {
        if (down)
            padButtons |= (1 << bit);
        else
            padButtons &= ~(1 << bit);
        push();
    }

    public static synchronized void setPadLeftStick(float x, float y) {
        padLX = x;
        padLY = y;
        if (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f)
            leftStickOwner = 1;
        push();
    }

    public static synchronized void setPadRightStick(float x, float y) {
        padRX = x;
        padRY = y;
        if (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f)
            rightStickOwner = 1;
        push();
    }

    // ---- presence --------------------------------------------------------

    public static synchronized void setOverlayPresent(boolean present) {
        overlayPresent = present;
        pushPresent();
    }

    public static synchronized void setGamepadPresent(boolean present) {
        gamepadPresent = present;
        if (!present) {
            padButtons = 0;
            padLX = padLY = padRX = padRY = padLT = padRT = 0f;
            push();
        }
        pushPresent();
    }

    private static void pushPresent() {
        try {
            nativeSetPresent(overlayPresent || gamepadPresent);
        } catch (Throwable ignored) {
            // Library not loaded yet; GameActivity re-sends after loadLibrary.
        }
    }

    // ---- merge + push ----------------------------------------------------

    private static float pick(float ownerValue, float otherValue) {
        // The owner's value, unless it has centred while the other source
        // is still holding a deflection.
        if (Math.abs(ownerValue) > 0.05f)
            return ownerValue;
        return otherValue;
    }

    private static void push() {
        final float lx = leftStickOwner == 0 ? pick(touchLX, padLX) : pick(padLX, touchLX);
        final float ly = leftStickOwner == 0 ? pick(touchLY, padLY) : pick(padLY, touchLY);
        final float rx = rightStickOwner == 0 ? pick(touchRX, padRX) : pick(padRX, touchRX);
        final float ry = rightStickOwner == 0 ? pick(touchRY, padRY) : pick(padRY, touchRY);
        try {
            nativeSetState(touchButtons | touchDpad | padButtons, lx, ly, rx, ry,
                    Math.max(touchLT, padLT), Math.max(touchRT, padRT));
        } catch (Throwable ignored) {
            // Library not loaded yet: the next change re-sends the full state.
        }
    }

    // ---- app lifecycle (Milestone 18) -----------------------------------
    // GameActivity forwards its lifecycle here: paused/backgrounded suspends the
    // emulation (the EE thread parks; the phone stops burning cores on a game
    // nobody is looking at), resumed wakes it, destroyed asks the runtime to
    // stop so the process can actually end. See ps2_android_host.h natively.

    public static void setAppSuspended(boolean suspended) {
        try {
            nativeSetSuspended(suspended);
        } catch (Throwable ignored) {
            // Library not loaded yet; nothing is running to suspend.
        }
    }

    public static void requestAppStop() {
        try {
            nativeRequestStop();
        } catch (Throwable ignored) {
            // Library not loaded yet; nothing is running to stop.
        }
    }

    private static native void nativeSetSuspended(boolean suspended);

    private static native void nativeRequestStop();

    private static native void nativeSetPresent(boolean present);

    private static native void nativeSetState(int buttons, float leftX, float leftY, float rightX, float rightY,
                                              float leftTrigger, float rightTrigger);

    // Milestone 5: the shell FPS label's text, from the runtime's
    // present-based statistics ("" until the library is loaded).
    public static native String nativeFpsText();
}
