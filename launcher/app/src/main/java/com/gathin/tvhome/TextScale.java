package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;

/**
 * "Text size" (⚙ Settings > Picture & sound): makes all of TV Home's text larger by raising the font
 * scale of TV Home's own screens, on top of whatever the box itself is set to. Android's screens are
 * not affected.
 */
final class TextScale {

    static final String PREFS = "look", KEY = "text_scale";
    static final float[] LEVELS = {1f, 1.15f, 1.3f};
    static final int[] NAMES = {R.string.tv_text_normal, R.string.tv_text_large, R.string.tv_text_xlarge};

    /** The level in use on the screen that's showing (for sizes laid out in code). */
    static float current = 1f;

    private TextScale() {
    }

    static int level(Context ctx) {
        int l = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0);
        return l >= 0 && l < LEVELS.length ? l : 0;
    }

    static float factor(Context ctx) {
        return LEVELS[level(ctx)];
    }

    static void setLevel(Context ctx, int level) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, level).apply();
    }

    /** For an activity's attachBaseContext: the same context with the larger text applied. */
    static Context wrap(Context base) {
        float k = factor(base);
        current = k;
        if (k == 1f) return base;
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.fontScale = conf.fontScale * k;
        return base.createConfigurationContext(conf);
    }
}
