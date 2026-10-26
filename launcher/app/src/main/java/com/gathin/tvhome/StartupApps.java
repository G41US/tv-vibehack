package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faster start-up (Updates & setup): apps the person picks are switched off while the box shuts down
 * or restarts, so Android has less to start before Home, and switched back on (and given their
 * start-up call where the box allows it) as soon as the TV Home animation has played. Done over the
 * same debugging connection as "Set up on this TV". If switching back on fails, it's retried every
 * time TV Home starts, and the page offers "Turn them all back on now".
 */
final class StartupApps {

    private static final String PREFS = "startup_apps", KEY_HELD = "held", KEY_PAUSED = "paused";
    /** Parts of Android and the box that must always start (remote, keyboard, Play services, chips). */
    private static final String[] NEVER = {"android", "com.android.", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.ext.", "com.google.android.tv.remote",
            "com.google.android.inputmethod", "com.google.android.tts", "com.droidlogic", "com.amlogic",
            "com.mediatek", "com.realtek", "com.rtk", "com.hisilicon", "com.mstar", "com.novatek"};
    private static final Pattern COMPONENT = Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+");

    private StartupApps() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> held(Context c) {
        return new TreeSet<>(prefs(c).getStringSet(KEY_HELD, new HashSet<>()));
    }

    static Set<String> paused(Context c) {
        return new TreeSet<>(prefs(c).getStringSet(KEY_PAUSED, new HashSet<>()));
    }

    private static AdbSelf connect(Context c, long waitMs) throws Exception {
        return AdbSelf.connect(c, SelfSetup.PORT, waitMs, () -> {
        });
    }

    // ---- the box shutting down / starting -------------------------------------------------------------

    /** The box is shutting down or restarting: switch the held-back apps off. Runs off the main thread. */
    static void hold(Context c) {
        Set<String> held = held(c);
        if (held.isEmpty() || !AdbSelf.reachable(SelfSetup.PORT)) return;
        Set<String> paused = paused(c);
        try (AdbSelf adb = connect(c, 3000)) {
            String off = adb.shell("pm list packages -d");   // already off by the person's choice: leave alone
            for (String pkg : held) {
                if (off.contains("package:" + pkg + "\n") || off.endsWith("package:" + pkg)) continue;
                if (adb.shell("pm disable-user --user 0 " + pkg + " 2>&1").contains("disabled")) {
                    paused.add(pkg);
                    prefs(c).edit().putStringSet(KEY_PAUSED, paused).commit();   // kept even if power goes mid-way
                }
            }
        } catch (Exception ignored) {
            // no time or no connection: they simply start as usual
        }
    }

    /** Home is up after the animation: switch everything paused back on. Retries for a few minutes. */
    static void release(Context c) {
        if (paused(c).isEmpty()) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            for (int i = 0; i < 12 && !paused(app).isEmpty(); i++) {
                if (i > 0) SystemClock.sleep(10_000);
                if (!AdbSelf.reachable(SelfSetup.PORT)) continue;
                try (AdbSelf adb = connect(app, 30_000)) {
                    turnOn(app, adb);
                } catch (Exception ignored) {
                }
            }
            if (paused(app).isEmpty()) BootTrace.mark(app, "held-back apps back on");
        }, "startup-apps").start();
    }

    private static void turnOn(Context c, AdbSelf adb) throws Exception {
        Set<String> paused = paused(c);
        for (String pkg : new ArrayList<>(paused)) {
            String out = adb.shell("pm enable --user 0 " + pkg + " 2>&1");
            if (out.contains("enabled") || out.contains("Unknown package")) {
                paused.remove(pkg);
                prefs(c).edit().putStringSet(KEY_PAUSED, paused).commit();
                // Their own start-up step; refused on boxes that don't allow it, which is fine.
                adb.shell("am broadcast -a android.intent.action.BOOT_COMPLETED -p " + pkg + " >/dev/null 2>&1");
            }
        }
    }

    // ---- the page -----------------------------------------------------------------------------------

    private static final class App {
        String pkg, name;
        Object icon;
    }

    static void show(Activity a, Runnable back) {
        GlassMenu.Progress p = GlassMenu.progress(a, a.getString(R.string.fs_title));
        p.set(a.getString(R.string.fs_checking));
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<App> apps = null;
            if (AdbSelf.reachable(SelfSetup.PORT)) {
                try (AdbSelf adb = AdbSelf.connect(a, SelfSetup.PORT, 30_000,
                        () -> main.post(() -> p.set(a.getString(R.string.ss_allow))))) {
                    if (!paused(a).isEmpty()) turnOn(a, adb);
                    apps = list(a, adb);
                } catch (Exception ignored) {
                }
            }
            final List<App> found = apps;
            main.post(() -> {
                p.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (found == null) {
                    GlassMenu.message(a, a.getString(R.string.fs_title), a.getString(R.string.apps_data_need),
                            new String[]{a.getString(R.string.ss_menu_self), a.getString(android.R.string.cancel)}, w -> {
                                if (w == 0) SelfSetup.show(a);
                            });
                } else if (found.isEmpty()) {
                    GlassMenu.message(a, a.getString(R.string.fs_title), a.getString(R.string.fs_none),
                            new String[]{a.getString(android.R.string.ok)}, w -> {
                                if (back != null) back.run();
                            });
                } else {
                    page(a, found, back, 0);
                }
            });
        }, "startup-apps-list").start();
    }

    /** Apps with a start-up step (they run every time the box starts), minus parts of the system. */
    private static List<App> list(Context c, AdbSelf adb) throws Exception {
        Set<String> pkgs = new TreeSet<>(held(c));
        for (String action : new String[]{"android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED"}) {
            Matcher m = COMPONENT.matcher(adb.shell("cmd package query-receivers --brief -a " + action));
            while (m.find()) pkgs.add(m.group(1));
        }
        String keep = String.valueOf(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS))
                + ":" + Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        PackageManager pm = c.getPackageManager();
        List<App> out = new ArrayList<>();
        for (String pkg : pkgs) {
            if (pkg.equals(c.getPackageName()) || keep.contains(pkg + "/") || never(pkg)) continue;
            App app = new App();
            app.pkg = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
                if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;   // Android won't switch these off
                app.name = String.valueOf(pm.getApplicationLabel(ai));
                app.icon = pm.getApplicationIcon(ai);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            out.add(app);
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    private static boolean never(String pkg) {
        for (String n : NEVER) if (n.endsWith(".") ? pkg.startsWith(n) : pkg.equals(n) || pkg.startsWith(n + ".")) return true;
        return false;
    }

    private static void page(Activity a, List<App> apps, Runnable back, int focus) {
        Set<String> held = held(a);
        List<GlassMenu.Row> rows = new ArrayList<>();
        for (App app : apps) {
            boolean on = held.contains(app.pkg);
            rows.add(new GlassMenu.Row(app.icon, app.name, a.getString(on ? R.string.fs_held : R.string.fs_starts), () -> {
                Set<String> h = held(a);
                if (!h.remove(app.pkg)) h.add(app.pkg);
                prefs(a).edit().putStringSet(KEY_HELD, h).apply();
                page(a, apps, back, apps.indexOf(app));
            }));
        }
        GlassMenu.page(a, a.getString(R.string.hs_setup), a.getString(R.string.fs_title),
                GlassMenu.hero(a, a.getString(R.string.fs_count, held.size()), a.getString(R.string.fs_explain)),
                rows, back, focus);
    }
}
