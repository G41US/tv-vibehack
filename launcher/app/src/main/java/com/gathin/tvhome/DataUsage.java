package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Data used today and this month, counted from the box's own traffic counters. Android resets those
 * counters at every boot, so each sample adds what changed since the previous one into a day and a
 * month bucket. Mobile (cellular) traffic is counted separately: on a box that is also the router,
 * that is the household's internet use; when there is none, all traffic through the box is shown.
 * Sampled by HomeKeyService (at most once a minute) and whenever Home is on screen.
 */
final class DataUsage {

    private static final String PREFS = "data_usage";

    private DataUsage() {
    }

    static final class Totals {
        final long today, month;
        final boolean mobile;

        Totals(long today, long month, boolean mobile) {
            this.today = today;
            this.month = month;
            this.mobile = mobile;
        }
    }

    private static long nonNegative(long v) {
        return v < 0 ? 0 : v;
    }

    static synchronized void sample(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long mob = nonNegative(TrafficStats.getMobileRxBytes()) + nonNegative(TrafficStats.getMobileTxBytes());
        long all = nonNegative(TrafficStats.getTotalRxBytes()) + nonNegative(TrafficStats.getTotalTxBytes());
        Date now = new Date();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
        String month = day.substring(0, 7);
        SharedPreferences.Editor e = p.edit();
        if (!month.equals(p.getString("month", ""))) {
            e.putString("month", month).putLong("m_mob", 0).putLong("m_all", 0);
        }
        if (!day.equals(p.getString("day", ""))) {
            e.putString("day", day).putLong("d_mob", 0).putLong("d_all", 0);
        }
        e.apply();
        if (p.contains("last_all")) {
            long lastMob = p.getLong("last_mob", 0), lastAll = p.getLong("last_all", 0);
            // Counters below the last sample mean the box restarted: everything since boot is new.
            long dMob = mob >= lastMob ? mob - lastMob : mob;
            long dAll = all >= lastAll ? all - lastAll : all;
            p.edit().putLong("m_mob", p.getLong("m_mob", 0) + dMob)
                    .putLong("m_all", p.getLong("m_all", 0) + dAll)
                    .putLong("d_mob", p.getLong("d_mob", 0) + dMob)
                    .putLong("d_all", p.getLong("d_all", 0) + dAll)
                    .apply();
        } else {
            p.edit().putLong("since", now.getTime()).apply();
        }
        p.edit().putLong("last_mob", mob).putLong("last_all", all).apply();
    }

    static Totals read(Context ctx) {
        sample(ctx);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean mobile = p.getLong("m_mob", 0) > 0;
        return mobile
                ? new Totals(p.getLong("d_mob", 0), p.getLong("m_mob", 0), true)
                : new Totals(p.getLong("d_all", 0), p.getLong("m_all", 0), false);
    }

    /** When counting started (first sample), so the panel can say "since 19 Oct" in the first month. */
    static long since(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("since", 0);
    }

    static String format(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }
}
