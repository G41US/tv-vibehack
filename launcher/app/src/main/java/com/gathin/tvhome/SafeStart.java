package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Safe start: if TV Home crashes (or never finishes starting) twice in a row on the same build, it
 * starts in safe mode with the decorative effects off (moving wallpaper, weather, album lighting), so
 * the box is never left without a working home screen. A new build clears it, and the person can turn
 * the effects back on. The last problem is kept for the TV check page.
 */
final class SafeStart {

    private static final String PREFS = "safe", KEY_COUNT = "crashes", KEY_BUILD = "build",
            KEY_STARTING = "starting", KEY_LAST = "last", KEY_TOLD = "told";
    private static Boolean on;

    private SafeStart() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long build(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Start of MainActivity: count an unfinished last start, watch for crashes, decide safe mode. */
    static void begin(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        long b = build(app);
        SharedPreferences.Editor e = p.edit();
        if (p.getLong(KEY_BUILD, -1) != b) {   // a new build: fresh start, safe mode off
            e.putLong(KEY_BUILD, b).putInt(KEY_COUNT, 0).putBoolean(KEY_STARTING, false).putBoolean(KEY_TOLD, false);
            e.commit();
        }
        if (p.getBoolean(KEY_STARTING, false)) {
            p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).commit();
        }
        p.edit().putBoolean(KEY_STARTING, true).commit();
        on = p.getInt(KEY_COUNT, 0) >= 2;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Watch) return;
        Thread.setDefaultUncaughtExceptionHandler(new Watch(app, previous));
    }

    /** TV Home is up and running: this start counts as good. */
    static void started(Context ctx) {
        Context app = ctx.getApplicationContext();
        prefs(app).edit().putBoolean(KEY_STARTING, false).apply();   // reached the screen
        // Running fine for half a minute: the crash count starts over.
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(TOKEN);
        new Handler(Looper.getMainLooper()).postAtTime(() -> {
            if (!on(app)) prefs(app).edit().putInt(KEY_COUNT, 0).apply();
        }, TOKEN, android.os.SystemClock.uptimeMillis() + 30_000);
    }

    private static final Object TOKEN = new Object();

    static boolean on(Context ctx) {
        if (on == null) on = prefs(ctx.getApplicationContext()).getInt(KEY_COUNT, 0) >= 2;
        return on;
    }

    /** Safe mode is on and the person hasn't been told yet this build. */
    static boolean shouldTell(Context ctx) {
        return on(ctx) && !prefs(ctx).getBoolean(KEY_TOLD, false);
    }

    static void told(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_TOLD, true).apply();
    }

    /** Effects back on (until the next crashes). */
    static void leave(Context ctx) {
        prefs(ctx).edit().putInt(KEY_COUNT, 0).apply();
        on = false;
    }

    static String lastProblem(Context ctx) {
        return prefs(ctx).getString(KEY_LAST, null);
    }

    private static final class Watch implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler next;

        Watch(Context app, Thread.UncaughtExceptionHandler next) {
            this.app = app;
            this.next = next;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
                if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
                for (StackTraceElement el : e.getStackTrace()) {
                    if (el.getClassName().startsWith("com.gathin")) {
                        sb.append(" @ ").append(el.getClassName().replace("com.gathin.tvhome.", ""))
                                .append(':').append(el.getLineNumber());
                        break;
                    }
                }
                SharedPreferences p = prefs(app);
                p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).putBoolean(KEY_STARTING, false)
                        .putString(KEY_LAST, "build " + build(app) + " · " + sb).commit();
            } catch (Throwable ignored) {
            }
            if (next != null) next.uncaughtException(t, e);
        }
    }
}
