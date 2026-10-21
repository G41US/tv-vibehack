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
    private Ticker ticker, subTicker;
    private ImageView playPause;
    private LinearLayout info, textCol;
    private int accent = 0xFFFFFFFF, glowColor = 0xFF000000;
    private String lastTitle = "";
    private android.animation.ValueAnimator breathe;
    private long lastInput = android.os.SystemClock.uptimeMillis();
    private boolean started;
    private Full full;
    /** The song the person closed the card on: it stays hidden until something else plays. */
    private String dismissed;

    static final String PREFS = "look", KEY_ON = "now_playing";

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    private String key() {
        if (current == null) return null;
        MediaMetadata md = current.getMetadata();
        CharSequence t = md != null ? md.getText(MediaMetadata.METADATA_KEY_TITLE) : null;
        return current.getPackageName() + "|" + t;
    }

    private void dismiss() {
        dismissed = key();
        render();
    }

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
                new int[]{0xA62A2A2E, 0x8C161618});   // see-through: the wallpaper shows under it
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(14), dp(8));
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
        info = new LinearLayout(act);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setFocusable(true);
        info.setClickable(true);
        info.setBackgroundResource(R.drawable.np_info_bg);
        info.setPadding(dp(4), dp(4), dp(4), dp(4));
        info.setOnClickListener(v -> openFull());   // OK on the song: the full-screen player
        info.setOnKeyListener((v, k, e) -> k == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN);
        info.addView(art, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout text = new LinearLayout(act);
        textCol = text;
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        title = label(17, 0xFFFFFFFF, 620);
        title.setEllipsize(null);
        TextView follow = label(17, 0xFFFFFFFF, 620);
        follow.setEllipsize(null);
        ticker = new Ticker(act, title, follow, density);   // long titles scroll round, like a music app
        subtitle = label(14, 0xC7FFFFFF, 520);
        subtitle.setEllipsize(null);
        TextView subFollow = label(14, 0xC7FFFFFF, 520);
        subFollow.setEllipsize(null);
        subTicker = new Ticker(act, subtitle, subFollow, density);   // long artist names scroll too
        eq = new Equalizer(act, density);
        LinearLayout who = new LinearLayout(act);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(14), dp(12));
        elp.rightMargin = dp(7);
        who.addView(eq, elp);
        who.addView(subTicker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new Progress(act, density, 4);
        time = label(12, 0xB3FFFFFF, 520);
        LinearLayout prow = new LinearLayout(act);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.addView(progress, new LinearLayout.LayoutParams(0, dp(14), 1f));
        time.setPadding(dp(8), 0, 0, 0);
        prow.addView(time);
        text.addView(ticker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(2);
        text.addView(who, wlp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(3);
        text.addView(prow, plp);
        info.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView prev = button(R.drawable.ic_np_prev, () -> transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        playPause = button(R.drawable.ic_np_pause, () -> transport(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        ImageView next = button(R.drawable.ic_np_next, () -> transport(KeyEvent.KEYCODE_MEDIA_NEXT));
        card.addView(prev);
        card.addView(playPause);
        card.addView(next);
        // ✕: hide the card until the next song (or another app) starts playing.
        ImageView close = button(R.drawable.ic_np_close, this::dismiss);
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(32), dp(32));
        xlp.leftMargin = dp(10);
        close.setLayoutParams(xlp);
        close.setAlpha(0.85f);
        card.addView(close);
        close.setOnKeyListener((v, k, e) -> k == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN);
        // Left/Right stay inside the three buttons instead of jumping to the top bar or the dock.
        prev.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_DPAD_LEFT || e.getAction() != KeyEvent.ACTION_DOWN) return false;
            info.requestFocus();
            return true;
        });

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
        int top = a[1] - c[1] + topBar.getHeight() + dp(16);
        // Exactly as wide as the top bar, from the left of Search to the right of Settings.
        int width = Math.max(topBar.getWidth(), dp(400));
        int right = root.getWidth() - (a[0] - c[0] + topBar.getWidth());
        if (lp.topMargin != top || lp.width != width || lp.rightMargin != right) {
            lp.topMargin = top;
            lp.width = width;
            lp.rightMargin = Math.max(0, right);
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
        t.setShadowLayer(6 * density, 0, density, 0x99000000);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.leftMargin = dp(5);
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
        started = true;
        noteInput();
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, 10_000);
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
        if (full != null) full.progress();
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** The card takes on the cover's colour, fading into the dark glass. */
    private void tint(Bitmap cover) {
        int c = 0xA62A2A2E;
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
                c = android.graphics.Color.HSVToColor(0xB8, hsv);
            } catch (Exception ignored) {
            }
        }
        bg.setColors(new int[]{c, 0x8C161618});
        float[] h = new float[3];
        android.graphics.Color.colorToHSV(c, h);
        accent = cover == null ? 0xFFFFFFFF : android.graphics.Color.HSVToColor(new float[]{h[0], Math.min(h[1], 0.45f), 1f});
        glowColor = cover == null ? 0xFF000000 : android.graphics.Color.HSVToColor(new float[]{h[0], Math.min(1f, h[1] + 0.2f), 0.9f});
        progress.setColor(accent);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            card.setOutlineSpotShadowColor(glowColor);
            card.setOutlineAmbientShadowColor(glowColor);
        }
        if (full != null) full.tint();
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
        if (ticker != null) ticker.setRunning(false);
        if (subTicker != null) subTicker.setRunning(false);
        setBreathing(false);
        started = false;
        handler.removeCallbacks(idleCheck);
    }

    /** While music plays, the card's coloured glow slowly swells and settles, like breathing. */
    private void setBreathing(boolean on) {
        if (!on) {
            if (breathe != null) breathe.cancel();
            breathe = null;
            if (card != null) card.setElevation(dp(8));
            return;
        }
        if (breathe != null) return;
        breathe = android.animation.ValueAnimator.ofFloat(dp(8), dp(22));
        breathe.setDuration(2600);
        breathe.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breathe.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(a -> card.setElevation((float) a.getAnimatedValue()));
        breathe.start();
    }

    // ---- full-screen player -----------------------------------------------------------------------

    /** Any key on Home (MainActivity): resets the wait before the player fills the screen by itself. */
    void noteInput() {
        lastInput = android.os.SystemClock.uptimeMillis();
    }

    private static final long AMBIENT_AFTER_MS = 60_000;

    /** Music playing and nobody touching the remote on Home for a minute: the full-screen player. */
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            PlaybackState st = current != null ? current.getPlaybackState() : null;
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            if (playing && full == null && act.hasWindowFocus() && card.getVisibility() == View.VISIBLE
                    && android.os.SystemClock.uptimeMillis() - lastInput > AMBIENT_AFTER_MS) {
                openFull();
            }
            handler.postDelayed(this, 10_000);
        }
    };

    void openFull() {
        if (current == null || full != null) return;
        full = new Full();
        full.show();
    }

    /**
     * The full-screen player: the cover blurred and drifting behind everything, the cover itself large
     * with a glow in its colour, the title and artist big, a progress bar, and the controls.
     */
    private final class Full {
        private android.app.Dialog d;
        private ImageView backdrop, cover;
        private TextView kicker, album, elapsed, total;
        private Ticker title, artist;
        private Progress bar;
        private Equalizer bigEq;
        private ImageView play;
        private android.animation.ValueAnimator drift, pulse;
        private Bitmap shown;

        void show() {
            d = new android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            FrameLayout root = new FrameLayout(act);
            root.setBackgroundColor(0xFF000000);
            backdrop = new ImageView(act);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            View shade = new View(act);
            shade.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x8C000000, 0x59000000, 0xA6000000}));
            root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            cover = new ImageView(act);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
                }
            });
            cover.setClipToOutline(true);
            cover.setElevation(dp(30));
            row.addView(cover, new LinearLayout.LayoutParams(dp(300), dp(300)));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(48), 0, 0, 0);
            LinearLayout k = new LinearLayout(act);
            k.setOrientation(LinearLayout.HORIZONTAL);
            k.setGravity(Gravity.CENTER_VERTICAL);
            bigEq = new Equalizer(act, density);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(18), dp(16));
            elp.rightMargin = dp(10);
            k.addView(bigEq, elp);
            kicker = label(13, 0xD9FFFFFF, 620);
            kicker.setAllCaps(true);
            kicker.setLetterSpacing(0.18f);
            k.addView(kicker);
            col.addView(k);

            TextView t1 = label(36, 0xFFFFFFFF, 700), t2 = label(36, 0xFFFFFFFF, 700);
            t1.setEllipsize(null);
            t2.setEllipsize(null);
            title = new Ticker(act, t1, t2, density);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(14);
            col.addView(title, tlp);
            TextView a1 = label(21, 0xE6FFFFFF, 560), a2 = label(21, 0xE6FFFFFF, 560);
            a1.setEllipsize(null);
            a2.setEllipsize(null);
            artist = new Ticker(act, a1, a2, density);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(4);
            col.addView(artist, alp);
            album = label(15, 0xA6FFFFFF, 520);
            album.setPadding(0, dp(4), 0, 0);
            col.addView(album);

            bar = new Progress(act, density, 6);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
            blp.topMargin = dp(30);
            col.addView(bar, blp);
            FrameLayout times = new FrameLayout(act);
            elapsed = label(13, 0xCCFFFFFF, 560);
            total = label(13, 0xCCFFFFFF, 560);
            times.addView(elapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
            times.addView(total, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(6);
            col.addView(times, mlp);

            LinearLayout controls = new LinearLayout(act);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            ImageView prev = bigButton(R.drawable.ic_np_prev, 56, () -> transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
            play = bigButton(R.drawable.ic_np_pause, 72, () -> transport(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            ImageView next = bigButton(R.drawable.ic_np_next, 56, () -> transport(KeyEvent.KEYCODE_MEDIA_NEXT));
            controls.addView(prev);
            controls.addView(play);
            controls.addView(next);
            prev.setOnKeyListener((v, kc, e) -> kc == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN);
            next.setOnKeyListener((v, kc, e) -> kc == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(26);
            col.addView(controls, clp);

            row.addView(col, new LinearLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            d.setContentView(root);
            Sounds.attach(d);
            d.setOnDismissListener(x -> {
                if (drift != null) drift.cancel();
                if (pulse != null) pulse.cancel();
                title.setRunning(false);
                artist.setRunning(false);
                bigEq.setPlaying(false);
                full = null;
                noteInput();
            });
            drift = LiveWallpaper.drift(backdrop, density);
            backdrop.setScaleX(1.25f);
            backdrop.setScaleY(1.25f);
            drift.start();
            root.setAlpha(0f);
            d.setOnShowListener(x -> {
                play.requestFocus();
                root.animate().alpha(1f).setDuration(350).start();
            });
            d.show();
            update(true);
        }

        private ImageView bigButton(int icon, int size, Runnable action) {
            ImageView b = new ImageView(act);
            b.setImageResource(icon);
            b.setImageTintList(act.getColorStateList(R.color.menu_ink));
            b.setBackgroundResource(R.drawable.np_button_bg);
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = dp(size * 0.3f);
            b.setPadding(pad, pad, pad, pad);
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            lp.rightMargin = dp(16);
            b.setLayoutParams(lp);
            return b;
        }

        void tint() {
            if (d == null) return;
            bar.setColor(accent);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                cover.setOutlineSpotShadowColor(glowColor);
                cover.setOutlineAmbientShadowColor(glowColor);
            }
        }

        /** Refreshes from the session; {@code all} also redoes the pictures. */
        void update(boolean all) {
            if (d == null || current == null) return;
            MediaMetadata md = current.getMetadata();
            PlaybackState st = current.getPlaybackState();
            if (md == null) return;
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            title.setText(String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE)));
            title.setRunning(true);
            CharSequence who = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(who)) who = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            artist.setText(TextUtils.isEmpty(who) ? " " : who);
            artist.setRunning(true);
            CharSequence alb = md.getText(MediaMetadata.METADATA_KEY_ALBUM);
            album.setText(TextUtils.isEmpty(alb) ? "" : alb);
            kicker.setText(appName(current.getPackageName()) + "  ·  " + act.getString(R.string.np_now_playing));
            bigEq.setPlaying(playing);
            play.setImageResource(playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
            Bitmap c = coverOf(md);
            if (all || c != shown) {
                shown = c;
                if (c != null) {
                    cover.setImageBitmap(c);
                    backdrop.setImageBitmap(Bitmap.createScaledBitmap(c, 48, 48, true));
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                                dp(24), dp(24), android.graphics.Shader.TileMode.CLAMP));
                    }
                } else {
                    cover.setImageDrawable(appIcon(current.getPackageName()));
                    backdrop.setImageDrawable(null);
                }
                tint();
            }
            setPulse(playing);
            progress();
        }

        /** The big cover gently breathes while the music plays. */
        private void setPulse(boolean on) {
            if (!on) {
                if (pulse != null) pulse.cancel();
                pulse = null;
                cover.animate().scaleX(0.96f).scaleY(0.96f).setDuration(400).start();
                return;
            }
            if (pulse != null) return;
            cover.animate().cancel();
            pulse = android.animation.ValueAnimator.ofFloat(1f, 1.025f);
            pulse.setDuration(2600);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            pulse.addUpdateListener(a -> {
                float v = (float) a.getAnimatedValue();
                cover.setScaleX(v);
                cover.setScaleY(v);
            });
            pulse.start();
        }

        void progress() {
            if (d == null || current == null) return;
            long[] pd = position();
            if (pd == null) {
                bar.setVisibility(View.INVISIBLE);
                elapsed.setText("");
                total.setText("");
                return;
            }
            bar.setVisibility(View.VISIBLE);
            bar.set(pd[0] / (float) pd[1]);
            elapsed.setText(fmt(pd[0]));
            total.setText("-" + fmt(pd[1] - pd[0]));
        }
    }

    private static Bitmap coverOf(MediaMetadata md) {
        Bitmap c = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (c == null) c = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (c == null) c = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return c;
    }

    /** {position, duration} in ms now, or null without a duration. */
    private long[] position() {
        PlaybackState st = current.getPlaybackState();
        MediaMetadata md = current.getMetadata();
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (st == null || dur <= 0) return null;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        return new long[]{Math.max(0, Math.min(dur, pos)), dur};
    }

    /**
     * A title that doesn't fit scrolls round like a music app: it rests, scrolls left with a copy
     * following a gap behind, and when the copy reaches the start it rests again, then carries on.
     * Setting the same title again (sessions report often) doesn't restart it.
     */
    private static final class Ticker extends android.widget.HorizontalScrollView {
        private final TextView text, copy;
        private final float density;
        private final int gap;
        private android.animation.Animator anim;
        private boolean running;

        Ticker(Context c, TextView text, TextView copy, float density) {
            super(c);
            this.text = text;
            this.copy = copy;
            this.density = density;
            this.gap = (int) (56 * density);
            setHorizontalScrollBarEnabled(false);
            setFocusable(false);
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength((int) (20 * density));
            setOverScrollMode(OVER_SCROLL_NEVER);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = gap;
            copy.setVisibility(GONE);
            row.addView(copy, clp);
            addView(row, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setText(CharSequence t) {
            if (t.toString().contentEquals(text.getText())) return;
            text.setText(t);
            copy.setText(t);
            restart();
        }

        void setRunning(boolean on) {
            if (running == on) return;
            running = on;
            restart();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            restart();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        private void restart() {
            if (anim != null) {
                anim.removeAllListeners();
                anim.cancel();
                anim = null;
            }
            setScrollX(0);
            copy.setVisibility(GONE);
            if (!running) return;
            // Measure the title alone first; only a title that doesn't fit gets the following copy.
            post(() -> {
                if (!running || anim != null) return;
                if (text.getWidth() <= getWidth()) return;
                copy.setVisibility(VISIBLE);
                post(this::loop);
            });
        }

        private void loop() {
            if (!running || anim != null || copy.getVisibility() != VISIBLE) return;
            int distance = text.getWidth() + gap;   // the copy then sits exactly where the title began
            android.animation.ObjectAnimator go = android.animation.ObjectAnimator.ofInt(this, "scrollX", 0, distance);
            go.setStartDelay(2500);
            go.setDuration(Math.max(1000, (long) (distance / (34 * density) * 1000)));   // 34 dp a second
            go.setInterpolator(new android.view.animation.LinearInterpolator());
            go.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (anim != a) return;
                    anim = null;
                    setScrollX(0);   // seamless: the copy was showing exactly this
                    loop();
                }
            });
            anim = go;
            go.start();
        }
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
        private final float thick;

        Progress(Context c, float density, float thickDp) {
            super(c);
            thick = thickDp * density;
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
            float w = getWidth(), cy = getHeight() / 2f, t = thick, head = t * 1.6f;
            c.drawRoundRect(head, cy - t / 2, w - head, cy + t / 2, t / 2, t / 2, track);
            if (f <= 0) return;
            float x = head + Math.max(t, (w - 2 * head) * f);
            c.drawRoundRect(head, cy - t / 2, x, cy + t / 2, t / 2, t / 2, fill);
            // A small glowing head where the song is now.
            int a = fill.getAlpha();
            fill.setAlpha(70);
            c.drawCircle(x, cy, head, fill);
            fill.setAlpha(a);
            c.drawCircle(x, cy, t * 0.95f, fill);
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
                || state == PlaybackState.STATE_BUFFERING)
                && enabled(act) && !String.valueOf(key()).equals(dismissed);
        if (!show) {
            if (full != null && full.d != null) full.d.dismiss();
            setBreathing(false);
            lastTitle = "";
            if (subTicker != null) subTicker.setRunning(false);
            if (ticker != null) ticker.setRunning(false);
            ticking = false;
            handler.removeCallbacks(tick);
            eq.setPlaying(false);
            if (card.getVisibility() == View.VISIBLE) {
                if (card.hasFocus()) act.findViewById(R.id.btnSettings).requestFocus();
                card.animate().alpha(0f).setDuration(200).withEndAction(() -> card.setVisibility(View.GONE)).start();
            }
            return;
        }
        CharSequence who = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(who)) who = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String app = appName(current.getPackageName());
        final CharSequence line = TextUtils.isEmpty(who) ? app : who + "  ·  " + app;
        final CharSequence song = t;
        Bitmap cover = coverOf(md);
        final Bitmap pic = cover;
        Runnable apply = () -> {
            ticker.setText(song);
            ticker.setRunning(true);
            subTicker.setText(line);
            subTicker.setRunning(true);
            if (pic != null) art.setImageBitmap(pic);
            else art.setImageDrawable(appIcon(current != null ? current.getPackageName() : act.getPackageName()));
        };
        boolean changed = !song.toString().equals(lastTitle);
        lastTitle = song.toString();
        if (changed && card.getVisibility() == View.VISIBLE) {
            // New song: the old one slides out to the left, the new one slides in from the right.
            textCol.animate().cancel();
            textCol.animate().alpha(0f).translationX(-dp(14)).setDuration(160).withEndAction(() -> {
                apply.run();
                textCol.setTranslationX(dp(14));
                textCol.animate().alpha(1f).translationX(0f).setDuration(260).start();
            }).start();
            art.animate().cancel();
            art.animate().alpha(0f).setDuration(160).withEndAction(() ->
                    art.animate().alpha(1f).setDuration(300).start()).start();
        } else {
            apply.run();
        }
        if (cover != lastCover) {
            lastCover = cover;
            tint(cover);
        }
        eq.setPlaying(state == PlaybackState.STATE_PLAYING);
        setBreathing(state == PlaybackState.STATE_PLAYING);
        if (full != null) full.update(false);
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
