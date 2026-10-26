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
        p.edit().putString("text", text).putLong("at", now).apply();
    }

    static String text(Context c) {
        String t = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("text", "");
        return t.isEmpty() ? "not recorded yet" : t;
    }
}
