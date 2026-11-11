package dev.ptraaa.godhand;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;

// The game window. Extends the framework NativeActivity: the manifest's
// android.app.lib_name="godhand" makes it load libgodhand.so during
// super.onCreate(), whose raylib glue runs the port. onCreate prepares
// IsoHolder BEFORE super.onCreate() starts the native thread.
//
// Milestone 2b: every step is also written to godhand-boot.log from Java,
// as early as each step happens, so the log alone separates the failure
// points: this activity never entered / the ISO fd failed / the library
// load inside super.onCreate() threw / the native side never reached its
// own first line (the glue writes "JNI_OnLoad" at load time and a banner
// at main()).
//
// Milestone 3: IsoHolder.isoFilePath points at the launcher's real file
// copy of the ISO (internal files dir); the glue prefers it for --iso.
// The provider fd is still opened and held as the fallback for a run
// where the copy is somehow missing.
//
// Milestone 4: the play session's input. The touch overlay (TouchOverlay)
// hangs over the game surface and is marked present in TouchPad; a
// physical gamepad joins the same pad through the dispatchKeyEvent /
// onGenericMotionEvent overrides below, merged in TouchPad.
//
// Milestone 5:
//   - the overlay moves OUT of the activity hierarchy into its own
//     WindowManager window. In the hierarchy it shared the surface
//     NativeActivity takes for the native renderer, so the game's
//     presents overwrote its draws and it was invisible on device.
//     Its own window is composited above the game by the system.
//   - the owner's launcher settings are read from SharedPreferences:
//     scale / thread policy / vsync (and, since Milestone 7, disc
//     prefetch) go into IsoHolder for the glue;
//     opacity, button size and the FPS toggle shape the overlay; the
//     remapping is loaded into TouchPad (loadMapping) and the gamepad
//     path below resolves every key through it.
//   - a handler polls the native FPS text (~2x/second) into the
//     overlay's FPS label while the activity is resumed.
public class GameActivity extends NativeActivity {
    private boolean gamepadLogged;
    private boolean padKeyLT;
    private boolean padKeyRT;
    private float padAxisLT;
    private float padAxisRT;
    private InputManager inputManager;
    private InputManager.InputDeviceListener inputDeviceListener;

