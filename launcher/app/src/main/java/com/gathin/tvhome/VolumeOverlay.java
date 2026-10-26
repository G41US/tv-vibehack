package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
 * level as a glowing bar, the percentage above it and a speaker (or muted speaker) below.
 *
 * On Android TV the system handles the volume buttons before any app sees them, and shows its own
 * slider (a vertical bar, centred at the right edge). So this listens for volume changes and draws the
 * capsule as an accessibility overlay, which sits above the system's volume window, exactly over that
 * slider and a little larger, so only this one is seen. Where the buttons do reach HomeKeyService, it
 * changes the volume itself without Android's slider.
 *
 * Left alone (Android's own panel, or the TV's) when the box's volume is fixed, i.e. the TV itself
 * controls the volume over HDMI, or when the person picked Android's style in TV settings.
 */
final class VolumeOverlay {

    static final String PREFS = "look", KEY_STYLE = "volume_style";   // "" = TV Home, "android"
    private static final long HIDE_MS = 3600;   // outlasts Android's slider underneath

    private final AccessibilityService service;
    private final AudioManager am;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;

    private LinearLayout panel;
    private ValueAnimator count;
    private int shownNumber = -2;

    /** The number rolls up or down to the new level instead of jumping. */
    private void countTo(int target) {
        if (count != null) count.cancel();
        if (target < 0) {
            shownNumber = -1;
            pct.setText("—");
            return;
        }
        int from = shownNumber < 0 ? target : shownNumber;
        if (from == target) {
            shownNumber = target;
            pct.setText(String.valueOf(target));
            return;
        }
        count = ValueAnimator.ofInt(from, target);
        count.setDuration(180);
        count.addUpdateListener(v -> {
            shownNumber = (int) v.getAnimatedValue();
            pct.setText(String.valueOf(shownNumber));
        });
        count.start();
    }
    private GradientDrawable bg;
    private Boolean shapedUpright;
    // New name: drops any badly measured spot saved by older builds.
    static final String KEY_RECT = "volume_bar";
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

