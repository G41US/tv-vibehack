package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/**
 * The start-up screen Android opens right after its boot logo, in place of its own blank "waiting"
 * screen (FallbackHome). Android only lets a home screen that works before the box's storage is
 * unlocked run that early (directBootAware), so this one touches no settings or files: it just plays
 * the TV Home animation, then, once the video is done and Android has unlocked, hands over to TV Home.
 *
 * It is switched on while the box shuts down (when the start-up animation is on) and switches itself
 * off before handing over, so the Home button only ever finds TV Home's real home screen.
 */
public class BootHome extends Activity {

    static final String EXTRA_PLAYED = "boot_anim_played";
    /** On screen: HomeKeyService must not open Home over it (two home screens would mean a chooser). */
    static volatile boolean showing;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SurfaceView video;
    private volatile MediaPlayer player;
    private boolean surfaceReady, prepared, started, videoDone, handed;
    private long startedAt;
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handOver();
        }
    };

    /**
     * Only on boxes whose storage is locked while Android starts (file-based encryption, nearly all
     * Android 10+ TVs). Elsewhere there is no early waiting screen to replace, and a second home screen
     * from TV Home could make Android ask which home to use.
     */
    static boolean supported() {
        return "file".equals(Splash.prop("ro.crypto.type"));
    }

    /** Switched on or off with the start-up animation; used while the box shuts down and from Look. */
    static void enable(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, BootHome.class),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        showing = true;
        try {
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(0xFF000000);
            video = new SurfaceView(this);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float s = Math.max(dm.widthPixels / 1920f, dm.heightPixels / 1080f);
            root.addView(video, new FrameLayout.LayoutParams(Math.round(1920 * s), Math.round(1080 * s), Gravity.CENTER));
            setContentView(root);
            video.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    surfaceReady = true;
                    maybeStart();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int f, int w, int hgt) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    surfaceReady = false;
                }
            });
            registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            new Thread(this::load, "boot-anim").start();
        } catch (Throwable t) {
            videoDone = true;
        }
        // Whatever happens to the video, never sit here: Home follows within half a minute.
        h.postDelayed(() -> {
            videoDone = true;
            handOver();
        }, 30_000);
    }

    /** The video, from the app itself (readable before unlock), ready to play once the boot logo has gone. */
    private void load() {
        MediaPlayer mp = new MediaPlayer();   // no Looper here: its events come to the main thread
        try (AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.startup)) {
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.prepare();
        } catch (Exception e) {
            mp.release();
            h.post(() -> {
                videoDone = true;
                handOver();
            });
            return;
        }
        long until = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < until && Splash.bootAnimating()) SystemClock.sleep(100);
        h.post(() -> {
            if (handed || isFinishing()) {
                mp.release();
                return;
            }
            player = mp;
            prepared = true;
            maybeStart();
        });
    }

    private void maybeStart() {
        if (started || !prepared || !surfaceReady || handed) return;
        started = true;
        MediaPlayer mp = player;
        mp.setOnCompletionListener(p -> {
            videoDone = true;
            handOver();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            videoDone = true;
            handOver();
            return true;
        });
        try {
            mp.setDisplay(video.getHolder());
            mp.start();
            startedAt = SystemClock.elapsedRealtime();
            h.post(holdOnLogo);
        } catch (Exception e) {
            videoDone = true;
            handOver();
        }
    }

    /**
     * At 4 s the logo and name are complete (after that the video fades to black): stop there and hold
     * the logo until TV Home takes over with the same picture, so there's no black gap while Android
     * finishes starting.
     */
    private final Runnable holdOnLogo = new Runnable() {
        @Override
        public void run() {
            MediaPlayer mp = player;
            if (mp == null || videoDone) return;
            try {
                if (mp.getCurrentPosition() >= 4000) {
                    mp.pause();
                    videoDone = true;
                    handOver();
                    return;
                }
            } catch (Exception ignored) {
            }
            h.postDelayed(this, 50);
        }
    };

    private boolean unlocked() {
        UserManager um = getSystemService(UserManager.class);
        return um == null || um.isUserUnlocked();
    }

    /** Video done (or skipped) and Android unlocked: off with this screen, on to TV Home. */
    private void handOver() {
        if (handed || !videoDone || !unlocked()) return;
        handed = true;
        release();
        enable(this, false);   // first, so Home resolves to TV Home's real home screen only
        try {
            if (startedAt > 0) BootTrace.mark(this, "animation started (start-up screen)", startedAt);
            BootTrace.mark(this, "start-up screen handed over");
        } catch (Exception ignored) {
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_PLAYED, startedAt > 0);
        try {
            startActivity(home);
        } catch (Exception e) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Any button skips the animation (Home follows as soon as Android has unlocked).
        if (e.getAction() == KeyEvent.ACTION_UP && !videoDone) {
            videoDone = true;
            release();
            handOver();
        }
        return true;
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        showing = false;
        h.removeCallbacksAndMessages(null);
        release();
        try {
            unregisterReceiver(unlocked);
        } catch (Exception ignored) {
        }
    }
}
