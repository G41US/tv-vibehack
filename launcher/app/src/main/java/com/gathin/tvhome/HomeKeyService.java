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

    /** The running service, when Android has it bound (so SelfHeal can send Home to TV Home after an update). */
    static volatile HomeKeyService live;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        lastInput = SystemClock.elapsedRealtime();   // someone's there (Still watching?)
        if (volume != null && volume.onKey(event)) return true;   // TV Home's volume display
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return onBack(event);
        if (code != KeyEvent.KEYCODE_ALL_APPS && code != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            goHome();
        }
        return true;
    }

    /*
     * Hold Back for the Quick panel. Android 12 keeps Home to itself (it never reaches accessibility
     * services), so Back it is: TV Home takes every Back press, and a short one is sent on as Android's
     * own Back when the button comes up.
     */
    private static final long HOLD_MS = 550;
    private boolean backHeld;
    /** Until then, Back presses are our own re-sent Back (if Android passes them through here): let them go. */
    private long passBackUntil;
    private final Runnable holdBack = () -> {
        backHeld = true;
        QuickPanel.open(this);
    };

    private boolean onBack(KeyEvent event) {
        if (!QuickPanel.enabled(this) || SystemClock.uptimeMillis() < passBackUntil) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                backHeld = false;
                idleHandler.postDelayed(holdBack, HOLD_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            idleHandler.removeCallbacks(holdBack);
            if (!backHeld && !event.isCanceled()) {
                passBackUntil = SystemClock.uptimeMillis() + 400;
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            backHeld = false;
        }
        return true;
    }

    private VolumeOverlay volume;
    private final android.content.BroadcastReceiver volumeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                NowPlaying.startSmall(c);   // TV off: the music card comes back small
                return;
            }
            int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", android.media.AudioManager.STREAM_MUSIC);
            if (stream != android.media.AudioManager.STREAM_MUSIC) return;
            if (Night.enforceCap(c)) return;   // brought down to the night limit; the next change shows it
            if (volume != null) volume.onVolumeChanged();
        }
    };
    private SongPopup songPopup;
    private String[] takeover = new String[0];
    private long lastTakeover;

    @Override
    protected void onServiceConnected() {
        takeover = getResources().getStringArray(R.array.takeover_packages);
        BootTrace.watch(this);
        BootTrace.mark(this, "Home button service");
        if (volume == null) {
            volume = new VolumeOverlay(this);
            android.content.IntentFilter f = new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            f.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
            f.addAction(Intent.ACTION_SCREEN_OFF);
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, f, RECEIVER_EXPORTED);
            else registerReceiver(volumeReceiver, f);
        }
        if (songPopup == null) songPopup = new SongPopup(this);
        live = this;
        lastInput = SystemClock.elapsedRealtime();
        idleHandler.removeCallbacks(idleCheck);
        idleHandler.postDelayed(idleCheck, IDLE_CHECK_MS);
        // Just updated (SelfHeal.Receiver): come back to this home screen instead of the stock one.
        if (takeGoHomeFlag(this)) goHome();
    }

    private long lastSample;
    private long pausedUntil;
    private int failures;
    private boolean cameUp = true;

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
        if (!"com.android.systemui".contentEquals(pkg)) SongPopup.front = pkg.toString();   // the volume bar is no app
        if (getPackageName().contentEquals(pkg)) {   // TV Home is on screen: the last takeover worked
            cameUp = true;
            failures = 0;
            return;
        }
        CharSequence cls = event.getClassName();
        for (String p : takeover) {
            // "package" matches any of its windows; "package/full.Activity" only that one screen
            // (Fire TV, where the same package also shows Amazon's movie and show pages).
            int slash = p.indexOf('/');
            boolean match = slash < 0 ? p.contentEquals(pkg)
                    : p.regionMatches(0, pkg.toString(), 0, slash) && slash == pkg.length()
                    && cls != null && p.substring(slash + 1).contentEquals(cls);
            if (match) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastTakeover < 1000) return;   // one hop per second, never a loop
                if (now < pausedUntil) return;
                // TV Home never came up after the last takeover (it fails to open, or the firmware
                // insists): after a few of those in a row, stop for ten minutes instead of bouncing.
                // Pressing Home often is fine: each time TV Home appears, the count starts over.
                if (!cameUp && now - lastTakeover < 5000) {
                    if (++failures >= 4) {
                        pausedUntil = now + 10 * 60_000;
                        failures = 0;
                        return;
                    }
                } else {
                    failures = 0;
                }
                cameUp = false;
                lastTakeover = now;
                goHome();
                return;
            }
        }
    }

    // ---- Still watching? ---------------------------------------------------------------------------

    private static final long IDLE_CHECK_MS = 2 * 60_000;
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile long lastInput;
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            checkIdle();
            idleHandler.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    private void checkIdle() {
        Night.enforceCap(this);   // night started while the volume was up
        int hours = StillWatching.hours(this);
        long now = SystemClock.elapsedRealtime();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (hours <= 0 || pm == null || !pm.isInteractive()) {
            lastInput = now;   // screen off or feature off: start counting again when it's back
            return;
        }
        if (now - lastInput >= hours * 3_600_000L && !StillWatching.Prompt.showing) {
            lastInput = now;
            StillWatching.ask(this, 60);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        idleHandler.removeCallbacks(idleCheck);
        if (live == this) live = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        if (songPopup != null) songPopup.release();
        if (volume != null) {
            volume.release();
            try {
                unregisterReceiver(volumeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
    }

    /** Consumes SelfHeal's "just updated" flag: true when TV Home should come back to the front now. */
    static boolean takeGoHomeFlag(android.content.Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(SelfHeal.PREFS, MODE_PRIVATE);
        if (!p.getBoolean(SelfHeal.KEY_GO_HOME, false)) return false;
        p.edit().putBoolean(SelfHeal.KEY_GO_HOME, false).commit();
        return true;
    }

    void goHome() {
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
