package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * The ⏻ button at the end of the top bar: Sleep, Restart, Shut down in a small glass menu.
 *
 * Sleep is Android's own standby (the TV follows over HDMI). Apps may not restart or switch off the
 * box themselves, so Restart and Shut down go through the TV's own debugging service (AdbSelf, as set
 * up by "Set up on this TV"); where that's off, Android's power menu opens instead.
 */
final class PowerMenu {

    private PowerMenu() {
    }

    static void show(Activity act, View anchor) {
        GlassMenu.dropdown(act, anchor,
                new int[]{R.drawable.ic_pw_sleep, R.drawable.ic_pw_restart, R.drawable.ic_power},
                new CharSequence[]{act.getString(R.string.pw_sleep), act.getString(R.string.pw_restart),
                        act.getString(R.string.pw_off)},
                which -> {
                    if (which == 0) StillWatching.turnOff(act);
                    else if (which == 1) confirm(act, false);
                    else confirm(act, true);
                });
    }

    private static void confirm(Activity act, boolean off) {
        String msg = !off ? act.getString(R.string.pw_restart_q)
                : StillWatching.isRouterBox(act) ? act.getString(R.string.pw_off_router)
                : act.getString(R.string.pw_off_q);
        GlassMenu.message(act, act.getString(off ? R.string.pw_off : R.string.pw_restart), msg,
                new String[]{act.getString(off ? R.string.pw_off : R.string.pw_restart), act.getString(android.R.string.cancel)},
                w -> {
                    if (w == 0) run(act, off);
                });
    }

    private static void run(Activity act, boolean off) {
        GlassMenu.Progress p = GlassMenu.progress(act, act.getString(off ? R.string.pw_off_now : R.string.pw_restart_now));
        p.set("");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean done = false;
            if (AdbSelf.reachable(SelfSetup.PORT)) {
                try (AdbSelf adb = AdbSelf.connect(act, SelfSetup.PORT, 30_000,
                        () -> main.post(() -> p.set(act.getString(R.string.ss_allow))))) {
                    adb.shell(off ? "svc power shutdown 2>/dev/null || reboot -p" : "svc power reboot 2>/dev/null || reboot");
                    done = true;
                } catch (Exception ignored) {
                }
            }
            final boolean ok = done;
            main.post(() -> {
                p.dismiss();
                if (ok) return;   // on its way down
                // No debugging connection: Android's own power menu, which has these too.
                HomeKeyService s = HomeKeyService.live;
                if (s != null && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) return;
                GlassMenu.message(act, act.getString(off ? R.string.pw_off : R.string.pw_restart),
                        act.getString(R.string.pw_need_setup), new String[]{act.getString(R.string.ss_menu_self),
                                act.getString(android.R.string.cancel)}, w -> {
                            if (w == 0) SelfSetup.show(act);
                        });
            });
        }, "power").start();
    }
}