    private WindowManager windowManager;
    private TouchOverlay overlay;
    private boolean fpsOn;
    private boolean bootFailureShown;
    private final Handler fpsHandler = new Handler(Looper.getMainLooper());
    private final Runnable fpsTick = new Runnable() {
        @Override
        public void run() {
            if (overlay != null) {
                try {
                    overlay.setFpsText(TouchPad.nativeFpsText());
                } catch (Throwable ignored) {
                    // Library not loaded yet; the next tick retries.
                }
            }
            fpsHandler.postDelayed(this, 500);
        }
    };
    // m45: the boot-failure watch. The glue records why the port did not
    // start (TouchPad.nativeBootFailure); until m45 nothing on the Java
    // side ever asked, so a refused disc image (issue #3: a modded ISO
    // stopped by the boot ELF hash check) left the player staring at a
    // silent game screen. Polled on the same handler as the FPS label
    // but in BOTH modes and regardless of the FPS toggle: this is player
    // information, not a developer readout. Stops once shown.
    private final Runnable bootWatchTick = new Runnable() {
        @Override
        public void run() {
            if (bootFailureShown)
                return;
            String code = "";
            try {
                code = TouchPad.nativeBootFailure();
            } catch (Throwable ignored) {
                // Library not loaded yet; the next tick retries.
            }
            if (code != null && !code.isEmpty()) {
                bootFailureShown = true;
                BootLog.append(GameActivity.this,
                        "[shell] m45: the game did not start (" + code + ") - telling the player why");
                showBootFailure(code);
                return;
            }
            fpsHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BootLog.append(this, "GameActivity onCreate entered - preparing ISO before super.onCreate()");

        // The dirs go into IsoHolder FIRST: the glue's JNI_OnLoad reads
        // IsoHolder.logDir during the library load inside super.onCreate().
        File ext = getExternalFilesDir(null);
        IsoHolder.logDir = ext != null ? ext.getAbsolutePath() : getFilesDir().getAbsolutePath();
        IsoHolder.filesDir = getFilesDir().getAbsolutePath();

        // Milestone 5: the owner's settings, before anything native runs.
        // The glue reads the IsoHolder strings in main() and turns them
        // into the port's environment.
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS, MODE_PRIVATE);
        IsoHolder.optGsScale = prefs.getString(LauncherActivity.KEY_SET_SCALE, "1");
        // m40: the app mode, read up here because m42 needs it before
        // the performance options below.
        String appMode = prefs.getString(LauncherActivity.KEY_SET_MODE, LauncherActivity.MODE_PLAYER);
        boolean developerMode = LauncherActivity.MODE_DEVELOPER.equals(appMode);
        // m42 (Pablo's rule): in Player mode the performance options
        // are ON for the player automatically - thread placement runs
        // "auto" and disc prefetch runs "on" for this game run,
        // whatever the stored toggles say. The forcing happens only
        // here, at the read site: no preference is ever written, so
        // flipping back to Developer mode restores the owner's own
        // toggle values exactly. In Developer mode the stored values
        // rule, exactly as m41.
        IsoHolder.optThreadPolicy = (!developerMode
                || prefs.getBoolean(LauncherActivity.KEY_SET_THREADS, false)) ? "auto" : "off";
        IsoHolder.optVsync = prefs.getBoolean(LauncherActivity.KEY_SET_VSYNC, true) ? "1" : "0";
        IsoHolder.optShowFps = prefs.getBoolean(LauncherActivity.KEY_SET_FPS, true) ? "on" : "off";
        // Milestone 7: disc prefetch, owner-controlled, default off.
        // m42: forced on in Player mode, like thread placement above.
        IsoHolder.optDiscPrefetch = (!developerMode
                || prefs.getBoolean(LauncherActivity.KEY_SET_PREFETCH, false)) ? "on" : "off";
        // Milestone 10: GPU renderer, owner-controlled, default off.
        // m42: deliberately NOT forced in Player mode - it follows
        // the stored value in both modes, exactly as m41.
        // m47: the unset-preference fallback is now ON. The shipped
        // default renderer flipped from the bridge to the built-in
        // GS (the glue's compiled-in seed), and on the native path
        // this toggle is the real backend choice: on runs the ARUS
        // GPU backend, off is the explicit CPU-backend fallback.
        // Only the fallback changed - a saved owner value is read
        // back exactly as stored, as m41 did for widescreen.
        IsoHolder.optGpuRenderer = prefs.getBoolean(LauncherActivity.KEY_SET_GPU, true) ? "on" : "off";
        // Path B stage B6: widescreen 16:9, owner-controlled. Stage B7
        // made it default OFF (owner opt-in); m41 makes it default ON
        // (Pablo's order: true 16:9 is the shipped default). Only the
        // unset-preference fallback changed - a saved owner value is
        // read back exactly as stored.
        IsoHolder.optWidescreen = prefs.getBoolean(LauncherActivity.KEY_SET_WIDE, true) ? "on" : "off";
        // m40: the player's control style, handed to the overlay (the
        // app mode itself is read further up, before the performance
        // options - m42). Player mode keeps every debug/technical
        // overlay text hidden, so the FPS label stays off there even
        // if the (developer-only) FPS toggle is on.
        String controlStyle = prefs.getString(LauncherActivity.KEY_SET_CONTROL_STYLE, "a");
        fpsOn = prefs.getBoolean(LauncherActivity.KEY_SET_FPS, true) && developerMode;
        TouchPad.loadMapping(prefs);
        // m42: the settings line reports the EFFECTIVE values, and in
        // Player mode marks the two values Player mode forced, so a
        // returned log never reads as "off in the launcher but on in
        // the run" without a reason. Developer mode prints the stored
        // values bare, exactly as m41.
        String playerMark = developerMode ? "" : " (player mode)";
        BootLog.append(this, "settings loaded: scale=" + IsoHolder.optGsScale
                + " threads=" + IsoHolder.optThreadPolicy + playerMark + " vsync=" + IsoHolder.optVsync
                + " fps=" + IsoHolder.optShowFps
                + " prefetch=" + IsoHolder.optDiscPrefetch + playerMark
                + " gpu=" + IsoHolder.optGpuRenderer
                + " wide=" + IsoHolder.optWidescreen
                + " mode=" + appMode
                + " controlStyle=" + controlStyle
                + " opacity=" + prefs.getString(LauncherActivity.KEY_SET_OPACITY, "high"));

        // Milestone 3: the real file copy the launcher made of the ISO.
        // The glue prefers this path; the fd opened below is fallback.
        File isoCopy = new File(getFilesDir(), LauncherActivity.ISO_COPY_NAME);
        if (isoCopy.isFile() && isoCopy.length() > 0) {
            IsoHolder.isoFilePath = isoCopy.getAbsolutePath();
            BootLog.append(this, "iso copy present: " + IsoHolder.isoFilePath + " ("
                    + isoCopy.length() + " bytes) - the port gets the real file");
        } else {
            IsoHolder.isoFilePath = "";
            BootLog.append(this, "iso copy missing - the port falls back to the picker fd");
        }

        String uriText = prefs.getString(LauncherActivity.KEY_ISO_URI, "");
        IsoHolder.isoPfd = null;
        if (!uriText.isEmpty()) {
            try {
                ParcelFileDescriptor pfd =
                        getContentResolver().openFileDescriptor(Uri.parse(uriText), "r");
                IsoHolder.isoPfd = pfd;
                BootLog.append(this, "iso fd opened: " + IsoHolder.isoFdPath());
            } catch (Throwable t) {
                IsoHolder.isoPfd = null;
                BootLog.append(this, "iso fd open FAILED: " + BootLog.stack(t));
            }
        } else {
            BootLog.append(this, "no ISO stored - booting without a disc (the port logs its no-disc line)");
        }

        // Milestone 2c: load the library through the VM FIRST.
        // NativeActivity's own load (inside super.onCreate) is a native
        // dlopen, which never calls JNI_OnLoad - on 0.2-m2b that left the
        // glue's JNI bridge unset, so main() got empty strings for the
        // log dir, files dir and ISO and the boot ran mute and disc-less.
        // System.loadLibrary runs JNI_OnLoad; super.onCreate's dlopen
        // then finds the library already loaded and just starts the
        // native thread.
        BootLog.append(this, "loading libgodhand.so via System.loadLibrary so JNI_OnLoad runs");
        try {
            System.loadLibrary("godhand");
            BootLog.append(this, "System.loadLibrary(godhand) returned - JNI_OnLoad has run");
        } catch (Throwable t) {
            BootLog.append(this, "System.loadLibrary(godhand) FAILED: " + BootLog.stack(t));
        }

        BootLog.append(this, "calling super.onCreate() - NativeActivity loads libgodhand.so now");
        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable t) {
            BootLog.append(this, "super.onCreate() THREW (library load / native init): "
                    + BootLog.stack(t));
            throw t;
        }
        BootLog.append(this, "super.onCreate() returned - libgodhand.so loaded, native thread starting");

