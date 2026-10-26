package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * When things happened after the box last started, in seconds since power-on ("box ready 21.4s,
 * TV Home started 22.0s, home on screen 38.9s"), for TV check, so a slow start can be traced on any box.
 */
final class BootTrace {

    private static final String PREFS = "boottrace";
    private static final long WINDOW_MS = 180_000;   // only the first three minutes count as start-up

    private BootTrace() {
    }

    static void mark(Context c, String what) {
        mark(c, what, SystemClock.elapsedRealtime());
    }

    static synchronized void mark(Context c, String what, long at) {
        if (SystemClock.elapsedRealtime() > WINDOW_MS) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime();
        String text = now < p.getLong("at", Long.MAX_VALUE) ? "" : p.getString("text", "");   // a new start: begin again
        if (text.contains(what + " ")) return;   // first time only
        text += (text.isEmpty() ? "" : ", ") + what + String.format(java.util.Locale.US, " %.1fs", at / 1000f);
        p.edit().putString("text", text).putLong("at", now).commit();   // at once: a process that dies still leaves its marks
    }

    private static boolean watching;

    /**
     * While the box is starting, notices when TV Home's main thread is held up for more than a second
     * and records where ("busy 14.8s in TvInputManager.getTvInputList (MainActivity.updateInputsButton)").
     */
    static synchronized void watch(Context c) {
        if (watching || SystemClock.elapsedRealtime() > WINDOW_MS) return;
        watching = true;
        Context app = c.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Thread mainThread = android.os.Looper.getMainLooper().getThread();
        new Thread(() -> {
            int found = 0;
            while (SystemClock.elapsedRealtime() < WINDOW_MS && found < 4) {
                java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong();
                long sent = SystemClock.elapsedRealtime();
                main.post(() -> done.set(SystemClock.elapsedRealtime()));
                SystemClock.sleep(1000);
                if (done.get() != 0) continue;
                String where = where(mainThread.getStackTrace());
                while (done.get() == 0 && SystemClock.elapsedRealtime() < WINDOW_MS) SystemClock.sleep(100);
                long end = done.get() != 0 ? done.get() : SystemClock.elapsedRealtime();
                mark(app, String.format(java.util.Locale.US, "busy %.1fs in %s from", (end - sent) / 1000f, where), sent);
                found++;
            }
        }, "boot-watch").start();
    }

    /** The call it's stuck in, and the TV Home code that made it. */
    private static String where(StackTraceElement[] st) {
        if (st.length == 0) return "?";
        String top = st[0].getClassName().replaceAll(".*\\.", "") + "." + st[0].getMethodName();
        for (StackTraceElement e : st) {
            if (e.getClassName().startsWith("com.gathin")) {
                String ours = e.getClassName().replaceAll(".*\\.", "") + "." + e.getMethodName();
                return ours.equals(top) ? top : top + " (" + ours + ")";
            }
        }
        return top;
    }

    static String text(Context c) {
        String t = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("text", "");
        return t.isEmpty() ? "not recorded yet" : t;
    }
}
