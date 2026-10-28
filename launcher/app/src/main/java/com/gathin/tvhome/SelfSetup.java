package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * "Set up on this TV": everything the phone setup script does, done by TV Home itself through the
 * TV's own debugging service (AdbSelf). The person turns on Developer options and USB debugging with
 * the remote, presses one button, and allows the "Allow USB debugging?" question once.
 */
final class SelfSetup {

    static final int PORT = 5555;

    private SelfSetup() {
    }

    static void show(Activity act) {
        show(act, 0);
    }

    private static void show(Activity act, int focus) {
        // Checking the debugging port is network work: off the main thread, then the page.
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ready = AdbSelf.ready(act);
            main.post(() -> {
                if (!act.isFinishing() && !act.isDestroyed()) build(act, focus, ready);
            });
        }, "adb-check").start();
    }

    private static void build(Activity act, int focus, boolean ready) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_m_setup, act.getString(R.string.ss_step_dev),
                act.getString(R.string.ss_step_dev_value), () -> open(act, Settings.ACTION_DEVICE_INFO_SETTINGS))
                .external());
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, act.getString(R.string.ss_step_debug),
                act.getString(ready ? R.string.ss_debug_on : R.string.ss_debug_off),
                () -> open(act, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)).external());
        rows.add(new GlassMenu.Row(R.drawable.ic_s_play, act.getString(R.string.ss_step_go),
                act.getString(ready ? R.string.ss_go_ready : R.string.ss_go_wait), () -> run(act)));
        LinearLayout hero = GlassMenu.hero(act, act.getString(R.string.ss_hero), act.getString(R.string.ss_hero_line));
        GlassMenu.page(act, act.getString(R.string.menu_setup), act.getString(R.string.ss_title), hero, rows, null,
                focus > 0 ? focus : (ready ? 2 : 0));
    }

    private static void open(Activity act, String action) {
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(act.getPackageManager()) == null) {
            i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            act.startActivity(i);
        } catch (Exception e) {
            GlassMenu.message(act, act.getString(R.string.ss_title), act.getString(R.string.ss_manual),
                    new String[]{act.getString(android.R.string.ok)}, null);
        }
    }

    /** Connects to the TV's debugging service and runs the setup commands. */
    static void run(Activity act) {
        GlassMenu.Progress p = GlassMenu.progress(act, act.getString(R.string.ss_connecting));
        p.set("");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            if (!AdbSelf.ready(act)) {
                error = act.getString(R.string.ss_err_off);
            } else {
                try (AdbSelf adb = AdbSelf.connect(act, PORT, 120_000,
                        () -> main.post(() -> p.set(act.getString(R.string.ss_allow))))) {
                    main.post(() -> p.set(act.getString(R.string.ss_working)));
                    commands(act, adb);
                } catch (java.net.SocketTimeoutException e) {
                    error = act.getString(R.string.ss_err_timeout);
                } catch (Exception e) {
                    error = act.getString(R.string.ss_err_other, String.valueOf(e.getMessage()));
                }
            }
            final String err = error;
            main.post(() -> {
                p.dismiss();
                if (act.isFinishing() || act.isDestroyed()) return;
                if (err != null) {
                    GlassMenu.message(act, act.getString(R.string.ss_title), err,
                            new String[]{act.getString(android.R.string.ok)}, null);
                    return;
                }
                SelfHeal.ensureService(act);
                TvCheck.show(act, true);
            });
        }, "self-setup").start();
    }

    private static void commands(Activity act, AdbSelf adb) throws Exception {
        String pkg = act.getPackageName();
        String svc = pkg + "/" + HomeKeyService.class.getName();
        adb.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
        // Only component-name characters go back into the command (no quotes or shell symbols).
        String cur = adb.shell("settings get secure enabled_accessibility_services").trim().replaceAll("[^A-Za-z0-9._/:$]", "");
        if (!cur.contains(pkg + "/")) {
            String next = cur.isEmpty() || "null".equals(cur) ? svc : cur + ":" + svc;
            adb.shell("settings put secure enabled_accessibility_services '" + next + "'");
        }
        adb.shell("settings put secure accessibility_enabled 1");
        adb.shell("appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow");
        if (Build.VERSION.SDK_INT >= 31) adb.shell("pm grant " + pkg + " " + Manifest.permission.BLUETOOTH_CONNECT);
        adb.shell("cmd notification allow_listener " + new ComponentName(act, MediaListener.class).flattenToString());
        adb.shell("appops set " + pkg + " GET_USAGE_STATS allow");   // data by app
        adb.shell("pm grant " + pkg + " " + BackupStore.readPermission());
        adb.shell("cmd package set-home-activity --user 0 " + pkg + "/.MainActivity");
    }
}
