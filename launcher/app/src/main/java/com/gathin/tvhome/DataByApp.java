package com.gathin.tvhome;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data by app (Network page): what each app on the box used this month and today, from Android's own
 * network records, plus "Phones and laptops on your Wi-Fi" when the box is also the router. Needs usage
 * access, which "Set up on this TV" grants (or TV Home grants itself over the debugging connection).
 */
final class DataByApp {

    private DataByApp() {
    }

    static boolean allowed(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** One line: a name, where to get its icon, and bytes this month and today. */
    private static final class Use {
        String name;
        Object icon;
        long month, today;
    }

    static void show(Activity a, Runnable back) {
        GlassMenu.Progress p = GlassMenu.progress(a, a.getString(R.string.apps_data_title));
        p.set("");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = allowed(a);
            if (!ok && AdbSelf.ready(a)) {   // grant it ourselves, as the setup does
                try (AdbSelf adb = AdbSelf.connect(a, SelfSetup.PORT, 30_000,
                        () -> main.post(() -> p.set(a.getString(R.string.ss_allow))))) {
                    adb.shell("appops set " + a.getPackageName() + " GET_USAGE_STATS allow");
                    ok = allowed(a);
                } catch (Exception ignored) {
                }
            }
            final List<Use> uses = ok ? collect(a) : null;
            main.post(() -> {
                p.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (uses == null) {
                    GlassMenu.message(a, a.getString(R.string.apps_data_title), a.getString(R.string.apps_data_need),
                            new String[]{a.getString(R.string.ss_menu_self), a.getString(android.R.string.cancel)}, w -> {
                                if (w == 0) SelfSetup.show(a);
                            });
                } else if (uses.isEmpty()) {
                    GlassMenu.message(a, a.getString(R.string.apps_data_title), a.getString(R.string.apps_data_empty),
                            new String[]{a.getString(android.R.string.ok)}, w -> {
                                if (back != null) back.run();
                            });
                } else {
                    page(a, uses, back);
                }
            });
        }, "data-by-app").start();
    }

    private static void page(Activity a, List<Use> uses, Runnable back) {
        long month = 0, today = 0;
        for (Use u : uses) {
            month += u.month;
            today += u.today;
        }
        List<GlassMenu.Row> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(12, uses.size()); i++) {
            Use u = uses.get(i);
            rows.add(new GlassMenu.Row(u.icon, u.name, a.getString(R.string.apps_data_row,
                    Formatter.formatShortFileSize(a, u.month), Formatter.formatShortFileSize(a, u.today)), null));
        }
        GlassMenu.page(a, a.getString(R.string.tv_network), a.getString(R.string.apps_data_title),
                GlassMenu.hero(a, Formatter.formatShortFileSize(a, month), a.getString(R.string.apps_data_month),
                        a.getString(R.string.apps_data_today, Formatter.formatShortFileSize(a, today))),
                rows, back, 0);
    }

    /** This month's and today's use per app, biggest first. */
    private static List<Use> collect(Context c) {
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        long monthStart = cal.getTimeInMillis();

        Map<Integer, long[]> byUid = new HashMap<>();
        NetworkStatsManager nsm = (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
        // Mobile (the router's SIM), Wi-Fi and Ethernet; a type this box doesn't have is skipped.
        for (int type : new int[]{ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET}) {
            add(nsm, type, monthStart, now, byUid, 0);
            add(nsm, type, dayStart, now, byUid, 1);
        }

        PackageManager pm = c.getPackageManager();
        Map<String, Use> byName = new HashMap<>();
        for (Map.Entry<Integer, long[]> e : byUid.entrySet()) {
            int uid = e.getKey();
            String name;
            Object icon = R.drawable.ic_m_apps;
            if (uid == NetworkStats.Bucket.UID_TETHERING) {
                name = c.getString(R.string.apps_data_tether);
                icon = R.drawable.ic_wifi;
            } else if (uid == NetworkStats.Bucket.UID_REMOVED) {
                name = c.getString(R.string.apps_data_removed);
            } else if (uid < Process.FIRST_APPLICATION_UID) {
                name = c.getString(R.string.apps_data_system);
                icon = R.drawable.ic_settings;
            } else {
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs == null || pkgs.length == 0) {
                    name = c.getString(R.string.apps_data_removed);
                } else {
                    try {
                        name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)));
                        icon = pm.getApplicationIcon(pkgs[0]);
                    } catch (PackageManager.NameNotFoundException ex) {
                        name = pkgs[0];
                    }
                }
            }
            Use u = byName.get(name);
            if (u == null) {
                u = new Use();
                u.name = name;
                u.icon = icon;
                byName.put(name, u);
            }
            u.month += e.getValue()[0];
            u.today += e.getValue()[1];
        }
        List<Use> out = new ArrayList<>();
        for (Use u : byName.values()) if (u.month > 0) out.add(u);
        out.sort((x, y) -> Long.compare(y.month, x.month));
        return out;
    }

    private static void add(NetworkStatsManager nsm, int type, long from, long to, Map<Integer, long[]> into, int slot) {
        try (NetworkStats stats = nsm.querySummary(type, null, from, to)) {
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b);
                long[] v = into.get(b.getUid());
                if (v == null) into.put(b.getUid(), v = new long[2]);
                v[slot] += b.getRxBytes() + b.getTxBytes();
            }
        } catch (Exception ignored) {
            // no such network on this box, or no access
        }
    }
}
