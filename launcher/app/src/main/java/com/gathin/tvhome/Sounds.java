package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Window;

/**
 * Navigation sounds (⚙ Settings > Picture & sound): a soft tick when focus moves and a gentle pop on
 * OK, like a console menu. Quieter during Night mode. On by default; one switch turns them off.
 */
final class Sounds {

    static final String PREFS = "look", KEY = "nav_sounds";

    private static SoundPool pool;
    private static int move, select;
    private static long lastMove;

    private Sounds() {
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    static void init(Context ctx) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        Context app = ctx.getApplicationContext();
        move = pool.load(app, R.raw.nav_move, 1);
        select = pool.load(app, R.raw.nav_select, 1);
    }

    private static float volume(Context ctx) {
        return Night.active(ctx) ? 0.25f : 0.55f;
    }

    static void move(Context ctx) {
        if (pool == null || !enabled(ctx)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastMove < 45) return;   // holding a direction: no machine-gun ticking
        lastMove = now;
        float v = volume(ctx);
        pool.play(move, v, v, 1, 0, 1f);
    }

    static void select(Context ctx) {
        if (pool == null || !enabled(ctx)) return;
        float v = volume(ctx);
        pool.play(select, v, v, 1, 0, 1f);
    }

    /** OK = select, Left/Right/Up/Down = move: for keys reaching an activity or a dialog. */
    static void onKey(Context ctx, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) select(ctx);
    }

    /** Ticks when focus moves inside a dialog, a pop on OK. */
    static void attach(Dialog d) {
        Context ctx = d.getContext();
        d.setOnKeyListener((dialog, keyCode, e) -> {
            onKey(ctx, e);
            return false;
        });
        Window w = d.getWindow();
        if (w != null) {
            w.getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
                if (o != null && n != null) move(ctx);
            });
        }
    }
}
