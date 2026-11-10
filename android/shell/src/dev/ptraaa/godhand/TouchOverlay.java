package dev.ptraaa.godhand;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

// The on-screen controls, m39: every button is a REAL native Android
// android.widget.Button - framework pressed states (StateListDrawable),
// framework touch handling, haptic ticks on press - styled to match the
// launcher (dark translucent fill, white label, the product accent
// orange while pressed, amber outline in edit mode). The two analog
// sticks stay custom views (a stick cannot be a Button) but wear the
// same colours. Hosted in its own WindowManager window above the game
// surface (Milestone 5, unchanged) - NativeActivity takes the activity
// window's surface, so hierarchy views are overwritten by the renderer.
//
// God Hand layout (designed for this game, not inherited):
//   left stick bottom-left = movement; right stick bottom-centre-right
//   = the dodge stick (God Hand dodges/backflips on the right stick);
//   face diamond bottom-right (Square/Triangle/Circle/Cross attacks and
//   guard/interact); D-pad cluster ABOVE the left stick (menus, item /
//   roulette selection - the old overlay had NO D-pad buttons at all);
//   L3/R3 pills beside the sticks (stick clicks); L1/L2 and R1/R2
//   shoulder pills along the top edges; Select/Start small top-centre;
//   EDIT/DONE and RESET are native Buttons too.
//
// Wiring is unchanged: buttons call TouchPad.setTouchControl(controlId)
// through PadMapping (extended in m39 with the D-pad and L3/R3 touch
// controls), sticks call setLeftStick / setRightStick. EDIT mode drags
// any control, persists "lay_<id>_x/y", and suspends game input via
// TouchPad.setEditing, exactly as before.
public final class TouchOverlay extends FrameLayout {
    public static final String ID_STICK_LEFT = "stick_left";
    public static final String ID_STICK_RIGHT = "stick_right";

    // Product accent (matches LauncherActivity): orange.
    static final int ACCENT = Color.rgb(255, 122, 26);
    private static final int EDIT_STROKE = Color.rgb(255, 210, 60);

    private final float density;
    private final SharedPreferences prefs;
    private final float opacityFactor;
    private final float sizeScale;

    private final Map<String, View> controls = new LinkedHashMap<>();
    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    private final Map<Button, PadBtn> padButtons = new LinkedHashMap<>();
    private boolean positionsApplied;
    private int appliedRootW;
    private int appliedRootH;
    private boolean editing;

    private TextView fpsLabel;
    private Button editPill;
    private Button resetPill;

    private static final int LEFT_TOP = 0;
    private static final int LEFT_BOTTOM = 1;
    private static final int RIGHT_TOP = 2;
    private static final int RIGHT_BOTTOM = 3;
    private static final int CENTRE_TOP = 4;

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

    public TouchOverlay(Context context, SharedPreferences prefs, float opacityFactor, float sizeScale,
                        boolean fpsVisible) {
        super(context);
        this.prefs = prefs;
        this.opacityFactor = opacityFactor;
        this.sizeScale = sizeScale;
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);

        // Left stick (movement), bottom-left corner.
        StickView leftStick = new StickView(context, opacityFactor, (x, y) -> TouchPad.setLeftStick(x, y));
        register(ID_STICK_LEFT, leftStick, 136, 136, new Anchor(LEFT_BOTTOM, 24, 36));

        // Right stick (dodge), bottom area left of the face diamond.
        StickView rightStick = new StickView(context, opacityFactor, (x, y) -> TouchPad.setRightStick(x, y));
        register(ID_STICK_RIGHT, rightStick, 108, 108, new Anchor(RIGHT_BOTTOM, 248, 40));

        // D-pad cluster above the left stick, in a cross.
        register("btn_dpad_up", button(context, "▲", true, "btn_dpad_up", Color.WHITE), 44, 44,
                new Anchor(LEFT_BOTTOM, 82, 284));
        register("btn_dpad_left", button(context, "◀", true, "btn_dpad_left", Color.WHITE), 44, 44,
                new Anchor(LEFT_BOTTOM, 38, 240));
        register("btn_dpad_right", button(context, "▶", true, "btn_dpad_right", Color.WHITE), 44, 44,
                new Anchor(LEFT_BOTTOM, 126, 240));
        register("btn_dpad_down", button(context, "▼", true, "btn_dpad_down", Color.WHITE), 44, 44,
                new Anchor(LEFT_BOTTOM, 82, 196));

        // Face diamond, bottom-right - PlayStation colours on the labels.
        register("btn_triangle", button(context, "△", true, "btn_triangle", Color.rgb(126, 212, 145)), 64, 64,
                new Anchor(RIGHT_BOTTOM, 102, 160));
        register("btn_circle", button(context, "○", true, "btn_circle", Color.rgb(255, 122, 107)), 64, 64,
                new Anchor(RIGHT_BOTTOM, 28, 96));
        register("btn_cross", button(context, "×", true, "btn_cross", Color.rgb(138, 180, 255)), 64, 64,
                new Anchor(RIGHT_BOTTOM, 102, 32));
        register("btn_square", button(context, "□", true, "btn_square", Color.rgb(255, 154, 213)), 64, 64,
                new Anchor(RIGHT_BOTTOM, 176, 96));

