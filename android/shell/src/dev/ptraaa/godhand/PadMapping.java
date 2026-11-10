package dev.ptraaa.godhand;

import android.content.SharedPreferences;

// Milestone 5: control remapping, as data. Every on-screen button and every
// physical-gamepad button can be pointed at any PS2 control; the choice is
// a SharedPreferences string ("map_touch_<control>" / "map_pad_<key>"),
// edited from the launcher's REMAP section and read once when the game
// starts. The native side is untouched: TouchPad still pushes one merged
// bitmask + axes through the same JNI bridge, only the meaning of each
// finger/button is data now.
//
// Targets are the seam's controls. L2/R2 are special: in the port's
// bindings they are analog trigger AXES, not bits, so a target of L2/R2
// writes the matching trigger instead of a bit (targetBit returns -1).
public final class PadMapping {
    public static final String LEFT_TRIGGER = "L2";
    public static final String RIGHT_TRIGGER = "R2";

    // The cycle order in the settings UI.
    public static final String[] TARGETS = {
            "TRIANGLE", "CIRCLE", "CROSS", "SQUARE",
            "L1", "R1", "L2", "R2", "L3", "R3",
            "START", "SELECT",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
    };
    private static final String[] TARGET_LABELS = {
            "Triangle", "Circle", "Cross", "Square",
            "L1", "R1", "L2", "R2", "L3", "R3",
            "Start", "Select",
            "D-pad up", "D-pad down", "D-pad left", "D-pad right",
    };

    // Remappable touch controls (the two sticks are layout-only).
    public static final String[] TOUCH_CONTROLS = {
            "btn_triangle", "btn_circle", "btn_cross", "btn_square",
            "btn_l1", "btn_l2", "btn_r1", "btn_r2", "btn_start", "btn_select",
            "btn_dpad_up", "btn_dpad_down", "btn_dpad_left", "btn_dpad_right",
            "btn_l3", "btn_r3",
    };
    private static final String[] TOUCH_LABELS = {
            "Triangle button", "Circle button", "Cross button", "Square button",
            "L1 button", "L2 button", "R1 button", "R2 button", "Start button", "Select button",
            "D-pad up button", "D-pad down button", "D-pad left button", "D-pad right button",
            "L3 button", "R3 button",
    };
    private static final String[] TOUCH_DEFAULTS = {
            "TRIANGLE", "CIRCLE", "CROSS", "SQUARE",
            "L1", "L2", "R1", "R2", "START", "SELECT",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
            "L3", "R3",
    };

    // Remappable physical-gamepad buttons (the D-pad and sticks are fixed).
    public static final String[] PAD_KEYS = {
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "START", "SELECT",
    };
    private static final String[] PAD_DEFAULTS = {
            "CROSS", "CIRCLE", "SQUARE", "TRIANGLE", "L1", "R1", "L2", "R2", "L3", "R3", "START", "SELECT",
    };

    private PadMapping() {
    }

    public static String prefKeyTouch(String control) {
        return "map_touch_" + control;
    }

    public static String prefKeyPad(String key) {
        return "map_pad_" + key;
    }

    public static String touchLabel(String control) {
        for (int i = 0; i < TOUCH_CONTROLS.length; i++)
            if (TOUCH_CONTROLS[i].equals(control))
                return TOUCH_LABELS[i];
        return control;
    }

    public static String defaultTouchTarget(String control) {
        for (int i = 0; i < TOUCH_CONTROLS.length; i++)
            if (TOUCH_CONTROLS[i].equals(control))
                return TOUCH_DEFAULTS[i];
        return "CROSS";
    }

    public static String defaultPadTarget(String key) {
        for (int i = 0; i < PAD_KEYS.length; i++)
            if (PAD_KEYS[i].equals(key))
                return PAD_DEFAULTS[i];
        return "CROSS";
    }

    public static String touchTarget(SharedPreferences prefs, String control) {
        String value = prefs.getString(prefKeyTouch(control), null);
        return isTarget(value) ? value : defaultTouchTarget(control);
    }

    public static String padTarget(SharedPreferences prefs, String key) {
        String value = prefs.getString(prefKeyPad(key), null);
        return isTarget(value) ? value : defaultPadTarget(key);
    }

    public static boolean isTarget(String value) {
        if (value == null)
            return false;
        for (String target : TARGETS)
            if (target.equals(value))
                return true;
        return false;
    }

    public static String targetLabel(String target) {
        for (int i = 0; i < TARGETS.length; i++)
            if (TARGETS[i].equals(target))
                return TARGET_LABELS[i];
        return target;
    }

    public static String nextTarget(String current) {
        for (int i = 0; i < TARGETS.length; i++)
            if (TARGETS[i].equals(current))
                return TARGETS[(i + 1) % TARGETS.length];
        return TARGETS[0];
    }

    public static boolean isTrigger(String target) {
        return LEFT_TRIGGER.equals(target) || RIGHT_TRIGGER.equals(target);
    }

    // The TouchPad bit for a target, or -1 for the triggers (and unknowns).
    public static int targetBit(String target) {
        if (target == null)
            return -1;
        switch (target) {
            case "CROSS":
                return TouchPad.CROSS;
            case "CIRCLE":
                return TouchPad.CIRCLE;
            case "SQUARE":
                return TouchPad.SQUARE;
            case "TRIANGLE":
                return TouchPad.TRIANGLE;
            case "SELECT":
                return TouchPad.SELECT;
            case "START":
                return TouchPad.START;
            case "L3":
                return TouchPad.L3;
            case "R3":
                return TouchPad.R3;
            case "L1":
                return TouchPad.L1;
            case "R1":
                return TouchPad.R1;
            case "DPAD_UP":
                return TouchPad.DPAD_UP;
            case "DPAD_DOWN":
                return TouchPad.DPAD_DOWN;
            case "DPAD_LEFT":
                return TouchPad.DPAD_LEFT;
            case "DPAD_RIGHT":
                return TouchPad.DPAD_RIGHT;
            default:
                return -1;
        }
    }
}
