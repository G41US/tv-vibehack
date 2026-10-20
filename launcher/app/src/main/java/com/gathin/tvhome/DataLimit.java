package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A monthly data limit (set in the Network panel). When this month's count (DataUsage) passes 80% and
 * then 100%, Home says so once each, so a data plan doesn't run out by surprise mid-film. The count is
 * the box's own, so on a box that isn't the router it covers what the TV uses, not the whole house.
 */
final class DataLimit {

    static final String PREFS = "data_limit", KEY_GB = "gb", KEY_WARNED = "warned";
    private static final int[] OPTIONS_GB = {0, 10, 25, 50, 100, 200, 500};
    private static final long GB = 1L << 30;

    private DataLimit() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The limit in GB; 0 = none. */
    static int gb(Context ctx) {
        try {
            return Integer.parseInt(prefs(ctx).getString(KEY_GB, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "of 50 GB (37%)" for the Network panel, or null with no limit. */
    static String panelLine(Context ctx, long monthBytes) {
        int gb = gb(ctx);
        if (gb <= 0) return null;
        long pct = Math.round(monthBytes * 100.0 / (gb * GB));
        return ctx.getString(R.string.limit_of, gb, (int) pct);
    }

    static void showSettings(Activity act, Runnable after) {
        int cur = gb(act);
        String[] labels = new String[OPTIONS_GB.length];
        for (int i = 0; i < OPTIONS_GB.length; i++) {
            String name = OPTIONS_GB[i] == 0 ? act.getString(R.string.limit_none)
                    : act.getString(R.string.limit_gb, OPTIONS_GB[i]);
            labels[i] = (OPTIONS_GB[i] == cur ? "✓  " : "     ") + name;
        }
        GlassMenu.list(act, act.getString(R.string.net_title), act.getString(R.string.limit_title), labels, which -> {
            prefs(act).edit().putString(KEY_GB, String.valueOf(OPTIONS_GB[which])).remove(KEY_WARNED).apply();
            if (after != null) after.run();
        });
    }

    /** On Home: one warning at 80% and one at 100% each month. */
    static void maybeWarn(Activity act) {
        int gb = gb(act);
        if (gb <= 0) return;
        long used = DataUsage.read(act).month;
        int pct = (int) (used * 100 / (gb * GB));
        int level = pct >= 100 ? 100 : pct >= 80 ? 80 : 0;
        if (level == 0) return;
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        String warned = prefs(act).getString(KEY_WARNED, "");
        if (warned.equals(month + ":" + level) || warned.equals(month + ":100")) return;
        prefs(act).edit().putString(KEY_WARNED, month + ":" + level).apply();
        GlassMenu.message(act, act.getString(level >= 100 ? R.string.limit_reached_title : R.string.limit_near_title),
                act.getString(R.string.limit_message, DataUsage.format(used), gb, pct),
                new String[]{act.getString(android.R.string.ok)}, null);
    }
}