    /** Volume changed (the remote's buttons, handled by Android): show over Android's slider. */
    void onVolumeChanged() {
        if (am == null || am.isVolumeFixed() || !enabled(service)) return;
        if (QuickPanel.showing) return;   // the Quick panel shows the volume itself
        show();
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
        bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF26262E, 0xFF111116});   // opaque: nothing of the slider below shows
        bg.setStroke(dp(1), 0x40FFFFFF);
        panel.setBackground(bg);
        panel.setElevation(dp(12));

        pct = new TextView(service);
        pct.setTextColor(Color.WHITE);
        pct.setTextSize(19);
        pct.setTypeface(Fonts.weight(service, 700));
        pct.setGravity(Gravity.CENTER);
        bar = new Bar(service, density);
        icon = new ImageView(service);
        icon.setColorFilter(0xE6FFFFFF);
        shape(true);
    }

    /** Lays the capsule out upright (level number, bar, speaker) or lying down (speaker, bar, number). */
    private void shape(boolean upright) {
        if (shapedUpright != null && shapedUpright == upright) return;
        shapedUpright = upright;
        panel.removeAllViews();
        bar.vertical = upright;
        if (upright) {
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER_HORIZONTAL);
            panel.setPadding(0, dp(16), 0, dp(16));
            panel.addView(pct, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), 0, 1f);
            blp.topMargin = dp(12);
            blp.bottomMargin = dp(14);
            panel.addView(bar, blp);
            panel.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        } else {
            panel.setOrientation(LinearLayout.HORIZONTAL);
            panel.setGravity(Gravity.CENTER_VERTICAL);
            panel.setPadding(dp(18), 0, dp(18), 0);
            panel.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(22), 1f);
            blp.leftMargin = dp(14);
            blp.rightMargin = dp(12);
            panel.addView(bar, blp);
            panel.addView(pct, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    // ---- where the TV's own volume bar is ---------------------------------------------------------

    /**
     * The screen area to cover: where this TV's own volume bar was last seen (found through the
     * accessibility window list and remembered), else Google TV's slider at the right edge.
     */
    private Rect target() {
        String saved = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECT, null);
        if (saved != null) {
            Rect r = Rect.unflattenFromString(saved);
            if (r != null && !r.isEmpty()) return r;
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int w = dp(56), h = dp(284);
        int right = dm.widthPixels - dp(26);
        int top = (dm.heightPixels - h) / 2;
        return new Rect(right - w, top, right, top + h);
    }

    /** The system's volume window, if the box reports it: a small system window from System UI or the maker's framework. */
    private Rect findSystemBar() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            long screen = (long) dm.widthPixels * dm.heightPixels;
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                if (b.isEmpty() || (long) b.width() * b.height() > screen * 0.3) continue;
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";
                if (pkg.equals(service.getPackageName())) continue;
                if (pkg.contains("systemui") || pkg.contains("volume") || pkg.startsWith("com.tcl.framework")
                        || pkg.startsWith("com.hisense") || pkg.startsWith("com.mitv") || pkg.contains("tvsystem")) {
                    return b;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Only a slim bar near a screen edge counts as the TV's volume bar. Anything else (a whole
     * System UI window with its margins, a notice) is ignored, so the capsule never jumps to a wrong,
     * oversized spot.
     */
    private boolean plausible(Rect r) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float w = r.width() / density, h = r.height() / density;
        boolean upright = h >= w && w >= 28 && w <= 100 && h >= 140 && h <= 420;
        boolean lying = w > h && h >= 28 && h <= 100 && w >= 140 && w <= 700;
        int edge = Math.round(140 * density);
        boolean nearEdge = r.left < edge || dm.widthPixels - r.right < edge || r.top < edge || dm.heightPixels - r.bottom < edge;
        return (upright || lying) && nearEdge;
    }

    private final Runnable seek = new Runnable() {
        @Override
        public void run() {
            Rect found = findSystemBar();
            if (found == null || !attached || !plausible(found)) return;
            String flat = found.flattenToString();
            SharedPreferences p = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (flat.equals(p.getString(KEY_RECT, null))) return;
            p.edit().putString(KEY_RECT, flat).apply();
            place(found, true);
        }
    };

    /** Puts the capsule over {@code r} with room to spare, upright or lying down to match it. */
    private void place(Rect r, boolean update) {
        int pad = dp(2);   // the same size as the TV's own bar (just covering its soft edge), not hugging the screen edge
        boolean upright = r.height() >= r.width();
        shape(upright);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width() + 2 * pad, r.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        bg.setCornerRadius(Math.min(lp.width, lp.height) / 2f);
        bg.setOrientation(upright ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT);
        if (update) {
            try {
                wm.updateViewLayout(panel, lp);
            } catch (Exception ignored) {
            }
        } else {
            wm.addView(panel, lp);
        }
    }

    private void show() {
        if (panel == null) build();
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0;
        float level = muted ? 0f : cur / (float) max;
        int target = muted ? -1 : cur;   // the same number the TV's own bar shows (its volume step)
        countTo(target);
        pct.setTextSize(19 * TextScale.factor(service));
        // Speaker with more sound waves as it gets louder.
        icon.setImageResource(muted ? R.drawable.ic_s_mute : level < 0.34f ? R.drawable.ic_vol_quiet
                : level < 0.67f ? R.drawable.ic_vol_low : R.drawable.ic_s_volume);
        bar.setMuted(muted);
        // During music the fill takes the song's colour; Night mode's limit shows as a small mark.
        bar.setAccent(NowPlaying.musicAccent);
        bar.setCap(Night.active(service) && Night.cap(service) > 0 ? Night.cap(service) / 100f : -1f);
        int a = NowPlaying.musicAccent;
        if (a != 0) {
            float[] h = new float[3];
            Color.colorToHSV(a, h);
            h[1] = Math.min(h[1], 0.5f);
            h[2] = 0.22f;
            bg.setColors(new int[]{Color.HSVToColor(h), 0xFF111116});
        } else {
            bg.setColors(new int[]{0xFF26262E, 0xFF111116});
        }

        if (!attached) {
            try {
                place(target(), false);
                attached = true;
            } catch (Exception ex) {
                return;
            }
            // The TV's own bar may appear a moment after the change: look for it and move over it.
            handler.removeCallbacks(seek);
            handler.postDelayed(seek, 150);
            handler.postDelayed(seek, 500);
            bar.setLevel(level, false);
            panel.setAlpha(0f);
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            bar.setLevel(level, true);
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_MS);
    }

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            panel.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(200).withEndAction(() -> {
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
        boolean vertical = true;

        Bar(Context ctx, float density) {
            super(ctx);
            this.density = density;
            track.setColor(0x2EFFFFFF);
            fill.setColor(0xF2FFFFFF);
            glow.setColor(0x33FFFFFF);
            mark.setColor(0xFFFFC46B);
        }

        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accent;
        private boolean muted;
        private float cap = -1f;

        void setMuted(boolean m) {
            muted = m;
            invalidate();
        }

        void setAccent(int a) {
            accent = a;
            invalidate();
        }

        void setCap(float c) {
            cap = c;
            invalidate();
        }

        /** White at the loud end, the song's colour (or soft white) at the quiet end. */
        private void shade(float x0, float y0, float x1, float y1) {
            int base = accent != 0 ? accent : 0xFFBFC6DA;
            int top = 0xFFFFFFFF;
            if (muted) {
                base = 0x66FFFFFF;
                top = 0x66FFFFFF;
            }
            fill.setShader(new android.graphics.LinearGradient(x0, y0, x1, y1, base, top,
                    android.graphics.Shader.TileMode.CLAMP));
            glow.setColor((accent != 0 ? accent : 0xFFFFFFFF) & 0x00FFFFFF | 0x38000000);
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
            float inset = 5 * density;   // room for the glow
            if (vertical) {
                float bw = w - 2 * inset, rad = bw / 2;
                r.set(inset, 0, w - inset, h);
                c.drawRoundRect(r, rad, rad, track);
                float top = h - Math.max(bw, h * level);
                if (level > 0f) {
                    shade(0, h, 0, top);
                    r.set(0, top - inset, w, h);
                    c.drawRoundRect(r, w / 2, w / 2, glow);
                    r.set(inset, top, w - inset, h);
                    c.drawRoundRect(r, rad, rad, fill);
                }
                if (cap > 0f) {
                    float y = h - h * cap;
                    c.drawRoundRect(0, y - density, w, y + density, density, density, mark);
                }
            } else {
                float bh = h - 2 * inset, rad = bh / 2;
                r.set(0, inset, w, h - inset);
                c.drawRoundRect(r, rad, rad, track);
                float end = Math.max(bh, w * level);
                if (level > 0f) {
                    shade(0, 0, end, 0);
                    r.set(0, 0, end + inset, h);
                    c.drawRoundRect(r, h / 2, h / 2, glow);
                    r.set(0, inset, end, h - inset);
                    c.drawRoundRect(r, rad, rad, fill);
                }
                if (cap > 0f) {
                    float x = w * cap;
                    c.drawRoundRect(x - density, 0, x + density, h, density, density, mark);
                }
            }
        }
    }
}
