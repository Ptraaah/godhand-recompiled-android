package dev.ptraaa.godhand;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

// The on-screen controls. Milestone 4 laid them over the game surface in
// GameActivity and they were INVISIBLE on the owner's phone: NativeActivity
// takes the window's surface for the native renderer (Window.takeSurface),
// so views added into the activity hierarchy share the surface the game
// presents into 60 times a second and their draws are overwritten before
// they are ever composited. Milestone 5 therefore hosts this overlay in
// its OWN window (GameActivity adds it through WindowManager), which the
// system compositor layers above the game window - the same reason an
// on-screen keyboard can draw over a game. The paints are also brighter
// than Milestone 4's, scaled by the owner's opacity setting.
//
// Layout: every control is positioned absolutely (pixels) over the root.
// First run uses the Milestone 4 arrangement; the overlay's EDIT mode
// (the EDIT pill, top centre) lets the owner drag any control anywhere -
// positions persist to SharedPreferences ("lay_<id>_x/y") and are the
// layout from then on. Button sizes scale with the owner's size setting.
// The sticks are layout-only; the ten buttons send through the remapping
// (PadMapping via TouchPad.setTouchControl).
//
// Edit mode suspends overlay input into the game (TouchPad.setEditing),
// shows a RESET pill that restores the default layout, and outlines the
// controls so it is obvious what can be dragged.
public final class TouchOverlay extends FrameLayout {
    public static final String ID_STICK_LEFT = "stick_left";
    public static final String ID_STICK_RIGHT = "stick_right";

    private final float density;
    private final SharedPreferences prefs;
    private final float opacityFactor;
    private final float sizeScale;

    private final Map<String, View> controls = new LinkedHashMap<>();
    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    private boolean positionsApplied;
    private int appliedRootW;
    private int appliedRootH;
    private boolean editing;

    private TextView fpsLabel;
    private PadButtonView editPill;
    private PadButtonView resetPill;

    // Anchor kinds for the default arrangement (margins in dp).
    private static final int LEFT_TOP = 0;
    private static final int LEFT_BOTTOM = 1;
    private static final int RIGHT_TOP = 2;
    private static final int RIGHT_BOTTOM = 3;
    private static final int CENTRE_TOP = 4; // margin = offset from the centre line

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

    public TouchOverlay(Context context, SharedPreferences prefs, float opacityFactor, float sizeScale,
                        boolean fpsVisible) {
        super(context);
        this.prefs = prefs;
        this.opacityFactor = opacityFactor;
        this.sizeScale = sizeScale;
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);

        // Left stick (movement).
        StickView leftStick = new StickView(context, opacityFactor, (x, y) -> TouchPad.setLeftStick(x, y));
        register(ID_STICK_LEFT, leftStick, 136, 136, new Anchor(LEFT_BOTTOM, 30, 40));

        // Right stick (dodge).
        StickView rightStick = new StickView(context, opacityFactor, (x, y) -> TouchPad.setRightStick(x, y));
        register(ID_STICK_RIGHT, rightStick, 108, 108, new Anchor(RIGHT_BOTTOM, 256, 44));

        // Face diamond - through the remapping.
        register("btn_triangle", button(context, "△", true, "btn_triangle"), 62, 62,
                new Anchor(RIGHT_BOTTOM, 104, 158));
        register("btn_circle", button(context, "○", true, "btn_circle"), 62, 62,
                new Anchor(RIGHT_BOTTOM, 30, 96));
        register("btn_cross", button(context, "×", true, "btn_cross"), 62, 62,
                new Anchor(RIGHT_BOTTOM, 104, 34));
        register("btn_square", button(context, "□", true, "btn_square"), 62, 62,
                new Anchor(RIGHT_BOTTOM, 178, 96));

        // Shoulders along the top edge.
        register("btn_l1", button(context, "L1", false, "btn_l1"), 66, 42, new Anchor(LEFT_TOP, 30, 16));
        register("btn_l2", button(context, "L2", false, "btn_l2"), 66, 42, new Anchor(LEFT_TOP, 106, 16));
        register("btn_r2", button(context, "R2", false, "btn_r2"), 66, 42, new Anchor(RIGHT_TOP, 106, 16));
        register("btn_r1", button(context, "R1", false, "btn_r1"), 66, 42, new Anchor(RIGHT_TOP, 30, 16));

        // Start / Select, small and top-centre.
        register("btn_select", button(context, "SELECT", false, "btn_select"), 64, 34,
                new Anchor(CENTRE_TOP, -74, 16));
        register("btn_start", button(context, "START", false, "btn_start"), 64, 34,
                new Anchor(CENTRE_TOP, 10, 16));

        // The FPS label (text fed by GameActivity's poller).
        fpsLabel = new TextView(context);
        fpsLabel.setText("FPS ...");
        fpsLabel.setTextSize(15);
        fpsLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        fpsLabel.setTextColor(Color.WHITE);
        fpsLabel.setBackgroundColor(Color.argb(150, 0, 0, 0));
        fpsLabel.setPadding(dp(10), dp(3), dp(10), dp(3));
        fpsLabel.setVisibility(fpsVisible ? VISIBLE : GONE);
        LayoutParams fpsParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        fpsParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        fpsParams.topMargin = dp(58);
        addView(fpsLabel, fpsParams);

