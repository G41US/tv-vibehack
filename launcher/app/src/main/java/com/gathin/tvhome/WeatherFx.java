package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.Random;

/**
 * Live weather on the wallpaper (part of Moving wallpaper): light rain falls when it's raining at the
 * weather's place, now and then lightning in a thunderstorm, snow when it snows, a slow drifting haze
 * on harmattan days (low visibility) and in fog. Clear and cloudy skies add nothing.
 */
final class WeatherFx extends View {

    static final int NONE = 0, RAIN = 1, STORM = 2, SNOW = 3, HAZE = 4, FOG = 5;

    /** From the WMO code and visibility (metres) the weather line was made from. */
    static int modeFor(int code, float visibility) {
        if (code >= 95) return STORM;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code == 45 || code == 48) return FOG;
        if (code >= 0 && visibility > 0 && visibility < 5000) return HAZE;   // harmattan dust
        return NONE;
    }

    private final float density;
    private final Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint();
    private final Paint flash = new Paint();
    private final Random rnd = new Random(5);
    private int mode = NONE;
    private float[] x = new float[0], y = new float[0], speed = new float[0], len = new float[0];
    private long last;
    private long nextFlash, flashAt;
    private boolean running;
    private ValueAnimator drift;

    WeatherFx(Context c, float density) {
        super(c);
        this.density = density;
        drop.setStrokeWidth(1.4f * density);
        drop.setStrokeCap(Paint.Cap.ROUND);
        drop.setColor(0x73E6F0FF);
        flake.setColor(0xCCFFFFFF);
        flash.setColor(0xFFFFFFFF);
    }

    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        int n = m == RAIN ? 55 : m == STORM ? 70 : m == SNOW ? 45 : 0;
        x = new float[n];
        y = new float[n];
        speed = new float[n];
        len = new float[n];
        for (int i = 0; i < n; i++) seed(i, true);
        nextFlash = SystemClock.uptimeMillis() + 6000;
        setHazeDrift(m == HAZE || m == FOG);
        invalidate();
        if (running) start();
    }

    private void seed(int i, boolean anywhere) {
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        x[i] = rnd.nextFloat() * (w + 200 * density) - 100 * density;
        y[i] = anywhere ? rnd.nextFloat() * h : -rnd.nextFloat() * 120 * density;
        if (mode == SNOW) {
            speed[i] = (18 + rnd.nextFloat() * 30) * density;      // dp per second
            len[i] = (1.2f + rnd.nextFloat() * 2f) * density;      // radius
        } else {
            speed[i] = (520 + rnd.nextFloat() * 380) * density;
            len[i] = (12 + rnd.nextFloat() * 14) * density;
        }
    }

    void start() {
        running = true;
        last = SystemClock.uptimeMillis();
        if (mode == RAIN || mode == STORM || mode == SNOW) postInvalidateOnAnimation();
        if (drift != null && drift.isPaused()) drift.resume();
    }

    void stop() {
        running = false;
        if (drift != null) drift.pause();
    }

    /** Haze and fog: one soft band drawn once, gliding slowly side to side (no redrawing). */
    private void setHazeDrift(boolean on) {
        if (drift != null) {
            drift.cancel();
            drift = null;
            setTranslationX(0);
        }
        if (!on) return;
        drift = ValueAnimator.ofFloat(-60 * density, 60 * density);
        drift.setDuration(26_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new AccelerateDecelerateInterpolator());
        drift.addUpdateListener(a -> setTranslationX((float) a.getAnimatedValue()));
        if (running) drift.start();
        else {
            drift.start();
            drift.pause();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (mode == HAZE || mode == FOG) {
            int col = mode == HAZE ? 0xD9C9A27A : 0xD9C8CDD4;   // dusty amber / grey
            veil.setShader(new LinearGradient(0, h * 0.25f, 0, h,
                    new int[]{col & 0x00FFFFFF, (col & 0x00FFFFFF) | 0x3D000000, (col & 0x00FFFFFF) | 0x26000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(-100 * density, 0, w + 100 * density, h, veil);
            return;
        }
        if (x.length == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float slant = 0.18f;
        for (int i = 0; i < x.length; i++) {
            y[i] += speed[i] * dt;
            if (mode == SNOW) {
                x[i] += (float) Math.sin((now / 1000f) + i) * 8 * density * dt;
                c.drawCircle(x[i], y[i], len[i], flake);
            } else {
                x[i] -= speed[i] * slant * dt;
                c.drawLine(x[i], y[i], x[i] + len[i] * slant, y[i] - len[i], drop);
            }
            if (y[i] > h + 20 * density) seed(i, false);
        }
        if (mode == STORM) {
            if (now > nextFlash) {
                flashAt = now;
                nextFlash = now + 9000 + rnd.nextInt(14000);
            }
            long since = now - flashAt;
            if (since < 450) {
                // Two quick flickers, like distant lightning.
                float f = since < 90 ? 1f - since / 90f : since > 160 && since < 300 ? 0.6f * (1f - (since - 160) / 140f) : 0f;
                flash.setAlpha((int) (60 * f));
                c.drawRect(0, 0, w, h, flash);
            }
        }
        if (running) {
            if (LiveWallpaper.busy()) postDelayed(this::invalidate, 300);   // the remote is in use: wait
            else postInvalidateDelayed(50);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (drift != null) drift.cancel();
        super.onDetachedFromWindow();
    }
}
