package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Album colour lighting: while music plays, two soft pools of the cover's colours glow over the
 * wallpaper and slowly sway, like smart lights following the song. Drawn once per song; the sway is a
 * cheap move of the whole layer, so the box doesn't redraw it every frame.
 */
final class MoodLight extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int a = 0, b = 0;
    private boolean on;
    private ValueAnimator sway;
    private final float density;

    MoodLight(Context c, float density) {
        super(c);
        this.density = density;
        setAlpha(0f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setScaleX(1.15f);
        setScaleY(1.15f);
    }

    /** New colours, and whether music is playing (the light shows only then). */
    void set(int colorA, int colorB, boolean playing) {
        if (colorA != a || colorB != b) {
            a = colorA;
            b = colorB;
            invalidate();
        }
        if (playing == on) return;
        on = playing;
        animate().cancel();
        animate().alpha(on ? 1f : 0f).setDuration(on ? 1500 : 900).start();
        // Static glow (fade in and out only): a moving full-screen layer cost smoothness on TV boxes.
    }

    private void startSway() {
        if (sway != null) {
            if (sway.isPaused()) sway.resume();
            return;
        }
        sway = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        sway.setDuration(16_000);
        sway.setRepeatCount(ValueAnimator.INFINITE);
        sway.setInterpolator(new LinearInterpolator());
        sway.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            setTranslationX((float) Math.sin(t) * 40 * density);
            setTranslationY((float) Math.cos(t * 2) * 22 * density);
        });
        sway.start();
    }

    void pause() {
        if (sway != null) sway.pause();
    }

    void resume() {
    }

    @Override
    protected void onDraw(Canvas c) {
        if (a == 0 && b == 0) return;
        float w = getWidth(), h = getHeight();
        paint.setShader(new RadialGradient(w * 0.18f, h * 0.82f, w * 0.62f,
                (a & 0x00FFFFFF) | 0x66000000, a & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.86f, h * 0.22f, w * 0.55f,
                (b & 0x00FFFFFF) | 0x52000000, b & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sway != null) sway.cancel();
        super.onDetachedFromWindow();
    }
}
