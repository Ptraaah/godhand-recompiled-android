package dev.ptraaa.godhand;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Shared Java-side boot log writer (Milestone 2b). The 2a shell wrote
// NOTHING before the native glue ran, so a launch that died anywhere
// before the glue left no evidence at all. Now every step of the launch
// ladder appends a timestamped line to the same godhand-boot.log the
// native side writes to, in the app's external files dir
// (Android/data/dev.ptraaa.godhand/files/). Logging must never crash the
// shell: every failure in here is swallowed.
final class BootLog {
    private BootLog() {
    }

    static File file(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File dir = ext != null ? ext : ctx.getFilesDir();
        return new File(dir, "godhand-boot.log");
    }

    static void append(Context ctx, String line) {
        try {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            FileOutputStream out = new FileOutputStream(file(ctx), true);
            out.write(("[" + stamp + "] " + line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }
}
