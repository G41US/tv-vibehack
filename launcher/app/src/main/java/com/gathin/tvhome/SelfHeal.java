package com.gathin.tvhome;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Keeps HomeKeyService switched on without adb. Android turns an app's accessibility service off
 * whenever the app is reinstalled or updated, which on firmware like the Airtel Smart Router brings
 * the stock home screen back. With WRITE_SECURE_SETTINGS granted once over adb:
 *
 *   adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
 *
 * the launcher switches the service back on itself: after every update (MY_PACKAGE_REPLACED), at
 * boot, and whenever the home screen comes up. Other enabled services are kept. Without the grant
 * this does nothing.
 */
public final class SelfHeal {

    static final String PREFS = "heal", KEY_GO_HOME = "go_home_once";

    private SelfHeal() {
    }

    static boolean canHeal(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Switches HomeKeyService on if it is off. Returns true when it is on afterwards. */
    static boolean ensureService(Context ctx) {
        String mine = ctx.getPackageName() + "/" + HomeKeyService.class.getName();
        String shortName = ctx.getPackageName() + "/.HomeKeyService";
        String cur = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = cur != null && (cur.contains(mine) || cur.contains(shortName));
        if (on) return true;
        if (!canHeal(ctx)) return false;
        try {
            String next = (cur == null || cur.isEmpty() || cur.equals("null")) ? mine : cur + ":" + mine;
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** After an update or at boot: turn the service on, and let it open this home screen once. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && !Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GO_HOME, true).apply();
            }
            ensureService(ctx);
        }
    }
}
