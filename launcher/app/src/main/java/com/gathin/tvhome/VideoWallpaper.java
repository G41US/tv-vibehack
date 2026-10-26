package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Moving scenes as wallpaper: ocean waves, rain on a window, a city at night, northern lights, golden
 * water. Short, silent clips from Mixkit (free licence), downloaded once when picked and kept on the
 * box. They play only on the home screen, pause the moment an app opens, and loop with a one-second
 * crossfade so there's no jump.
 */
final class VideoWallpaper {

    /** Wallpaper key, name, clip (720p, no sound). */
    static final String[] KEYS = {"v_ocean", "v_rain", "v_city", "v_aurora", "v_golden"};
    static final int[] NAMES = {R.string.wp_v_ocean, R.string.wp_v_rain, R.string.wp_v_city, R.string.wp_v_aurora, R.string.wp_v_golden};
    private static final String[] URLS = {
            "https://assets.mixkit.co/videos/1925/1925-720.mp4",
            "https://assets.mixkit.co/active_storage/video_items/99839/1717104607/99839-video-720.mp4",
            "https://assets.mixkit.co/videos/30169/30169-720.mp4",
            "https://assets.mixkit.co/videos/4034/4034-720.mp4",
            "https://assets.mixkit.co/videos/34363/34363-720.mp4",
    };

    private VideoWallpaper() {
    }

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    static File file(Context c, String key) {
        return new File(new File(c.getFilesDir(), "video"), key + ".mp4");
    }

    interface Done {
        void ready();
    }

    /** Downloads the clip once (with a progress card), then calls done; already there: done at once. */
    static void fetch(Activity a, String key, Done done) {
        File f = file(a, key);
        if (f.isFile() && f.length() > 0) {
            done.ready();
            return;
        }
        int i = index(key);
        if (i < 0) return;
        GlassMenu.Progress p = GlassMenu.progress(a, a.getString(R.string.wp_v_downloading, a.getString(NAMES[i])));
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            boolean ok = false;
            File tmp = new File(f.getPath() + ".part");
            try {
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(URLS[i]).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, shown = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != shown) {
                            shown = pct;
                            final int show = pct;
                            main.post(() -> p.set(show >= 0 ? show + "%" : ""));
                        }
                    }
                }
                ok = tmp.length() > 0 && (total <= 0 || tmp.length() == total) && tmp.renameTo(f);
            } catch (Exception ignored) {
                // no internet, or the clip moved
            }
            if (!ok) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            final boolean success = ok;
            main.post(() -> {
                p.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (success) done.ready();
                else GlassMenu.message(a, a.getString(NAMES[i]), a.getString(R.string.wp_v_failed),
                        new String[]{a.getString(android.R.string.ok)}, null);
            });
        }, "video-wallpaper").start();
    }

    /**
     * The playing scene: two players take turns, the next one fading in over the last second of the
     * other, so the loop has no jump. A box that can't run two at once just loops one.
     */
    static final class Player extends FrameLayout {
        private static final long FADE_MS = 1000;
        private final File clip;
        private final TextureView[] views = new TextureView[2];
        private final MediaPlayer[] players = new MediaPlayer[2];
        private int current;
        private boolean playing, simpleLoop;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final Runnable watch = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                MediaPlayer mp = players[current];
                try {
                    if (mp != null && !simpleLoop && mp.getDuration() > 3 * FADE_MS
                            && mp.getCurrentPosition() >= mp.getDuration() - FADE_MS) {
                        crossfade();
                    }
                } catch (IllegalStateException ignored) {
                    // between states
                }
                h.postDelayed(this, 100);
            }
        };

        Player(Context c, File clip) {
            super(c);
            this.clip = clip;
            for (int i = 0; i < 2; i++) {
                final int k = i;
                TextureView tv = new TextureView(c);
                tv.setAlpha(i == 0 ? 1f : 0f);
                tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                        prepare(k, new Surface(st));
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        release(k);
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    }
                });
                views[i] = tv;
                addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }

        private void prepare(int k, Surface s) {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(clip.getPath());
                mp.setSurface(s);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(m -> {
                    if (k == current && playing) m.start();
                });
                mp.prepareAsync();
                players[k] = mp;
            } catch (Exception e) {
                players[k] = null;   // crossfade() then falls back to a plain loop
            }
        }

        private void crossfade() {
            int next = 1 - current;
            MediaPlayer a = players[current], b = players[next];
            if (b == null) {
                simpleLoop = true;
                if (a != null) a.setLooping(true);
                return;
            }
            try {
                b.seekTo(0);
                b.start();
            } catch (IllegalStateException e) {
                simpleLoop = true;
                if (a != null) a.setLooping(true);
                return;
            }
            final int was = current;
            current = next;
            views[next].animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                views[was].setAlpha(0f);
                try {
                    if (players[was] != null) {
                        players[was].pause();
                        players[was].seekTo(0);
                    }
                } catch (IllegalStateException ignored) {
                    // released meanwhile
                }
            }).start();
            views[next].bringToFront();
        }

        void start() {
            playing = true;
            try {
                if (players[current] != null) players[current].start();
            } catch (IllegalStateException ignored) {
                // still preparing: it starts when ready
            }
            h.removeCallbacks(watch);
            h.post(watch);
        }

        void stop() {
            playing = false;
            h.removeCallbacks(watch);
            for (MediaPlayer mp : players) {
                try {
                    if (mp != null && mp.isPlaying()) mp.pause();
                } catch (IllegalStateException ignored) {
                    // not ready
                }
            }
        }

        private void release(int k) {
            if (players[k] != null) {
                players[k].release();
                players[k] = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            release(0);
            release(1);
            super.onDetachedFromWindow();
        }
    }
}
