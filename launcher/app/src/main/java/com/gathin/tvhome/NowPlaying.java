package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Now playing: when music or a video is playing (or paused) in another app, a glass card under the
 * top bar shows the cover, title and artist, with previous, play/pause and next. Reached with Down
 * from the top bar or Up from the dock.
 *
 * Android only shows other apps' media sessions to an app with notification access (MediaListener).
 * TV Home turns that on itself with the setup script's permission, or the script grants it.
 */
final class NowPlaying {

    private final Activity act;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private ComponentName listener;
    private MediaController current;

    private LinearLayout card;
    private android.graphics.drawable.GradientDrawable bg;
    private Equalizer eq;
    private Progress progress;
    private TextView time;
    private Bitmap lastCover;
    private boolean ticking;
    private ImageView art;
    private TextView title, subtitle;
    private ImageView playPause;

    NowPlaying(Activity act) {
        this.act = act;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Adds the (hidden) card to the home screen, just under the top bar on the right. */
    void attach(FrameLayout root, View topBar) {
        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        bg = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF22A2A2E, 0xF5161618});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(12), dp(12), dp(14), dp(12));
        card.setElevation(dp(8));
        card.setVisibility(View.GONE);

        art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        art.setClipToOutline(true);
        art.setElevation(dp(4));
        card.addView(art, new LinearLayout.LayoutParams(dp(68), dp(68)));

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(10), 0);
        title = label(17, 0xFFFFFFFF, 620);
        // Long titles scroll slowly instead of being cut off.
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setFadingEdgeLength(dp(18));
        title.setSelected(true);
        subtitle = label(14, 0xC7FFFFFF, 520);
        eq = new Equalizer(act, density);
        LinearLayout who = new LinearLayout(act);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(14), dp(12));
        elp.rightMargin = dp(7);
        who.addView(eq, elp);
        who.addView(subtitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new Progress(act, density);
        time = label(12, 0xB3FFFFFF, 520);
        LinearLayout prow = new LinearLayout(act);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.addView(progress, new LinearLayout.LayoutParams(0, dp(4), 1f));
        time.setPadding(dp(8), 0, 0, 0);
        prow.addView(time);
        text.addView(title);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(2);
        text.addView(who, wlp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        text.addView(prow, plp);
        card.addView(text, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView prev = button(R.drawable.ic_np_prev, () -> transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        playPause = button(R.drawable.ic_np_pause, () -> transport(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        ImageView next = button(R.drawable.ic_np_next, () -> transport(KeyEvent.KEYCODE_MEDIA_NEXT));
        card.addView(prev);
        card.addView(playPause);
        card.addView(next);
        // Left/Right stay inside the three buttons instead of jumping to the top bar or the dock.
        prev.setOnKeyListener((v, k, e) -> k == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN);
        next.setOnKeyListener((v, k, e) -> k == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(56);
        root.addView(card, lp);
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(root, topBar));
    }

    private void place(FrameLayout root, View topBar) {
        int[] a = new int[2], c = new int[2];
        topBar.getLocationInWindow(a);
        root.getLocationInWindow(c);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int top = a[1] - c[1] + topBar.getHeight() + dp(22);
        if (lp.topMargin != top) {
            lp.topMargin = top;
            card.setLayoutParams(lp);
        }
    }

    private TextView label(float sp, int color, int weight) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(color);
        try {
            t.setTypeface(act.getResources().getFont(R.font.outfit));
        } catch (Exception ignored) {
        }
        t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private ImageView button(int icon, Runnable action) {
        ImageView b = new ImageView(act);
        b.setImageResource(icon);
        b.setImageTintList(act.getColorStateList(R.color.menu_ink));
        b.setBackgroundResource(R.drawable.np_button_bg);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void transport(int keyCode) {
        if (current == null) return;
        MediaController.TransportControls tc = current.getTransportControls();
        PlaybackState st = current.getPlaybackState();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                tc.skipToPrevious();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                tc.skipToNext();
                break;
            default:
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                else tc.play();
        }
    }

    // ---- sessions ---------------------------------------------------------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> refresh();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            handler.post(NowPlaying.this::render);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            handler.post(NowPlaying.this::render);
        }

        @Override
        public void onSessionDestroyed() {
            handler.post(NowPlaying.this::refresh);
        }
    };

    /** Home is showing: follow the media sessions. */
    void start() {
        if (card == null) return;
        listener = new ComponentName(act, MediaListener.class);
        ensureAccess(act);
        msm = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
        } catch (SecurityException e) {
            msm = null;   // no notification access: no card
        }
        refresh();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    /** Elapsed / total time and the bar, from the session's position (it reports where and when). */
    private void updateProgress() {
        if (current == null) return;
        PlaybackState st = current.getPlaybackState();
        MediaMetadata md = current.getMetadata();
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (st == null || dur <= 0) {
            progress.setVisibility(View.INVISIBLE);
            time.setText("");
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(dur, pos));
        progress.setVisibility(View.VISIBLE);
        progress.set(pos / (float) dur);
        time.setText(fmt(pos) + " / " + fmt(dur));
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** The card takes on the cover's colour, fading into the dark glass. */
    private void tint(Bitmap cover) {
        int c = 0xFF2A2A2E;
        if (cover != null) {
            try {
                Bitmap small = Bitmap.createScaledBitmap(cover, 16, 16, true);
                float[] hsv = new float[3];
                float best = -1;
                int pick = 0;
                long r = 0, g = 0, b = 0;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int p = small.getPixel(x, y);
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                        android.graphics.Color.colorToHSV(p, hsv);
                        float score = hsv[1] * Math.min(1f, hsv[2] * 1.5f);
                        if (score > best) {
                            best = score;
                            pick = p;
                        }
                    }
                }
                if (small != cover) small.recycle();
                if (best < 0.2f) pick = android.graphics.Color.rgb((int) (r / 256), (int) (g / 256), (int) (b / 256));
                android.graphics.Color.colorToHSV(pick, hsv);
                hsv[1] = Math.min(hsv[1], 0.7f);
                hsv[2] = 0.36f;
                c = android.graphics.Color.HSVToColor(0xF2, hsv);
            } catch (Exception ignored) {
            }
        }
        bg.setColors(new int[]{c, 0xF5161618});
        progress.setColor(0xFFFFFFFF);
    }

    void stop() {
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (Exception ignored) {
            }
        }
        if (current != null) current.unregisterCallback(callback);
        current = null;
        ticking = false;
        handler.removeCallbacks(tick);
        if (eq != null) eq.setPlaying(false);
    }

    /** Three little bars that dance while playing. */
    private static final class Equalizer extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private boolean playing;

        Equalizer(Context c, float density) {
            super(c);
            this.density = density;
            p.setColor(0xFFFFFFFF);
        }

        void setPlaying(boolean on) {
            if (playing == on) return;
            playing = on;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), bw = w / 5f;
            long t = android.os.SystemClock.uptimeMillis();
            for (int i = 0; i < 3; i++) {
                float f = playing ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t / (170f + i * 60f) + i * 1.7f)) : 0.3f;
                float x = i * bw * 2;
                c.drawRoundRect(x, h * (1 - f), x + bw, h, bw / 2, bw / 2, p);
            }
            if (playing) postInvalidateDelayed(50);
        }
    }

    /** A thin rounded progress bar. */
    private static final class Progress extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float f;

        Progress(Context c, float density) {
            super(c);
            track.setColor(0x33FFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        void set(float fraction) {
            f = Math.max(0f, Math.min(1f, fraction));
            invalidate();
        }

        void setColor(int color) {
            fill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(0, 0, w, h, h / 2, h / 2, track);
            if (f > 0) c.drawRoundRect(0, 0, Math.max(h, w * f), h, h / 2, h / 2, fill);
        }
    }

    private void refresh() {
        MediaController pick = null;
        if (msm != null) {
            try {
                List<MediaController> list = msm.getActiveSessions(listener);
                for (MediaController c : list) {
                    if (c.getPackageName().equals(act.getPackageName())) continue;
                    PlaybackState st = c.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                        pick = c;
                        break;
                    }
                    if (pick == null && st != null && st.getState() == PlaybackState.STATE_PAUSED) pick = c;
                }
            } catch (SecurityException ignored) {
            }
        }
        if (current != null && (pick == null || !pick.getSessionToken().equals(current.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (pick != null && current == null) {
            current = pick;
            current.registerCallback(callback, handler);
        }
        render();
    }

    private void render() {
        MediaMetadata md = current != null ? current.getMetadata() : null;
        PlaybackState st = current != null ? current.getPlaybackState() : null;
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        CharSequence t = md != null ? md.getText(MediaMetadata.METADATA_KEY_TITLE) : null;
        boolean show = current != null && !TextUtils.isEmpty(t)
                && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING);
        if (!show) {
            ticking = false;
            handler.removeCallbacks(tick);
            eq.setPlaying(false);
            if (card.getVisibility() == View.VISIBLE) {
                if (card.hasFocus()) act.findViewById(R.id.btnSettings).requestFocus();
                card.animate().alpha(0f).setDuration(200).withEndAction(() -> card.setVisibility(View.GONE)).start();
            }
            return;
        }
        title.setText(t);
        CharSequence who = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(who)) who = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String app = appName(current.getPackageName());
        subtitle.setText(TextUtils.isEmpty(who) ? app : who + "  ·  " + app);
        Bitmap cover = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover == null) cover = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (cover != null) {
            art.setImageBitmap(cover);
        } else {
            art.setImageDrawable(appIcon(current.getPackageName()));
        }
        if (cover != lastCover) {
            lastCover = cover;
            tint(cover);
        }
        eq.setPlaying(state == PlaybackState.STATE_PLAYING);
        updateProgress();
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, 1000);
        }
        playPause.setImageResource(state == PlaybackState.STATE_PLAYING ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        if (card.getVisibility() != View.VISIBLE) {
            card.setAlpha(0f);
            card.setTranslationY(-dp(8));
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).translationY(0f).setDuration(260).start();
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = act.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return act.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** With the setup script's permission: give TV Home notification access (for media sessions). */
    static void ensureAccess(Context ctx) {
        if (!SelfHeal.canHeal(ctx)) return;
        try {
            String mine = new ComponentName(ctx, MediaListener.class).flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (cur != null && cur.contains(mine)) return;
            String next = cur == null || cur.isEmpty() ? mine : cur + ":" + mine;
            Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", next);
        } catch (SecurityException ignored) {
        }
    }
}
