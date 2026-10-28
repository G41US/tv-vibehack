package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;

/**
 * "Speed up" (Quick panel and Settings → Storage): clears every app's cache and closes background apps
 * through the TV's own debugging connection (AdbSelf). `am kill-all` only closes apps Android itself
 * counts as safe to close, so music that's playing and the app on screen keep going.
 */
final class SpeedUp {

    private SpeedUp() {
    }

    /** Runs it, shows what was freed, then calls {@code after} once that message closes. */
    static void run(Activity act, Runnable after) {
        GlassMenu.Progress p = GlassMenu.progress(act, act.getString(R.string.su_working));
        p.set("");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            long space0 = freeSpace(), mem0 = freeMemory(act);
            int apps0 = -1, apps1 = -1;
            boolean done = false;
            if (AdbSelf.ready(act)) {
                try (AdbSelf adb = AdbSelf.connect(act, SelfSetup.PORT, 30_000,
                        () -> main.post(() -> p.set(act.getString(R.string.ss_allow))))) {
                    main.post(() -> p.set(""));
                    apps0 = appCount(adb);
                    adb.shell("pm trim-caches 999G >/dev/null 2>&1; am kill-all");
                    Thread.sleep(1500);   // let Android finish closing them before counting
                    apps1 = appCount(adb);
                    done = true;
                } catch (Exception ignored) {
                }
            }
            String msg = !done ? act.getString(R.string.su_need)
                    : act.getString(R.string.su_done,
                    Formatter.formatShortFileSize(act, Math.max(0, freeSpace() - space0)),
                    Formatter.formatShortFileSize(act, Math.max(0, freeMemory(act) - mem0)),
                    Math.max(0, apps0 - apps1));
            main.post(() -> {
                p.dismiss();
                if (act.isFinishing() || act.isDestroyed()) return;
                GlassMenu.message(act, act.getString(R.string.su_title), msg,
                        new String[]{act.getString(android.R.string.ok)}, null)
                        .setOnDismissListener(d -> after.run());
            });
        }, "speed-up").start();
    }

    /** Running app processes (uids u0_a…); -1 if ps can't say. */
    private static int appCount(AdbSelf adb) {
        try {
            return Integer.parseInt(adb.shell("ps -A -o USER | grep -c '^u0_a'").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long freeSpace() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long freeMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        return mi.availMem;
    }
}