        // Stick clicks beside the sticks.
        register("btn_l3", button(context, "L3", false, "btn_l3", Color.WHITE), 50, 34,
                new Anchor(LEFT_BOTTOM, 170, 50));
        register("btn_r3", button(context, "R3", false, "btn_r3", Color.WHITE), 50, 34,
                new Anchor(RIGHT_BOTTOM, 366, 50));

        // Shoulders along the top edge.
        register("btn_l1", button(context, "L1", false, "btn_l1", Color.WHITE), 68, 44, new Anchor(LEFT_TOP, 24, 14));
        register("btn_l2", button(context, "L2", false, "btn_l2", Color.WHITE), 68, 44, new Anchor(LEFT_TOP, 100, 14));
        register("btn_r2", button(context, "R2", false, "btn_r2", Color.WHITE), 68, 44, new Anchor(RIGHT_TOP, 100, 14));
        register("btn_r1", button(context, "R1", false, "btn_r1", Color.WHITE), 68, 44, new Anchor(RIGHT_TOP, 24, 14));

        // Start / Select, small and top-centre.
        register("btn_select", button(context, "SELECT", false, "btn_select", Color.WHITE), 68, 36,
                new Anchor(CENTRE_TOP, -78, 14));
        register("btn_start", button(context, "START", false, "btn_start", Color.WHITE), 68, 36,
                new Anchor(CENTRE_TOP, 10, 14));

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
    }

    // ---- native button construction --------------------------------------

    private int alpha(int base) {
        return Math.round(base * opacityFactor);
    }

    // The shared button look: dark translucent fill with a light rim;
    // while the framework pressed state is on, the accent fills it.
    // In edit mode the rim turns amber so draggable controls read as such.
    private StateListDrawable buttonBackground(boolean round, boolean editingLook) {
        GradientDrawable normal = new GradientDrawable();
        if (round)
            normal.setShape(GradientDrawable.OVAL);
        else
            normal.setCornerRadius(dp(18));
        normal.setColor(Color.argb(alpha(135), 13, 14, 20));
        normal.setStroke(dp(editingLook ? 3 : 2),
                editingLook ? EDIT_STROKE : Color.argb(alpha(215), 255, 255, 255));
        GradientDrawable pressed = new GradientDrawable();
        if (round)
            pressed.setShape(GradientDrawable.OVAL);
        else
            pressed.setCornerRadius(dp(18));
        pressed.setColor(Color.argb(alpha(225), Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        pressed.setStroke(dp(2), Color.argb(alpha(255), 255, 255, 255));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(StateSet.WILD_CARD, normal);
        return states;
    }

    private Button button(Context context, String label, boolean round, String controlId, int textColor) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(round && label.length() == 1 ? 20 : 12);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        PadBtn pad = new PadBtn(controlId, round);
        padButtons.put(b, pad);
        b.setBackground(buttonBackground(round, false));
        b.setOnTouchListener(gameTouchListener);
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

    // Press/release for every game button. A Button only ever receives
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

    private void releaseButton(Button b) {
        PadBtn pad = padButtons.get(b);
        if (pad != null && pad.downSent) {
            pad.downSent = false;
            TouchPad.setTouchControl(pad.controlId, false);
        }
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
        BootLog.append(getContext(), "overlay layout reset to defaults (0.40-m39)");
    }

    public String describeLayout() {
        StringBuilder out = new StringBuilder();
        out.append("root=").append(getWidth()).append('x').append(getHeight());
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
        for (Map.Entry<String, View> entry : controls.entrySet()) {
            View view = entry.getValue();
            if (view instanceof StickView) {
                ((StickView) view).setEditingVisual(on);
                view.setOnTouchListener(on ? dragListener : null);
            } else if (view instanceof Button) {
                Button b = (Button) view;
                if (on) {
                    releaseButton(b);
                    b.setOnTouchListener(dragListener);
                } else {
                    b.setOnTouchListener(gameTouchListener);
                }
                PadBtn pad = padButtons.get(b);
                if (pad != null)
                    b.setBackground(buttonBackground(pad.round, on));
            }
        }
        BootLog.append(getContext(), "overlay edit mode " + (on ? "ON - drag the controls, DONE to finish"
                : "off - layout saved"));
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

    // ---- the sticks (custom views; a stick cannot be a Button) -------------

    interface StickListener {
        void onStick(float x, float y);
    }

    // A virtual analog stick: a base ring and a knob that follows the
    // finger, clamped to the ring. Reports -1..1, down/right positive.
    // Colours match the native buttons: dark fill, light rim (amber in
    // edit mode), accent-orange knob centre.
    static final class StickView extends View {
        private final StickListener listener;
        private final float opacityFactor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pointerId = -1;
        private float knobX;
        private float knobY;
        private boolean editingVisual;

        StickView(Context context, float opacityFactor, StickListener listener) {
            super(context);
            this.listener = listener;
            this.opacityFactor = opacityFactor;
            setClickable(true);
        }

        void setEditingVisual(boolean on) {
            editingVisual = on;
            invalidate();
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
                        update(event, 0);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (pointerId == -1) {
                        int index = event.getActionIndex();
                        pointerId = event.getPointerId(index);
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
}
