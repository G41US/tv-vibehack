package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Start-up animation: the TV Home logo traced in light (res/raw/startup.mp4, made by tools/splash).
 * Plays full screen the first time Home opens after the box starts, over the home screen that loads
 * underneath, then fades away. Any button skips it. Not played in safe mode, before setup is done,
 * with animations turned off in the TV's settings, or when switched off in Look. If the box can't
 * play it, or it hasn't started within a second, Home just shows.
 */
final class Splash {

    static final String KEY_ON = "startup_anim", KEY_BOOT = "startup_boot";
    private static final long START_WITHIN_MS = 1000;

    private final Activity act;
    private final FrameLayout layer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Runnable then;
    private boolean ended;

    static boolean on(Context c) {
        return c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    /** Starts the animation if this is the first Home since the box started; null when it won't play. */
    static Splash maybeShow(Activity a) {
        android.content.SharedPreferences p = a.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE);
        int boot = Settings.Global.getInt(a.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot == p.getInt(KEY_BOOT, -2)) return null;
        p.edit().putInt(KEY_BOOT, boot).apply();   // marked before playing, so a bad file can't loop
        if (!on(a) || SafeStart.on(a) || !SetupPanel.serviceOn(a)) return null;
        if (Settings.Global.getFloat(a.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return null;
        return new Splash(a);
    }

    private Splash(Activity a) {
        act = a;
        layer = new FrameLayout(a);
        layer.setBackgroundColor(0xFF000000);
        TextureView video = new TextureView(a);
        layer.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        a.addContentView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.postDelayed(this::end, START_WITHIN_MS);   // cancelled once the video is playing
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hgt) {
                play(video, st, w, hgt);
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
            }
        });
    }

    private void play(TextureView video, SurfaceTexture st, int w, int hgt) {
        if (ended) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(act, Uri.parse("android.resource://" + act.getPackageName() + "/" + R.raw.startup));
            player.setSurface(new Surface(st));
            player.setOnVideoSizeChangedListener((mp, vw, vh) -> {
                if (vw == 0 || vh == 0) return;
                // Fill the screen from the centre whatever its shape (the logo sits well inside the frame).
                float s = Math.max((float) w / vw, (float) hgt / vh);
                Matrix m = new Matrix();
                m.setScale(vw * s / w, vh * s / hgt, w / 2f, hgt / 2f);
                video.setTransform(m);
            });
            player.setOnPreparedListener(mp -> {
                if (ended) return;
                mp.start();
                h.removeCallbacksAndMessages(null);
            });
            player.setOnCompletionListener(mp -> end());
            player.setOnErrorListener((mp, what, extra) -> {
                end();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            end();
        }
    }

    /** Runs r when the animation is gone (for "Who's watching?"). */
    void then(Runnable r) {
        then = r;
    }

    boolean showing() {
        return !ended;
    }

    /** Fades out to the home screen underneath. */
    void end() {
        if (ended) return;
        ended = true;
        h.removeCallbacksAndMessages(null);
        layer.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            release();
            if (then != null && !act.isFinishing()) then.run();
        }).start();
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
