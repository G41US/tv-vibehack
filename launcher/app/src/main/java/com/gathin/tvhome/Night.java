package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

import java.util.Calendar;

/**
 * Night mode (⚙ Settings > Picture & sound): from the chosen hour until 6 am the home screen dims and
 * turns warmer, navigation sounds go quieter, and the volume can't go above the night limit, so an ad
 * or a loud app doesn't blast at night. HomeKeyService enforces the limit whatever app is open.
 */
final class Night {

    static final String PREFS = "night", KEY_START = "start", KEY_CAP = "cap";
    static final int[] STARTS = {0, 21, 22, 23};      // 0 = off
    static final int[] CAPS = {40, 30, 50, 0};        // percent; 0 = no limit
    static final int END = 6;

    private Night() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int start(Context ctx) {
        return prefs(ctx).getInt(KEY_START, 0);
    }

    static int cap(Context ctx) {
        return prefs(ctx).getInt(KEY_CAP, 40);
    }

    static void setStart(Context ctx, int h) {
        prefs(ctx).edit().putInt(KEY_START, h).apply();
    }

    static void setCap(Context ctx, int pct) {
        prefs(ctx).edit().putInt(KEY_CAP, pct).apply();
    }

    static boolean active(Context ctx) {
        int s = start(ctx);
        if (s <= 0) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= s || h < END;
    }

    /** At night, brings the volume down to the limit if it's above it. True if it changed it. */
    static boolean enforceCap(Context ctx) {
        if (!active(ctx)) return false;
        int pct = cap(ctx);
        if (pct <= 0) return false;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || am.isVolumeFixed()) return false;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int limit = Math.max(1, Math.round(max * pct / 100f));
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= limit) return false;
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, 0);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