        // EDIT pill and its edit-mode RESET companion. Not game controls:
        // they never write to TouchPad and are not draggable/remappable.
        editPill = new PadButtonView(context, "EDIT", false, opacityFactor, down -> {
            if (down)
                setEditing(!editing);
        });
        addView(editPill, new LayoutParams(dp(64 * sizeScale), dp(34 * sizeScale)));
        resetPill = new PadButtonView(context, "RESET", false, opacityFactor, down -> {
            if (down)
                resetLayout();
        });
        resetPill.setVisibility(GONE);
        addView(resetPill, new LayoutParams(dp(74 * sizeScale), dp(34 * sizeScale)));
    }

    private PadButtonView button(Context context, String label, boolean round, String controlId) {
        return new PadButtonView(context, label, round, opacityFactor,
                down -> TouchPad.setTouchControl(controlId, down));
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
        // First layout places the controls; a later size change (the two
        // landscape orientations have the same size on most phones, but a
        // fold or a display-size change may not) re-applies with clamping.
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
        // The fixed pills sit right of the centre row.
        editPill.setX(rootW / 2f + dp(94));
        editPill.setY(dp(16));
        resetPill.setX(rootW / 2f + dp(166));
        resetPill.setY(dp(16));
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
        BootLog.append(getContext(), "overlay layout reset to defaults (0.19-m18)");
    }

    // One line for the boot log: every control's on-screen bounds, so a
    // future "the controls are invisible" report can be told apart from
    // "the controls are somewhere else / zero-sized" from the log alone.
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

    // Used when the activity pauses mid-edit: leave edit mode cleanly so
    // the pills, outlines and the TouchPad gate stay in step.
    public void leaveEditMode() {
        if (editing)
            setEditing(false);
    }

    private void setEditing(boolean on) {
        editing = on;
        TouchPad.setEditing(on);
        editPill.setLabel(on ? "DONE" : "EDIT");
        resetPill.setVisibility(on ? VISIBLE : GONE);
        for (View view : controls.values()) {
            if (view instanceof StickView)
                ((StickView) view).setEditingVisual(on);
            else if (view instanceof PadButtonView)
                ((PadButtonView) view).setEditingVisual(on);
            view.setOnTouchListener(on ? dragListener : null);
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

    // ---- the control views ---------------------------------------------------

    interface StickListener {
        void onStick(float x, float y);
    }

    interface ButtonAction {
        void set(boolean down);
    }

    // A virtual analog stick: a base ring and a knob that follows the
    // finger, clamped to the ring. Reports -1..1, down/right positive.
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
            paint.setColor(Color.argb(alpha(110), 10, 10, 16));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(editingVisual ? Color.argb(255, 255, 210, 60)
                    : Color.argb(alpha(200), 255, 255, 255));
            canvas.drawCircle(cx, cy, radius, paint);
            float knobRadius = radius * 0.42f;
            float travel = radius - knobRadius;
            knobPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.argb(alpha(185), 235, 235, 240));
            canvas.drawCircle(cx + knobX * travel, cy + knobY * travel, knobRadius, knobPaint);
        }
    }

    // One touch button: a translucent circle (face buttons) or pill
    // (shoulders, Start/Select, EDIT/RESET) with a label, lit while pressed.
    static final class PadButtonView extends View {
        private String label;
        private final boolean round;
        private final float opacityFactor;
        private final ButtonAction action;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pointerId = -1;
        private boolean pressed;
        private boolean editingVisual;

        PadButtonView(Context context, String label, boolean round, float opacityFactor, ButtonAction action) {
            super(context);
            this.label = label;
            this.round = round;
            this.opacityFactor = opacityFactor;
            this.action = action;
            setClickable(true);
        }

        void setLabel(String text) {
            label = text;
            invalidate();
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
                        applyPressed(true);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointerId == -1) {
                        pointerId = event.getPointerId(event.getActionIndex());
                        applyPressed(true);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == pointerId && event.getPointerCount() == 1)
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

        private void applyPressed(boolean down) {
            if (pressed == down)
                return;
            pressed = down;
            action.set(down);
            invalidate();
        }

        private void release() {
            pointerId = -1;
            applyPressed(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressed ? Color.argb(alpha(210), 210, 210, 215) : Color.argb(alpha(130), 12, 12, 18));
            if (round)
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - 2f, paint);
            else
                canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, h / 2f, h / 2f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(editingVisual ? 3.5f : 2f);
            paint.setColor(editingVisual ? Color.argb(255, 255, 210, 60)
                    : Color.argb(alpha(210), 255, 255, 255));
            if (round)
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - 2f, paint);
            else
                canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, h / 2f, h / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pressed ? Color.argb(255, 20, 20, 26) : Color.argb(alpha(255), 255, 255, 255));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(round ? h * 0.42f : h * 0.36f);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(label, w / 2f, baseline, paint);
        }
    }
}
