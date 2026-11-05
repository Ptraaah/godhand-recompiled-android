package dev.ptraaa.godhand;

import android.os.ParcelFileDescriptor;

// Static bridge between the launcher/game activities and the native glue
// (android/glue/godhand_android_main.cpp). GameActivity fills these in
// BEFORE super.onCreate() starts the native thread, so by the time the
// port's main() asks, the answers are already here.
//
// Milestone 3: isoFilePath is the primary disc source - the real file
// copy of the picked ISO inside the app's internal files dir
// (getFilesDir()/godhand.iso), made by LauncherActivity. The port mounts
// a real file happily; it could not mount the borrowed descriptor below
// (0.3-m2c: "[CDIMAGE] cannot open /proc/self/fd/187"). The
// ParcelFileDescriptor path is kept only as a fallback for when no copy
// exists; it is held in a static field on purpose, because its fd must
// stay open for the whole run while the native side uses it.
public final class IsoHolder {
    public static ParcelFileDescriptor isoPfd;
    public static String isoFilePath = "";
    public static String logDir = "";
    public static String filesDir = "";

    // Milestone 5: the owner's launcher settings (LauncherActivity writes
    // them to SharedPreferences; GameActivity copies them here before the
    // library loads; the glue reads them in main() and turns them into the
    // port's environment, replacing Milestone 4's hard overwrites).
    // Defaults are Milestone 4's shipped values, except the thread
    // policy: its Android implementation arrived in the previous build
    // defaulting on, and Milestone 6 returns it to off by default (still
    // selectable in the launcher settings) until it is proven on device.
    public static String optGsScale = "1";
    public static String optThreadPolicy = "off";
    public static String optVsync = "1";
    public static String optShowFps = "on";
    // Milestone 7: the owner's disc prefetch choice. The port's gate is
    // the GODHAND_ISO_PREFETCH env flag in main.cpp (default ON there);
    // the glue used to pre-set it to "0" as a silent no-overwrite default
    // (since Milestone 2a), so it never actually ran inside the app. It
    // is now an explicit owner setting, default off; the glue sets the
    // env from this field with overwrite and logs the state every boot.
    public static String optDiscPrefetch = "off";
    // Milestone 10: the owner's GPU renderer choice. The port's gate is
    // the PS2X_GS_GPU env flag, read by GSThreadedBackend::MakeDefault
    // when the GS frontend is constructed inside the port's main; the
    // glue pre-set it to "0" as a silent no-overwrite default from
    // Milestone 2a on, so the GPU backend was never selected inside the
    // app. It is now an explicit owner setting, default off; the glue
    // sets the env from this field with overwrite and logs the state
    // every boot ("renderer: cpu/gpu (owner setting)").
    public static String optGpuRenderer = "off";
    // Path B stage B6: the owner's widescreen choice (16:9 game patch +
    // composed 16:9 frame). In the path B variant the glue seeds
    // PS2X_WIDESCREEN from this field (an external godhand.ini
    // PS2X_WIDESCREEN line still wins, as the kill switch). Stage B7:
    // default off — see GameActivity / PATH-B-B7.md.
    public static String optWidescreen = "off";

    public static String isoFdPath() {
        return isoPfd == null ? "" : "/proc/self/fd/" + isoPfd.getFd();
    }

    public static String isoFilePath() {
        return isoFilePath;
    }

    public static String logDir() {
        return logDir;
    }

    public static String filesDir() {
        return filesDir;
    }

    public static String gsScale() {
        return optGsScale;
    }

    public static String threadPolicy() {
        return optThreadPolicy;
    }

    public static String vsync() {
        return optVsync;
    }

    public static String showFps() {
        return optShowFps;
    }

    public static String discPrefetch() {
        return optDiscPrefetch;
    }

    public static String gpuRenderer() {
        return optGpuRenderer;
    }

    public static String widescreen() {
        return optWidescreen;
    }
}
