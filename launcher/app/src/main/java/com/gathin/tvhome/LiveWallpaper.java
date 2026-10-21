package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Moving wallpaper (Wallpaper menu): the picture drifts very slowly, like a live photo, and the stars
 * in it twinkle. The stars are the wallpaper's own: small bright points on a dark sky are found in the
 * picture, and a soft glow pulses over each. Pictures without a night sky just drift.
 */
final class LiveWallpaper {

    static final String PREFS = "look", KEY = "motion";

    private LiveWallpaper() {
    }

    /** On by default, except on low-memory TVs and boxes (many budget TVs), where it starts off. */
    static boolean enabled(Context ctx) {
        if (SafeStart.on(ctx)) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, !lowEnd(ctx));
    }

    static boolean lowEnd(Context ctx) {
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem < 1600L * 1024 * 1024;   // under ~1.5 GB
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    /** Slow drift of the whole wallpaper layer; scaled up a little so its edges never show. */
    static ValueAnimator drift(View layer, float density) {
        layer.setScaleX(1.07f);
        layer.setScaleY(1.07f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        a.setDuration(70_000);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            layer.setTranslationX((float) Math.sin(t) * 16 * density);
            layer.setTranslationY((float) Math.sin(2 * t) * 7 * density);
        });
        return a;
    }

    /** Bright, isolated points in the upper part of the picture: its stars. Bitmap coordinates. */
    static float[][] findStars(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int r = Math.max(3, w / 320);
        List<float[]> found = new ArrayList<>();
        int top = (int) (h * 0.65f);
        for (int y = r; y < top - r; y += 2) {
            for (int x = r; x < w - r; x += 2) {
                float l = lum(px[y * w + x]);
                if (l < 0.62f) continue;
                float ring = (lum(px[(y - r) * w + x]) + lum(px[(y + r) * w + x])
                        + lum(px[y * w + x - r]) + lum(px[y * w + x + r])) / 4f;
                if (ring > 0.42f || l - ring < 0.25f) continue;
                found.add(new float[]{x, y, l});
            }
        }
        Collections.shuffle(found, new Random(7));
        List<float[]> kept = new ArrayList<>();
        float minD = w / 60f;
        for (float[] s : found) {
            boolean near = false;
            for (float[] k : kept) {
                if (Math.abs(k[0] - s[0]) < minD && Math.abs(k[1] - s[1]) < minD) {
                    near = true;
                    break;
                }
            }
            if (!near) kept.add(s);
            if (kept.size() >= max) break;
        }
        return kept.toArray(new float[0][]);
    }

    private static float lum(int p) {
        return (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
    }

    /** Draws the twinkling glows over the wallpaper's stars (CENTER_CROP mapping), ~25 fps. */
    static final class Twinkle extends View {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Bitmap glow = makeGlow();
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final float density;
        private float[][] stars = new float[0][];
        private float[] phase = new float[0], speed = new float[0], size = new float[0];
        private int bw = 1, bh = 1;
        private boolean running;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, 80);
            }
        };

        Twinkle(Context ctx, float density) {
            super(ctx);
            this.density = density;
            core.setColor(0xFFFFFFFF);
        }

        private static Bitmap makeGlow() {
            int n = 48;
            Bitmap g = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.RadialGradient(n / 2f, n / 2f, n / 2f,
                    new int[]{0xFFFFFFFF, 0x66E8F0FF, 0x00E8F0FF}, new float[]{0f, 0.25f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            new Canvas(g).drawCircle(n / 2f, n / 2f, n / 2f, p);
            return g;
        }

        void setStars(float[][] s, int bitmapW, int bitmapH) {
            stars = s;
            bw = bitmapW;
            bh = bitmapH;
            Random rnd = new Random(11);
            phase = new float[s.length];
            speed = new float[s.length];
            size = new float[s.length];
            for (int i = 0; i < s.length; i++) {
                phase[i] = rnd.nextFloat() * 6.283f;
                speed[i] = 6.283f / (2500 + rnd.nextInt(4500));   // one pulse every 2.5 to 7 s
                size[i] = (1.1f + rnd.nextFloat() * 1.4f) * density;
            }
            invalidate();
        }

        void start() {
            if (running || stars.length == 0) return;
            running = true;
            handler.post(frame);
        }

        void stop() {
            running = false;
            handler.removeCallbacks(frame);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (stars.length == 0) return;
            float vw = getWidth(), vh = getHeight();
            float k = Math.max(vw / bw, vh / bh);
            float ox = (vw - bw * k) / 2f, oy = (vh - bh * k) / 2f;
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < stars.length; i++) {
                float s = (float) Math.sin(phase[i] + now * speed[i]);
                float a = Math.max(0f, s);            // brightens, then rests at the picture's own star
                a = a * a;
                if (a < 0.02f) continue;
                float x = ox + stars[i][0] * k, y = oy + stars[i][1] * k;
                float g = size[i] * 4.5f;
                dst.set(x - g, y - g, x + g, y + g);
                halo.setAlpha((int) (150 * a));
                c.drawBitmap(glow, null, dst, halo);
                core.setAlpha((int) (235 * a));
                c.drawCircle(x, y, size[i], core);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }
    }
}
