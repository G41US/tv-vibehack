package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Start-up animation: the TV Home logo traced in light (res/raw/startup.mp4, made by tools/splash).
 * Plays full screen the first time Home opens after the box starts, over the home screen that loads
 * underneath, then fades away. Any button skips it. Not played in safe mode, before setup is done,
 * with animations turned off in the TV's settings, or when switched off in Look.
 *
 * It covers Home from TV Home's very first frame (the animation opens on black anyway), so Home is
 * never seen before it. It waits for the box's own boot animation to finish; if the video then isn't
 * on screen within a moment, it fades straight to Home. What happened last time is kept for TV check
 * ("startup_last").
 */
final class Splash {

    static final String KEY_ON = "startup_anim", KEY_UP = "startup_uptime", KEY_LAST = "startup_last",
            KEY_DONE = "startup_done";
    private static final long BOOTANIM_MAX_MS = 20_000, FIRST_FRAME_MS = 2500;

    private final Activity act;
    private final TextureView video;
    private final FrameLayout layer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private SurfaceTexture surface;
    private boolean ready, ended, playing;
    private Runnable then;

    static boolean on(Context c) {
        return c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    static String last(Context c) {
        return c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, "never");
    }

    private void note(String what) {
        act.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, what).apply();
    }

    /** Starts the animation if this is the first Home since the box started; null when it won't play. */
    static Splash maybeShow(Activity a) {
        SharedPreferences p = a.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE);
        // Time since the box started only goes down when it restarts (BOOT_COUNT isn't kept on every box).
        long up = SystemClock.elapsedRealtime(), was = p.getLong(KEY_UP, Long.MAX_VALUE);
        SharedPreferences.Editor e = p.edit().putLong(KEY_UP, up);
        if (up < was) e.putBoolean(KEY_DONE, false);   // the box restarted
        e.commit();   // written now: TV Home's process can be killed and restarted while the box starts
        // Once per start, and never once Home has been seen (it would look like a glitch after Home).
        if (p.getBoolean(KEY_DONE, false)) return null;
        p.edit().putBoolean(KEY_DONE, true).commit();
        if (!on(a)) return null;
        String skip = SafeStart.on(a) ? "skipped: safe mode" : !SetupPanel.serviceOn(a) ? "skipped: setup not done"
                : Settings.Global.getFloat(a.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                ? "skipped: animations off" : null;
        if (skip != null) {
            p.edit().putString(KEY_LAST, skip).apply();
            return null;
        }
        return new Splash(a);
    }

    private Splash(Activity a) {
        act = a;
        layer = new FrameLayout(a);
        layer.setBackgroundColor(0xFF000000);
        video = new TextureView(a);
        layer.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        a.addContentView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hgt) {
                surface = st;
                maybePlay();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hgt) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                end();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (playing || ended || player == null) return;
                playing = true;   // the first frame is on screen
                h.removeCallbacksAndMessages(null);
                note("played");
            }
        });
        // The box's own start-up animation may still be on screen: wait until it's gone.
        new Thread(() -> {
            long until = SystemClock.uptimeMillis() + BOOTANIM_MAX_MS;
            while (SystemClock.uptimeMillis() < until && bootAnimating()) SystemClock.sleep(250);
            h.post(() -> {
                BootTrace.mark(act, "boot logo gone");
                ready = true;
                maybePlay();
            });
        }, "splash-wait").start();
    }

    private static boolean bootAnimating() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", "init.svc.bootanim"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return "running".equals(String.valueOf(r.readLine()).trim());
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void maybePlay() {
        if (ended || !ready || surface == null || player != null) return;
        h.postDelayed(() -> {
            if (!playing) {
                note("skipped: video didn't start in time");
                end();
            }
        }, FIRST_FRAME_MS);
        try {
            player = new MediaPlayer();
            player.setDataSource(act, Uri.parse("android.resource://" + act.getPackageName() + "/" + R.raw.startup));
            player.setSurface(new Surface(surface));
            player.setOnVideoSizeChangedListener((mp, vw, vh) -> {
                int w = video.getWidth(), hgt = video.getHeight();
                if (vw == 0 || vh == 0 || w == 0 || hgt == 0) return;
                // Fill the screen from the centre whatever its shape (the logo sits well inside the frame).
                float s = Math.max((float) w / vw, (float) hgt / vh);
                Matrix m = new Matrix();
                m.setScale(vw * s / w, vh * s / hgt, w / 2f, hgt / 2f);
                video.setTransform(m);
            });
            player.setOnPreparedListener(mp -> {
                if (!ended) mp.start();
            });
            player.setOnCompletionListener(mp -> end());
            player.setOnErrorListener((mp, what, extra) -> {
                note("skipped: video error " + what + "/" + extra);
                end();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            note("skipped: " + e.getClass().getSimpleName());
            end();
        }
    }

    /** Home is on screen with nothing over it: no animation until the box starts again. */
    static void homeSeen(Context c) {
        SharedPreferences p = c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_DONE, false)) p.edit().putBoolean(KEY_DONE, true).commit();
    }

    /** Runs r when the animation is gone (for "Who's watching?"). */
    void then(Runnable r) {
        then = r;
    }

    /** On screen: waiting to play, or playing. */
    boolean active() {
        return !ended;
    }

    /** Fades out to the home screen underneath. */
    void end() {
        if (ended) return;
        ended = true;
        h.removeCallbacksAndMessages(null);
        Runnable done = () -> {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            release();
            if (then != null && !act.isFinishing()) then.run();
        };
        layer.animate().alpha(0f).setDuration(350).withEndAction(done).start();
    }

    private void release() {
        if (player == null) return;
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }
}
