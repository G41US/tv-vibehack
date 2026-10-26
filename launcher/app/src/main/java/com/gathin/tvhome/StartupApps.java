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
 * time TV Home starts.
 *
 * "Pick for me" (on by default, on every TV) holds back what is safe to: apps that start with the box
 * but aren't a home screen, keyboard, accessibility service, VPN, TV input, screensaver, device admin,
 * notification reader, Chromecast or the voice assistant. Nothing is held back on a box until TV Home
 * has seen, after a restart, that it can reach the debugging connection to switch apps back on.
 */
final class StartupApps {

    private static final String PREFS = "startup_apps", KEY_HELD = "held", KEY_PAUSED = "paused", KEY_AUTO = "auto",
            KEY_PROVEN = "proven";
    /** Held back by "Pick for me" never: Chromecast built-in and the voice assistant should work at once. */
    private static final String[] KEEP_RUNNING = {"com.google.android.apps.mediashell", "com.google.android.katniss",
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher"};
    private static volatile boolean releasing;
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
        // Only where switching back on has been seen to work after a restart: never strand an app.
        if (held.isEmpty() || !prefs(c).getBoolean(KEY_PROVEN, false) || !AdbSelf.reachable(SelfSetup.PORT)) return;
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

    /** For TV check. */
    static String state(Context c) {
        return (auto(c) ? "picked for me, " : "") + held(c).size() + " held back, " + paused(c).size() + " still off"
                + (prefs(c).getBoolean(KEY_PROVEN, false) ? "" : ", waiting for a restart");
    }

    static boolean auto(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    /**
     * Home is up after the animation: switch everything paused back on, note that the debugging
     * connection works after a restart, and with "Pick for me" choose what to hold back next time.
     * Retries for two minutes if the connection isn't up yet.
     */
    static void release(Context c) {
        if (releasing) return;
        boolean boot = SystemClock.elapsedRealtime() < 300_000;
        if (paused(c).isEmpty() && !boot) return;   // later opens of Home: nothing to do
        releasing = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean reached = false;
            for (int i = 0; i < 12 && !reached; i++) {
                if (i > 0) SystemClock.sleep(10_000);
                if (!AdbSelf.reachable(SelfSetup.PORT)) continue;
                try (AdbSelf adb = connect(app, 30_000)) {
                    if (boot) androidTimeline(app, adb);
                    turnOn(app, adb);
                    reached = paused(app).isEmpty();
                    if (reached && auto(app)) {
                        Set<String> picks = new TreeSet<>();
                        for (App a : list(app, adb)) if (a.autoPick) picks.add(a.pkg);
                        prefs(app).edit().putStringSet(KEY_HELD, picks).apply();
                    }
                } catch (Exception ignored) {
                }
            }
            if (boot) prefs(app).edit().putBoolean(KEY_PROVEN, reached).commit();
            if (reached) BootTrace.mark(app, "held-back apps back on");
            releasing = false;
        }, "startup-apps").start();
    }

    private static final String[][] ANDROID_STEPS = {
            {"boot_progress_start", "Android starting"}, {"boot_progress_system_run", "Android system"},
            {"boot_progress_pms_ready", "apps scanned"}, {"boot_progress_ams_ready", "Android ready"},
            {"FallbackHome", "Android's waiting screen"}, {"uc_finish_user_unlocked", "storage unlocked"},
            {"boot_progress_enable_screen", "screen handed over"}, {"sf_stop_bootanim", "boot logo stopped"}};

    /** Android's own start-up steps from its event log, into "Last start-up" (TV check). */
    private static void androidTimeline(Context c, AdbSelf adb) {
        try {
            String out = adb.shell("logcat -b events -d -v monotonic 2>/dev/null | grep -E "
                    + "'boot_progress_|sf_stop_bootanim|uc_finish_user_unlocked|am_create_activity.*FallbackHome' | head -40");
            Pattern at = Pattern.compile("^\\s*(\\d+\\.\\d+)");
            for (String line : out.split("\n")) {
                Matcher m = at.matcher(line);
                if (!m.find()) continue;
                long ms = (long) (Double.parseDouble(m.group(1)) * 1000);
                for (String[] step : ANDROID_STEPS) if (line.contains(step[0])) BootTrace.mark(c, step[1], ms);
            }
        } catch (Exception ignored) {
        }
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
        boolean autoPick;
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
        // What "Pick for me" leaves alone: home screens, admins, notification readers, the screensaver.
        Set<String> busy = new HashSet<>(java.util.Arrays.asList(KEEP_RUNNING));
        android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            busy.add(r.activityInfo.packageName);
        }
        android.app.admin.DevicePolicyManager dpm = c.getSystemService(android.app.admin.DevicePolicyManager.class);
        if (dpm != null && dpm.getActiveAdmins() != null) {
            for (android.content.ComponentName cn : dpm.getActiveAdmins()) busy.add(cn.getPackageName());
        }
        String inUse = keep + ":" + Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners")
                + ":" + Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
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
                app.autoPick = !busy.contains(pkg) && !inUse.contains(pkg + "/") && !runsSomethingLive(pm, pkg);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            out.add(app);
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** A VPN, a TV input (HDMI, tuner) or a screensaver: things that should be there from the start. */
    private static boolean runsSomethingLive(PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg,
                    PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (pi.services == null) return false;
            for (android.content.pm.ServiceInfo si : pi.services) {
                String perm = si.permission;
                if ("android.permission.BIND_VPN_SERVICE".equals(perm) || "android.permission.BIND_TV_INPUT".equals(perm)
                        || "android.permission.BIND_DREAM_SERVICE".equals(perm)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean never(String pkg) {
        for (String n : NEVER) if (n.endsWith(".") ? pkg.startsWith(n) : pkg.equals(n) || pkg.startsWith(n + ".")) return true;
        return false;
    }

    private static void page(Activity a, List<App> apps, Runnable back, int focus) {
        boolean auto = auto(a);
        if (auto) {   // show what "Pick for me" holds back now
            Set<String> picks = new TreeSet<>();
            for (App app : apps) if (app.autoPick) picks.add(app.pkg);
            prefs(a).edit().putStringSet(KEY_HELD, picks).apply();
        }
        Set<String> held = held(a);
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, a.getString(R.string.fs_auto),
                a.getString(auto ? R.string.tv_on : R.string.tv_off), () -> {
            prefs(a).edit().putBoolean(KEY_AUTO, !auto).apply();
            page(a, apps, back, 0);
        }));
        for (App app : apps) {
            boolean on = held.contains(app.pkg);
            rows.add(new GlassMenu.Row(app.icon, app.name, a.getString(on ? R.string.fs_held : R.string.fs_starts), () -> {
                Set<String> h = held(a);   // choosing yourself turns "Pick for me" off
                if (!h.remove(app.pkg)) h.add(app.pkg);
                prefs(a).edit().putStringSet(KEY_HELD, h).putBoolean(KEY_AUTO, false).apply();
                page(a, apps, back, apps.indexOf(app) + 1);
            }));
        }
        GlassMenu.page(a, a.getString(R.string.hs_setup), a.getString(R.string.fs_title),
                GlassMenu.hero(a, a.getString(R.string.fs_count, held.size()), a.getString(R.string.fs_explain),
                        prefs(a).getBoolean(KEY_PROVEN, false) ? null : a.getString(R.string.fs_pending)),
                rows, back, focus);
    }
}