        // Milestone 5: the overlay in its OWN window, above the game's.
        // (Milestone 4 added it into the activity hierarchy, where it
        // shared the native renderer's surface and was never visible.)
        TouchPad.setOverlayPresent(true);
        float opacityFactor = opacityFactor(prefs.getString(LauncherActivity.KEY_SET_OPACITY, "high"));
        float sizeScale = sizeScale(prefs.getString(LauncherActivity.KEY_SET_BTNSIZE, "100"));
        overlay = new TouchOverlay(this, prefs, opacityFactor, sizeScale, fpsOn, controlStyle);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            windowManager.addView(overlay, params);
            BootLog.append(this, "touch overlay added (0.43-m42-playerperf) in its own window above the game surface: "
                    + "control style " + overlay.styleName() + ", mode " + appMode
                    + ", native EDIT button"
                    + (fpsOn ? ", FPS label on" : ", FPS label hidden"));
            // One layout line with every control's bounds (see TouchOverlay.describeLayout).
            overlay.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            overlay.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            BootLog.append(GameActivity.this,
                                    "overlay layout (0.43-m42-playerperf): " + overlay.describeLayout());
                        }
                    });
        } catch (Throwable t) {
            BootLog.append(this, "touch overlay FAILED to add: " + BootLog.stack(t));
            overlay = null;
        }
        watchInputDevices();
    }

    private static float opacityFactor(String level) {
        if ("low".equals(level))
            return 0.45f;
        if ("medium".equals(level))
            return 0.72f;
        return 1.0f; // high
    }

    private static float sizeScale(String percent) {
        try {
            return Math.max(0.5f, Math.min(1.6f, Integer.parseInt(percent) / 100f));
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Milestone 18: wake the emulation parked by onPause.
        TouchPad.setAppSuspended(false);
        if (fpsOn)
            fpsHandler.post(fpsTick);
        // m45: watch for a boot failure in both modes (see bootWatchTick).
        fpsHandler.post(bootWatchTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Milestone 18: park the emulation while this activity is not in front.
        // Through 0.18 a backgrounded game kept executing at full pace (only the
        // frontend thread blocked), heating the phone until the whole system
        // throttled - the frame drops the owner felt outside the game.
        TouchPad.setAppSuspended(true);
        fpsHandler.removeCallbacks(fpsTick);
        fpsHandler.removeCallbacks(bootWatchTick);
        // A finger or button held across a pause never delivers its UP
        // here; drop both sources so nothing sticks into the next run.
        if (overlay != null)
            overlay.leaveEditMode();
        TouchPad.setEditing(false);
        TouchPad.releaseTouch();
        TouchPad.setGamepadPresent(false);
    }

    @Override
    protected void onDestroy() {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Throwable ignored) {
            }
            overlay = null;
        }
        if (inputManager != null && inputDeviceListener != null)
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
        TouchPad.setOverlayPresent(false);
        TouchPad.setGamepadPresent(false);
        // Milestone 18: ask the runtime to stop so the frontend loop returns,
        // the port's main exits and the process can end with the activity.
        // Through 0.18 nothing stopped, and the emulation threads outlived
        // the activity inside the still-running app process.
        TouchPad.requestAppStop();
        super.onDestroy();
    }

    // m45: tell the player, in plain words, why the game did not start,
    // instead of leaving them on a silent screen. The dialog stays until
    // they dismiss it - no timer runs it away - and OK takes them back
    // to the launcher to pick another image; dismissing it instead
    // still leaves them free to leave this screen with Back. If the
    // dialog itself cannot go up, a long toast carries the same words.
    private void showBootFailure(String code) {
        final String message;
        if ("hash".equals(code)) {
            message = "This disc image is not the original release. Modded, patched or "
                    + "translated images cannot run, and neither can discs from other "
                    + "regions. Please use a clean dump of the original US disc "
                    + "(SLUS-21503).";
        } else if ("disc".equals(code)) {
            message = "This disc image is not the original US release of the game. "
                    + "Please use a clean dump of the original US disc (SLUS-21503).";
        } else if ("image".equals(code)) {
            message = "This file could not be read as a disc image. Please pick a "
                    + "complete, undamaged dump of the original US disc (SLUS-21503).";
        } else {
            message = "The game could not start. Please try another disc image. The "
                    + "boot log in the app folder says exactly what went wrong.";
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("The game could not start")
                    .setMessage(message)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .show();
        } catch (Throwable t) {
            BootLog.append(this, "[shell] m45: failure dialog failed, using a toast instead: "
                    + BootLog.stack(t));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    // If a controller goes away mid-press its UP never arrives; clearing
    // the pad source on ANY input-device removal is the cheap cure (the
    // next event from a still-connected pad re-marks it present).
    private void watchInputDevices() {
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager == null)
            return;
        inputDeviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                TouchPad.setGamepadPresent(false);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
            }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener, null);
    }

    // ---- physical gamepad ------------------------------------------------
    // Standard Android mapping onto player 1's pad, resolved through the
    // owner's remapping (Milestone 5): each key below names a PadMapping
    // key ("A", "B", ...), whose target the owner chose in the launcher.
    // A = cross, B = circle, X = square, Y = triangle by default, plus
    // Start/Select, L1/R1, L3/R3 as buttons and L2/R2 as triggers
    // (digital keys or the analog brake/throttle axes, whichever pushes
    // further, routed to whatever target L2/R2 were mapped to). The
    // D-pad is fixed (keys or the hat axes). AXIS_X/Y is the left stick
    // and AXIS_Z/RZ the right stick, both with a small deadzone.
    // Everything lands in TouchPad next to the overlay's state; the
    // guest cannot tell the two sources apart.

    private static boolean isGamepadEvent(InputEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private void noteGamepadSeen(InputEvent event) {
        TouchPad.setGamepadPresent(true);
        if (gamepadLogged)
            return;
        gamepadLogged = true;
        String name = "unknown device";
        InputDevice device = event.getDevice();
        if (device != null && device.getName() != null && !device.getName().isEmpty())
            name = device.getName();
        BootLog.append(this, "gamepad input seen: " + name);
    }

    private void pushPadTriggers() {
        TouchPad.routePadTrigger(TouchPad.padTargetFor("L2"), Math.max(padKeyLT ? 1f : 0f, padAxisLT));
        TouchPad.routePadTrigger(TouchPad.padTargetFor("R2"), Math.max(padKeyRT ? 1f : 0f, padAxisRT));
    }

    // The PadMapping key for a gamepad button keycode, or null when the
    // key is handled elsewhere (triggers, D-pad) or not a pad button.
    private static String padKeyName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return "A";
            case KeyEvent.KEYCODE_BUTTON_B:
                return "B";
            case KeyEvent.KEYCODE_BUTTON_X:
                return "X";
            case KeyEvent.KEYCODE_BUTTON_Y:
                return "Y";
            case KeyEvent.KEYCODE_BUTTON_START:
                return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_L1:
                return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1:
                return "R1";
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return "R3";
            default:
                return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isGamepadEvent(event)) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
                boolean isLeft = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    noteGamepadSeen(event);
                    if (isLeft)
                        padKeyLT = true;
                    else
                        padKeyRT = true;
                    pushPadTriggers();
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (isLeft)
                        padKeyLT = false;
                    else
                        padKeyRT = false;
                    pushPadTriggers();
                }
                return true;
            }
            int dpadBit;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    dpadBit = TouchPad.DPAD_UP;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    dpadBit = TouchPad.DPAD_DOWN;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    dpadBit = TouchPad.DPAD_LEFT;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    dpadBit = TouchPad.DPAD_RIGHT;
                    break;
                default:
                    dpadBit = -1;
                    break;
            }
            if (dpadBit >= 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    noteGamepadSeen(event);
                    TouchPad.setPadButton(dpadBit, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    TouchPad.setPadButton(dpadBit, false);
                }
                return true;
            }
            String keyName = padKeyName(event.getKeyCode());
            if (keyName == null)
                return super.dispatchKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                noteGamepadSeen(event);
                TouchPad.applyPadTarget(TouchPad.padTargetFor(keyName), true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                TouchPad.applyPadTarget(TouchPad.padTargetFor(keyName), false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static float stickAxis(float value) {
        float magnitude = Math.abs(value);
        if (magnitude < 0.15f)
            return 0f;
        // Rescale past the deadzone so the stick still reaches full tilt.
        return Math.signum(value) * (magnitude - 0.15f) / 0.85f;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGamepadEvent(event) && event.getAction() == MotionEvent.ACTION_MOVE) {
            noteGamepadSeen(event);
            TouchPad.setPadLeftStick(stickAxis(event.getAxisValue(MotionEvent.AXIS_X)),
                    stickAxis(event.getAxisValue(MotionEvent.AXIS_Y)));
            TouchPad.setPadRightStick(stickAxis(event.getAxisValue(MotionEvent.AXIS_Z)),
                    stickAxis(event.getAxisValue(MotionEvent.AXIS_RZ)));
            padAxisLT = Math.max(event.getAxisValue(MotionEvent.AXIS_BRAKE),
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER));
            padAxisRT = Math.max(event.getAxisValue(MotionEvent.AXIS_THROTTLE),
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
            pushPadTriggers();
            // The D-pad often arrives as a hat switch, not as key events.
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            TouchPad.setPadButton(TouchPad.DPAD_LEFT, hatX < -0.5f);
            TouchPad.setPadButton(TouchPad.DPAD_RIGHT, hatX > 0.5f);
            TouchPad.setPadButton(TouchPad.DPAD_UP, hatY < -0.5f);
            TouchPad.setPadButton(TouchPad.DPAD_DOWN, hatY > 0.5f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
