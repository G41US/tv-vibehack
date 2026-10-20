package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * TV Home's volume display, in place of Android's: a slim frosted capsule at the right edge with the
 * level as a glowing bar, the percentage above it and a speaker (or muted speaker) below. The remote's
 * volume buttons reach HomeKeyService first; it changes the volume without Android's panel and shows
 * this instead.
 *
 * Left alone (Android's own panel, or the TV's) when the box's volume is fixed, i.e. the TV itself
 * controls the volume over HDMI, or when the person picked Android's style in TV settings.
 */
final class VolumeOverlay {

    static final String PREFS = "look", KEY_STYLE = "volume_style";   // "" = TV Home, "android"
    private static final long HIDE_MS = 2200;

    private final AccessibilityService service;
    private final AudioManager am;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private LinearLayout panel;
    private Bar bar;
    private TextView pct;
    private ImageView icon;
    private boolean attached;

    VolumeOverlay(AccessibilityService service) {
        this.service = service;
        this.am = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.density = service.getResources().getDisplayMetrics().density;
    }

    static boolean enabled(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !"android".equals(p.getString(KEY_STYLE, ""));
    }

    /** Handles a volume key; false leaves it to Android. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                && code != KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (am == null || am.isVolumeFixed() || !enabled(service)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            try {
                int dir = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                        : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER
                        : AudioManager.ADJUST_TOGGLE_MUTE;
                if (code == KeyEvent.KEYCODE_VOLUME_MUTE && e.getRepeatCount() > 0) return true;
                // Pressing up while muted unmutes first, as Android does.
                if (dir == AudioManager.ADJUST_RAISE && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);   // 0: no Android panel
            } catch (SecurityException ex) {
                return false;   // e.g. Do Not Disturb rules: let Android deal with it
            }
            show();
        }
        return true;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void build() {
        Typeface font;
        try {
            font = service.getResources().getFont(R.font.outfit);
        } catch (Exception ex) {
            font = Typeface.DEFAULT;
        }
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(0, dp(16), 0, dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEB2A2A2E, 0xF0161618});
        bg.setCornerRadius(dp(36));
        bg.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackground(bg);
        panel.setElevation(dp(12));

        pct = new TextView(service);
        pct.setTextColor(Color.WHITE);
        pct.setTextSize(17);
        pct.setTypeface(font);
        pct.setGravity(Gravity.CENTER);
        panel.addView(pct, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        bar = new Bar(service, density);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(14), 0, 1f);
        blp.topMargin = dp(12);
        blp.bottomMargin = dp(14);
        panel.addView(bar, blp);

        icon = new ImageView(service);
        icon.setColorFilter(0xE6FFFFFF);
        panel.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
    }

    private void show() {
        if (panel == null) build();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0;
        float level = muted ? 0f : cur / (float) max;
        pct.setText(muted ? "—" : String.valueOf(Math.round(level * 100)));
        icon.setImageResource(muted ? R.drawable.ic_s_mute : R.drawable.ic_s_volume);
        bar.setMuted(muted);

        if (!attached) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(68), dp(300),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.x = dp(44);
            try {
                wm.addView(panel, lp);
                attached = true;
            } catch (Exception ex) {
                return;
            }
            bar.setLevel(level, false);
            panel.setAlpha(0f);
            panel.setTranslationX(dp(28));
            panel.animate().alpha(1f).translationX(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setTranslationX(0f);
            bar.setLevel(level, true);
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_MS);
    }

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            panel.animate().alpha(0f).translationX(dp(28)).setDuration(220).withEndAction(() -> {
                try {
                    wm.removeView(panel);
                } catch (Exception ignored) {
                }
                attached = false;
            }).start();
        }
    };

    void release() {
        handler.removeCallbacks(hide);
        if (attached) {
            try {
                wm.removeViewImmediate(panel);
            } catch (Exception ignored) {
            }
            attached = false;
        }
    }

    /** The level: a rounded track with a white fill from the bottom and a soft glow at its top. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float density;
        private float level;
        private ValueAnimator anim;

        Bar(Context ctx, float density) {
            super(ctx);
            this.density = density;
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // for the glow
            track.setColor(0x2EFFFFFF);
            fill.setColor(0xF2FFFFFF);
            glow.setColor(0x66FFFFFF);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(6 * density, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }

        void setMuted(boolean muted) {
            fill.setColor(muted ? 0x66FFFFFF : 0xF2FFFFFF);
            invalidate();
        }

        void setLevel(float to, boolean animate) {
            if (anim != null) anim.cancel();
            if (!animate) {
                level = to;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(level, to);
            anim.setDuration(140);
            anim.addUpdateListener(a -> {
                level = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float inset = 3 * density;   // room for the glow
            float bw = w - 2 * inset, rad = bw / 2;
            r.set(inset, 0, w - inset, h);
            c.drawRoundRect(r, rad, rad, track);
            if (level <= 0f) return;
            float top = h - Math.max(bw, h * level);
            r.set(inset, top, w - inset, h);
            c.drawRoundRect(r, rad, rad, glow);
            c.drawRoundRect(r, rad, rad, fill);
        }
    }
}
