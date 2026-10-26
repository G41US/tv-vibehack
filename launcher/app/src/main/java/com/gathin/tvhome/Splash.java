package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Start-up animation: the TV Home logo traced in light (res/raw/startup.mp4, made by tools/splash).
 * Plays full screen the first time Home opens after the box starts, then fades into Home. Any button
 * skips it. Not played in safe mode, before setup is done, with animations turned off in the TV's
 * settings, or when switched off in Look.
 *
 * Built for slow starts (TV check's "Last start-up" showed why): the video is loaded on its own
 * thread while the box's boot animation is still up, and plays on its own surface, so Home being
 * built underneath can't hold it up or make it stutter. It covers Home from TV Home's first frame
 * (the animation opens on black), so Home is never seen before it. If the video still isn't on
 * screen a moment after it should start, Home just shows.
 */
final class Splash {

    static final String KEY_ON = "startup_anim", KEY_UP = "startup_uptime", KEY_LAST = "startup_last",
            KEY_DONE = "startup_done";
    private static final long BOOTANIM_MAX_MS = 20_000, FIRST_FRAME_MS = 3000;

    private final Activity act;
    private final FrameLayout layer;
    private final SurfaceView video;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile MediaPlayer player;
    private boolean prepared, surfaceReady, bootDone, started, playing;
    private volatile boolean ended;
    private Runnable timeout;
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
        if (a.getIntent() != null && a.getIntent().getBooleanExtra(BootHome.EXTRA_PLAYED, false)) {
            // Already played by the start-up screen, straight after the boot logo.
            p.edit().putBoolean(KEY_DONE, true).putString(KEY_LAST, "played (start-up screen)").commit();
            return null;
        }
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
        video = new SurfaceView(a);
        video.setZOrderOnTop(true);   // above Home; see-through (so black) until the first frame
        // Fill the screen from the centre whatever its shape (the logo sits well inside the 16:9 frame).
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        float s = Math.max(dm.widthPixels / 1920f, dm.heightPixels / 1080f);
        layer.addView(video, new FrameLayout.LayoutParams(Math.round(1920 * s), Math.round(1080 * s), Gravity.CENTER));
        a.addContentView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                maybeStart();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int hgt) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                end();
            }
        });
        // Load the video now, off the main thread, while the box is still busy starting.
        new Thread(() -> {
            MediaPlayer mp = new MediaPlayer();   // no Looper on this thread: its events come to the main one
            try {
                mp.setDataSource(act, Uri.parse("android.resource://" + act.getPackageName() + "/" + R.raw.startup));
                mp.prepare();
            } catch (Exception e) {
                mp.release();
                h.post(() -> {
                    note("skipped: " + e.getClass().getSimpleName());
                    end();
                });
                return;
            }
            h.post(() -> {
                if (ended) {
                    mp.release();
                    return;
                }
                player = mp;
                prepared = true;
                maybeStart();
            });
        }, "splash-load").start();
        // The box's own start-up animation may still be on screen: wait until it's gone.
        new Thread(() -> {
            long until = SystemClock.uptimeMillis() + BOOTANIM_MAX_MS;
            while (SystemClock.uptimeMillis() < until && bootAnimating()) SystemClock.sleep(200);
            h.post(() -> {
                BootTrace.mark(act, "boot logo gone");
                bootDone = true;
                maybeStart();
            });
        }, "splash-wait").start();
    }

    /** The boot logo is still up: not yet told to stop (some boxes keep its service "running" long after). */
    static boolean bootAnimating() {
        return !"1".equals(prop("service.bootanim.exit")) && "running".equals(prop("init.svc.bootanim"));
    }

    static String prop(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", name});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return String.valueOf(r.readLine()).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void maybeStart() {
        if (ended || started || !prepared || !surfaceReady || !bootDone) return;
        started = true;
        MediaPlayer mp = player;
        mp.setOnInfoListener((p, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) shown();
            return false;
        });
        mp.setOnCompletionListener(p -> end());
        mp.setOnErrorListener((p, what, extra) -> {
            note("skipped: video error " + what + "/" + extra);
            end();
            return true;
        });
        try {
            mp.setDisplay(video.getHolder());
            mp.start();
        } catch (Exception e) {
            note("skipped: " + e.getClass().getSimpleName());
            end();
            return;
        }
        BootTrace.mark(act, "animation started");
        h.postDelayed(this::runPlaying, 600);   // Home's own loading waits until the video is under way
        h.postDelayed(timeout = () -> {
            if (playing) return;
            // Some boxes never report the first frame: going by the clock is as good.
            if (mp.isPlaying() && mp.getCurrentPosition() > 0) shown();
            else {
                note("skipped: video didn't start in time");
                end();
            }
        }, FIRST_FRAME_MS);
    }

    private void shown() {
        if (playing || ended) return;
        playing = true;
        note("played");
    }

    /** Home is on screen with nothing over it: no animation until the box starts again. */
    static void homeSeen(Context c) {
        SharedPreferences p = c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_DONE, false)) p.edit().putBoolean(KEY_DONE, true).commit();
    }

    private Runnable whenPlaying;

    /** Runs r once the video is playing (or the animation is skipped), so it never delays the start. */
    void whenPlaying(Runnable r) {
        whenPlaying = r;
    }

    private void runPlaying() {
        Runnable r = whenPlaying;
        whenPlaying = null;
        if (r != null) r.run();
    }

    /** Runs r when the animation is gone (for "Who's watching?"). */
    void then(Runnable r) {
        then = r;
    }

    /** On screen: waiting to play, or playing. */
    boolean active() {
        return !ended;
    }

    /** Fades out to the home screen underneath (the video itself ends on black). */
    void end() {
        if (ended) return;
        ended = true;
        if (timeout != null) h.removeCallbacks(timeout);
        runPlaying();
        release();
        layer.removeView(video);
        layer.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            if (then != null && !act.isFinishing()) then.run();
        }).start();
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
}
