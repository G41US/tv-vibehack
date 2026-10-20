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

    /**
     * After an update or at boot: turn the service on, and after an update bring TV Home back to the
     * front by itself. Android rebinds the Home button service a moment after the update, sometimes
     * before this broadcast and sometimes after, so this flags the wish (the service acts on it when it
     * connects) and also checks for the running service for a few seconds and asks it directly.
     */
    public static class Receiver extends BroadcastReceiver {
        private static final long[] RETRY_MS = {0, 800, 2000, 4000, 7000};

        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && !Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
            boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
            Context app = ctx.getApplicationContext();
            if (updated) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GO_HOME, true).commit();
                forceRebind(app);
            }
            ensureService(app);
            if (!updated) {
                Profiles.markPending(app);   // box started: ask who's watching
                return;
            }
            PendingResult pending = goAsync();
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            for (int i = 0; i < RETRY_MS.length; i++) {
                final boolean last = i == RETRY_MS.length - 1;
                h.postDelayed(() -> {
                    HomeKeyService s = HomeKeyService.live;
                    if (s != null && HomeKeyService.takeGoHomeFlag(app)) s.goHome();
                    if (last) pending.finish();
                }, RETRY_MS[i]);
            }
        }
    }

    /** With the settings permission: switch the service off and on so Android binds the new version now. */
    private static void forceRebind(Context ctx) {
        if (!canHeal(ctx)) return;
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.isEmpty() || cur.equals("null")) return;
            StringBuilder others = new StringBuilder();
            for (String part : cur.split(":")) {
                if (part.isEmpty() || part.startsWith(ctx.getPackageName() + "/")) continue;
                if (others.length() > 0) others.append(':');
                others.append(part);
            }
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    others.toString());
            // ensureService() puts it straight back.
        } catch (SecurityException ignored) {
        }
    }
}
