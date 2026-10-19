package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. It also takes KEYCODE_HOME itself, for
 * firmware whose own key handler opens the stock launcher directly whatever the default home is
 * (Airtel Smart Router, com.nes.tvglobalkeyhandler -> com.nes.hometv). Every other key passes
 * through untouched.
 *
 * Takeover: some firmware starts its own home screen itself (at boot, or from its key handler),
 * whatever the default home is. Packages listed in config.xml's takeover_packages are replaced by
 * this launcher the moment one of their windows comes to the front.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_ALL_APPS && code != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            goHome();
        }
        return true;
    }

    private String[] takeover = new String[0];
    private long lastTakeover;

    @Override
    protected void onServiceConnected() {
        takeover = getResources().getStringArray(R.array.takeover_packages);
        // Just updated (SelfHeal.Receiver): come back to this home screen instead of the stock one.
        android.content.SharedPreferences p = getSharedPreferences(SelfHeal.PREFS, MODE_PRIVATE);
        if (p.getBoolean(SelfHeal.KEY_GO_HOME, false)) {
            p.edit().putBoolean(SelfHeal.KEY_GO_HOME, false).apply();
            goHome();
        }
    }

    private long lastSample;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long t = SystemClock.elapsedRealtime();
        if (t - lastSample > 60_000) {   // keeps the data counts going while other apps are open
            lastSample = t;
            DataUsage.sample(this);
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        for (String p : takeover) {
            if (p.contentEquals(pkg)) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastTakeover < 1000) return;   // one hop per second, never a loop
                lastTakeover = now;
                goHome();
                return;
            }
        }
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
    }

    @Override
    public void onInterrupt() {
    }
}
