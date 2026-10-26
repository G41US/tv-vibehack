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
    private TextView time, timeLeft;
    private Bitmap lastCover;
    private boolean ticking;
    private ImageView art;
    private TextView title, subtitle;
    private Ticker ticker, subTicker;
    private ImageView playPause;
    private LinearLayout info, textCol;
    private int accent = 0xFFFFFFFF, glowColor = 0xFF000000, secondColor = 0;

    /** The playing song's colour for the volume capsule (VolumeOverlay); 0 with no music. */
    static volatile int musicAccent;

    /** Album colour lighting (MoodLight on Home): the cover's two colours, and whether music plays. */
    interface Mood {
        void on(int a, int b, boolean playing);
    }

    private Mood mood;

    void setMood(Mood m) {
        mood = m;
    }
    private String lastTitle = "";
    private android.animation.ValueAnimator breathe;
    private long lastInput = android.os.SystemClock.uptimeMillis();
    private boolean started;
    private Full full;

    // Small mode: the card shrinks to a little pill (cover and bars); OK on it opens the card again.
    static final String PREFS = "look", KEY_MINI = "now_playing_small";
    private LinearLayout mini;
    private ImageView shrink;
    private static final int NEXT_ID = 0x7f0a7001;
    private ImageView miniArt;
    private Equalizer miniEq;
    private android.graphics.drawable.GradientDrawable miniBg;

    /** Out of the way while the app rows below the dock are browsed (they scroll up under it). */
    private boolean suppressed;

    void setSuppressed(boolean on) {
        if (suppressed == on) return;
        suppressed = on;
        if (card == null) return;
        if (on) {
            for (View v : new View[]{card, mini, shrink}) {
                if (v.getVisibility() != View.VISIBLE) continue;
                v.animate().cancel();
                v.animate().alpha(0f).setDuration(180).withEndAction(() -> v.setVisibility(View.GONE)).start();
            }
        } else {
            render();
        }
    }

    boolean small() {
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MINI, false);
    }

    /** Settings changed elsewhere (TV Home > Music): redraw the card. */
    void refreshView() {
        if (card != null) render();
    }

    void setSmall(boolean on) {
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MINI, on).apply();
        render();
        (on ? mini : playPause).requestFocus();
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
        // A fresh start of the box: the card starts small. (Checked here, before the first draw; the
        // system's "booted" message comes too late and made the card shrink a few seconds in.)
        long bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        android.content.SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (Math.abs(bootAt - p.getLong("boot_at", 0)) > 60_000) {
            p.edit().putLong("boot_at", bootAt).putBoolean(KEY_MINI, true).apply();
        }
        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        bg = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xA62A2A2E, 0x8C161618});   // see-through: the wallpaper shows under it
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(10), dp(8));
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
        info.addView(art, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout text = new LinearLayout(act);
        textCol = text;
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10), 0, dp(6), 0);
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
        // The bar uses the whole width; the times sit under it, played on the left, length on the right.
        time = label(11, 0xB3FFFFFF, 540);
        timeLeft = label(11, 0xB3FFFFFF, 540);
        LinearLayout prow = new LinearLayout(act);
        prow.setOrientation(LinearLayout.VERTICAL);
        prow.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        FrameLayout times = new FrameLayout(act);
        times.addView(timeLeft, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        times.addView(time, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        times.setPadding(dp(5), 0, dp(5), 0);
        prow.addView(times);
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
        next.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) shrink.requestFocus();   // Right from ⏭: the shrink button
            return true;
        });
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

        // The shrink button sits on the card's top-right corner, so the song keeps the card's full width.
        shrink = new ImageView(act);
        shrink.setImageResource(R.drawable.ic_np_small);
        shrink.setImageTintList(act.getColorStateList(R.color.menu_ink));
        shrink.setBackgroundResource(R.drawable.np_corner_bg);
        shrink.setScaleType(ImageView.ScaleType.CENTER);
        shrink.setFocusable(true);
        shrink.setClickable(true);
        shrink.setElevation(dp(24));
        shrink.setVisibility(View.GONE);
        shrink.setOnClickListener(v -> setSmall(true));
        shrink.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                card.findViewById(NEXT_ID).requestFocus();
                return true;
            }
            return k == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        next.setId(NEXT_ID);
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.END);
        root.addView(shrink, xlp);

        mini = new LinearLayout(act);
        mini.setOrientation(LinearLayout.HORIZONTAL);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        miniBg = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xA62A2A2E, 0x8C161618});
        miniBg.setCornerRadius(dp(26));
        miniBg.setStroke(dp(1), 0x4DFFFFFF);
        android.graphics.drawable.StateListDrawable miniSel = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setCornerRadius(dp(26));
        ring.setColor(0x33FFFFFF);
        ring.setStroke(dp(2), 0xE6FFFFFF);
        miniSel.addState(new int[]{android.R.attr.state_focused}, ring);
        miniSel.addState(new int[]{}, miniBg);
        mini.setBackground(miniSel);
        mini.setPadding(dp(6), dp(6), dp(16), dp(6));
        mini.setFocusable(true);
        mini.setClickable(true);
        mini.setElevation(dp(8));
        mini.setVisibility(View.GONE);
        mini.setOnClickListener(v -> setSmall(false));
        miniArt = new ImageView(act);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(20));
            }
        });
        miniArt.setClipToOutline(true);
        mini.addView(miniArt, new LinearLayout.LayoutParams(dp(40), dp(40)));
        miniEq = new Equalizer(act, density);
        LinearLayout.LayoutParams mel = new LinearLayout.LayoutParams(dp(16), dp(14));
        mel.leftMargin = dp(12);
        mini.addView(miniEq, mel);
        ImageView grow = new ImageView(act);
        grow.setImageResource(R.drawable.ic_np_big);
        grow.setImageTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(16), dp(16));
        gl.leftMargin = dp(12);
        mini.addView(grow, gl);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        mlp.rightMargin = dp(56);
        root.addView(mini, mlp);
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(root, topBar));
    }

    private void place(FrameLayout root, View topBar) {
        int[] a = new int[2], c = new int[2];
        topBar.getLocationInWindow(a);
        root.getLocationInWindow(c);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int top = a[1] - c[1] + topBar.getHeight() + dp(16);
        // Exactly as wide as the top bar, from the left of Search to the right of Settings.
        int width = Math.max(topBar.getWidth(), dp(420));   // room for the song even with a short top bar
        int right = root.getWidth() - (a[0] - c[0] + topBar.getWidth());
        if (lp.topMargin != top || lp.width != width || lp.rightMargin != right) {
            lp.topMargin = top;
            lp.width = width;
            lp.rightMargin = Math.max(0, right);
            card.setLayoutParams(lp);
        }
        FrameLayout.LayoutParams xl = (FrameLayout.LayoutParams) shrink.getLayoutParams();
        int xt = top - dp(11), xr = Math.max(0, right - dp(11));
        if (xl.topMargin != xt || xl.rightMargin != xr) {
            xl.topMargin = xt;
            xl.rightMargin = xr;
            shrink.setLayoutParams(xl);
        }
        FrameLayout.LayoutParams ml = (FrameLayout.LayoutParams) mini.getLayoutParams();
        if (ml.topMargin != top || ml.rightMargin != Math.max(0, right)) {
            ml.topMargin = top;
            ml.rightMargin = Math.max(0, right);
            mini.setLayoutParams(ml);
        }
    }

    private TextView label(float sp, int color, int weight) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.weight(act, weight));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private void transport(int keyCode) {
        if (current == null) {
            // The last song from before a restart: wake its app's player (or open the app).
            String pkg = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_pkg", null);
            if (pkg == null || keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return;
            wake(pkg);
            return;
        }
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
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    tc.pause();
                    return;
                }
                // After the box restarts, the app's session is only a leftover: play can stop it
                // instead. Keep the card while it wakes; if it hasn't started, wake the app's player the
                // way Android's own "resume" does; as a last resort open the app.
                // Both at once: whichever wakes the player first wins (a second play is harmless).
                tc.play();
                wake(current.getPackageName());
        }
    }

    /**
     * Wakes the app's player for its last song; keeps the card meanwhile. Only if nothing at all is
     * playing after 15 seconds does it open the app.
     */
    private void wake(String pkg) {
        holdUntil = android.os.SystemClock.uptimeMillis() + 15_000;
        // 1. A Play key, as a headset's would be: Android hands it to the last music app (it remembers
        //    which across restarts), which starts in the background without opening.
        android.media.AudioManager am = (android.media.AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        long t = android.os.SystemClock.uptimeMillis();
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        } catch (Exception ignored) {
        }
        // 2. Then connect to the app's player so the card can follow it (and play it, if 1 didn't).
        handler.postDelayed(() -> {
            if (!musicPlaying(pkg)) mediaButton(pkg);
            resume(pkg);
        }, 1200);
        handler.postDelayed(this::refresh, 3000);
        handler.postDelayed(this::render, 15_100);
        // Never opens the app on its own: it stays in the background until the person opens it.
    }

    /** The Play key sent straight to the app's own media-button receiver. */
    private void mediaButton(String pkg) {
        long t = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            try {
                act.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                        .putExtra(android.content.Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, KeyEvent.KEYCODE_MEDIA_PLAY, 0)));
            } catch (Exception ignored) {
            }
        }
    }

    /** Is music actually coming out? (Any app's sound, or that app's session playing.) */
    private boolean musicPlaying(String pkg) {
        android.media.AudioManager am = (android.media.AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) return true;
        if (playingNow()) return true;
        try {
            for (MediaController c : msm.getActiveSessions(listener)) {
                PlaybackState st = c.getPlaybackState();
                if (c.getPackageName().equals(pkg) && st != null && (st.getState() == PlaybackState.STATE_PLAYING
                        || st.getState() == PlaybackState.STATE_BUFFERING)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Until then a play is on its way: the card stays even if the session blinks out. */
    private long holdUntil;

    private boolean playingNow() {
        PlaybackState st = current != null ? current.getPlaybackState() : null;
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    /** Media resumption: connect to the app's media browser asking for its recent media, then play. */
    private void resume(String pkg) {
        try {
            List<android.content.pm.ResolveInfo> svc = act.getPackageManager().queryIntentServices(
                    new android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
            if (svc.isEmpty()) return;
            ComponentName c = new ComponentName(pkg, svc.get(0).serviceInfo.name);
            android.os.Bundle hints = new android.os.Bundle();
            hints.putBoolean("android.service.media.extra.RECENT", true);
            if (browser != null) browser.disconnect();
            final android.media.browse.MediaBrowser[] b = new android.media.browse.MediaBrowser[1];
            b[0] = new android.media.browse.MediaBrowser(act, c, new android.media.browse.MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    try {
                        // A session woken this way isn't always in Android's list of active sessions
                        // (Spotify's isn't until its app opens), so the card follows it directly.
                        MediaController mc = new MediaController(act, b[0].getSessionToken());
                        android.media.AudioManager am = (android.media.AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
                        if (am == null || !am.isMusicActive()) mc.getTransportControls().play();
                        adopted = mc.getSessionToken();
                        if (current != null) current.unregisterCallback(callback);
                        current = mc;
                        current.registerCallback(callback, handler);
                        render();
                    } catch (Exception ignored) {
                    }
                }
            }, hints);
            browser = b[0];   // stays connected: the card keeps following the music
            b[0].connect();
        } catch (Exception ignored) {
        }
    }

    private android.media.browse.MediaBrowser browser;
    private android.media.session.MediaSession.Token adopted;


    /** The TV went off or the box restarted: the card comes back small (the person can open it again). */
    static void startSmall(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MINI, true).apply();
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
            timeLeft.setText("");
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
        timeLeft.setText(fmt(pos));
        time.setText(fmt(dur));
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
        secondColor = 0;
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
                int avg = android.graphics.Color.rgb((int) (r / 256), (int) (g / 256), (int) (b / 256));
                float[] h2 = new float[3];
                android.graphics.Color.colorToHSV(avg, h2);
                h2[1] = Math.min(0.85f, h2[1] * 1.3f + 0.1f);
                h2[2] = 0.85f;
                secondColor = android.graphics.Color.HSVToColor(h2);
                if (best < 0.2f) pick = avg;
                android.graphics.Color.colorToHSV(pick, hsv);
                hsv[1] = Math.min(hsv[1], 0.7f);
                hsv[2] = 0.36f;
                c = android.graphics.Color.HSVToColor(0xB8, hsv);
            } catch (Exception ignored) {
            }
        }
        bg.setColors(new int[]{c, 0x8C161618});
        miniBg.setColors(new int[]{c, 0x8C161618});
        float[] h = new float[3];
        android.graphics.Color.colorToHSV(c, h);
        accent = cover == null ? 0xFFFFFFFF : android.graphics.Color.HSVToColor(new float[]{h[0], Math.min(h[1], 0.45f), 1f});
        musicAccent = cover == null ? 0 : android.graphics.Color.HSVToColor(new float[]{h[0], Math.min(1f, h[1] + 0.25f), 1f});
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
            if (playing && full == null && !small() && HomeSettings.autoPlayerOn(act) && act.hasWindowFocus()
                    && android.os.SystemClock.uptimeMillis() - lastInput > AMBIENT_AFTER_MS) {
                openFull();
            }
            handler.postDelayed(this, 10_000);
        }
    };

    /** Home pressed: close the full-screen player. */
    void closeFull() {
        if (full != null && full.d != null) full.d.dismiss();
    }

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
        private TextView hint;
        private android.animation.ValueAnimator drift, pulse;
        /** Where a seek just sent the song, shown until the app reports it (sessions lag a moment). */
        private long seekTarget = -1, seekUntil;

        /** Jumps by {@code delta} ms (repeats add up while the button is held). */
        void seek(long delta) {
            if (current == null) return;
            long[] pd = position();
            if (pd == null) return;
            long from = seekTarget >= 0 && android.os.SystemClock.uptimeMillis() < seekUntil ? seekTarget : pd[0];
            long to = Math.max(0, Math.min(pd[1] - 1000, from + delta));
            PlaybackState st = current.getPlaybackState();
            if (st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                current.getTransportControls().seekTo(to);
            } else if (delta > 0) {
                current.getTransportControls().fastForward();
            } else {
                current.getTransportControls().rewind();
            }
            seekTarget = to;
            seekUntil = android.os.SystemClock.uptimeMillis() + 1500;
            hint.setText(delta > 0 ? "+10s" : "−10s");
            hint.setAlpha(1f);
            handler.removeCallbacks(resetHint);
            handler.postDelayed(resetHint, 1200);
            show(to, pd[1]);
        }

        private final Runnable resetHint = () -> {
            if (hint != null) hint.setText(act.getString(R.string.np_seek_hint));
        };

        private void show(long pos, long dur) {
            bar.set(pos / (float) dur);
            elapsed.setText(fmt(pos));
            total.setText("-" + fmt(dur - pos));
        }
        private Bitmap shown;
        private String fullTitle;
        private ImageView smallCover;

        // ---- lyrics: in the cover's place, the current line lit, scrolling with the song ----
        private android.widget.ScrollView lyricsScroll;
        private LinearLayout lyricsLines;
        private TextView lyricsStatus;
        private Lyrics.Song song;
        private String songKey = "";
        private boolean searching;
        private int lit = -2;

        private void findLyrics(MediaMetadata md) {
            String t = String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE));
            CharSequence who = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            String a = who == null ? "" : who.toString();
            String key = t + "|" + a;
            if (key.equals(songKey)) return;
            songKey = key;
            song = null;
            if (!HomeSettings.lyricsOn(act)) {   // switched off in TV Home > Music
                searching = false;
                showLyricsOrCover();
                return;
            }
            searching = true;
            lit = -2;
            showLyricsOrCover();
            CharSequence alb = md.getText(MediaMetadata.METADATA_KEY_ALBUM);
            Lyrics.find(t, a, alb == null ? "" : alb.toString(), md.getLong(MediaMetadata.METADATA_KEY_DURATION), s -> {
                if (!key.equals(songKey) || d == null) return;
                searching = false;
                song = s;
                buildLines();
                showLyricsOrCover();
            });
        }

        private void buildLines() {
            lyricsLines.removeAllViews();
            if (song == null) return;
            for (String line : song.lines) {
                TextView t = label(23, 0xFFFFFFFF, 700);
                t.setSingleLine(false);
                t.setMaxLines(3);
                t.setEllipsize(null);
                t.setText(line.isEmpty() ? " " : line);
                t.setAlpha(song.synced ? 0.32f : 0.85f);
                t.setPivotX(0);
                t.setPadding(0, dp(8), dp(24), dp(8));
                t.setShadowLayer(0, 0, 0, 0);   // shadows on every line cost frames; only the lit one glows
                lyricsLines.addView(t);
            }
            lyricsLines.setPadding(0, dp(130), 0, dp(200));
        }

        private void showLyricsOrCover() {
            // Lyrics whenever the song has them (the big cover moves beside the name); otherwise the cover.
            boolean want = song != null;
            boolean was = lyricsScroll.getVisibility() == View.VISIBLE;
            lyricsScroll.setVisibility(want ? View.VISIBLE : View.GONE);
            cover.setVisibility(want ? View.GONE : View.VISIBLE);
            if (want != was) {
                View in = want ? lyricsScroll : cover;
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(260).start();
            }
            if (want != (smallCover.getVisibility() == View.VISIBLE)) {
                smallCover.setVisibility(want ? View.VISIBLE : View.GONE);
                if (want) {
                    smallCover.setAlpha(0f);
                    smallCover.setScaleX(0.85f);
                    smallCover.setScaleY(0.85f);
                    smallCover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start();
                }
            }
            lyricsStatus.setVisibility(View.GONE);
            lyricsLines.setVisibility(song != null ? View.VISIBLE : View.GONE);
            if (want && song != null) followLyrics();
        }

        private final Runnable lyricsTick = new Runnable() {
            @Override
            public void run() {
                if (d == null) return;
                followLyrics();
                handler.postDelayed(this, 250);
            }
        };

        /** Lights the line being sung and keeps it in the upper third. */
        private void followLyrics() {
            if (song == null || lyricsScroll.getVisibility() != View.VISIBLE || current == null) return;
            long[] pd = position();
            if (pd == null) return;
            int n = song.lines.size();
            int idx;
            if (song.synced) {
                long pos = seekTarget >= 0 && android.os.SystemClock.uptimeMillis() < seekUntil ? seekTarget : pd[0];
                idx = song.lineAt(pos + 250);   // a hair early, as music apps do
            } else {
                idx = (int) (pd[0] * n / Math.max(1, pd[1]));
            }
            idx = Math.max(-1, Math.min(n - 1, idx));
            if (idx == lit) return;
            lit = idx;
            for (int i = 0; i < lyricsLines.getChildCount(); i++) {
                View v = lyricsLines.getChildAt(i);
                boolean on = i == idx;
                if (song.synced) {
                    // The sung line lights up with a soft glow in the song's colour; sung lines fade back.
                    v.animate().alpha(on ? 1f : (i < idx ? 0.24f : 0.42f)).setDuration(320).start();
                    if (on) ((TextView) v).setShadowLayer(16 * density, 0, 0, (glowColor & 0x00FFFFFF) | 0xCC000000);
                    else ((TextView) v).setShadowLayer(0, 0, 0, 0);
                }
            }
            View target = lyricsLines.getChildAt(Math.max(0, idx));
            if (target != null) {
                lyricsScroll.smoothScrollTo(0, Math.max(0, target.getTop() - lyricsScroll.getHeight() / 3));
            }
        }

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
            FrameLayout left = new FrameLayout(act);
            left.addView(cover, new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER_VERTICAL));
            lyricsScroll = new android.widget.ScrollView(act);
            lyricsScroll.setVerticalScrollBarEnabled(false);
            lyricsScroll.setFocusable(false);
            lyricsScroll.setVerticalFadingEdgeEnabled(true);
            lyricsScroll.setFadingEdgeLength(dp(70));
            FrameLayout lyricsBox = new FrameLayout(act);
            lyricsLines = new LinearLayout(act);
            lyricsLines.setOrientation(LinearLayout.VERTICAL);
            lyricsBox.addView(lyricsLines, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            lyricsStatus = label(18, 0xB3FFFFFF, 560);
            lyricsStatus.setText(act.getString(R.string.np_finding_lyrics));
            lyricsStatus.setPadding(0, dp(150), 0, 0);
            lyricsBox.addView(lyricsStatus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            lyricsScroll.addView(lyricsBox);
            lyricsScroll.setVisibility(View.GONE);
            left.addView(lyricsScroll, new FrameLayout.LayoutParams(dp(400), dp(380), Gravity.CENTER_VERTICAL));
            row.addView(left, new LinearLayout.LayoutParams(dp(400), dp(380)));

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
            // Title block: when the lyrics take the big cover's place, a small cover sits beside the name.
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            smallCover = new ImageView(act);
            smallCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            smallCover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
                }
            });
            smallCover.setClipToOutline(true);
            smallCover.setElevation(dp(16));
            smallCover.setVisibility(View.GONE);
            LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(dp(104), dp(104));
            sclp.rightMargin = dp(20);
            head.addView(smallCover, sclp);
            LinearLayout texts = new LinearLayout(act);
            texts.setOrientation(LinearLayout.VERTICAL);
            head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(14);
            col.addView(head, hlp);
            texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView a1 = label(21, 0xE6FFFFFF, 560), a2 = label(21, 0xE6FFFFFF, 560);
            a1.setEllipsize(null);
            a2.setEllipsize(null);
            artist = new Ticker(act, a1, a2, density);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(4);
            texts.addView(artist, alp);
            album = label(15, 0xA6FFFFFF, 520);
            album.setPadding(0, dp(4), 0, 0);
            texts.addView(album);

            bar = new Progress(act, density, 6);
            // Up from the buttons: the bar. Left / Right jump 10 seconds back / forward.
            bar.setFocusable(true);
            bar.setOnKeyListener((v, kc, e) -> {
                if (kc != KeyEvent.KEYCODE_DPAD_LEFT && kc != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) seek(kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 10_000 : -10_000);
                return true;
            });
            bar.setOnFocusChangeListener((v, has) -> hint.animate().alpha(has ? 1f : 0f).setDuration(200).start());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
            blp.topMargin = dp(30);
            col.addView(bar, blp);
            FrameLayout times = new FrameLayout(act);
            elapsed = label(13, 0xCCFFFFFF, 560);
            total = label(13, 0xCCFFFFFF, 560);
            times.addView(elapsed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
            times.addView(total, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
            hint = label(13, 0xFFFFFFFF, 620);
            hint.setText(act.getString(R.string.np_seek_hint));
            hint.setAlpha(0f);
            times.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
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
            next.setOnKeyListener((v, kc, e) -> kc == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN);
            prev.setOnKeyListener((v, kc, e) -> kc == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN);

            // Centred under the progress bar, evenly spaced.
            controls.setGravity(Gravity.CENTER);
            for (View b : new View[]{prev, play, next}) {
                LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams) b.getLayoutParams();
                bl.leftMargin = dp(12);
                bl.rightMargin = dp(12);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(28);
            col.addView(controls, clp);

            // A small clock in the corner, for when the player stays up as the room's music screen.
            android.widget.TextClock clock = new android.widget.TextClock(act);
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(0xD9FFFFFF);
            clock.setTextSize(22);
            clock.setTypeface(Fonts.weight(act, 560));
            clock.setShadowLayer(8 * density, 0, density, 0x99000000);
            FrameLayout.LayoutParams ck = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            ck.topMargin = dp(28);
            ck.rightMargin = dp(40);
            root.addView(clock, ck);

            row.addView(col, new LinearLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            d.setContentView(root);
            Sounds.attach(d);
            d.setOnDismissListener(x -> {
                handler.removeCallbacks(lyricsTick);
                if (drift != null) drift.cancel();
                if (pulse != null) pulse.cancel();
                title.setRunning(false);
                artist.setRunning(false);
                bigEq.setPlaying(false);
                full = null;
                noteInput();
            });
            drift = null;   // the blurred cover stays still: a moving blur is heavy for TV boxes
            backdrop.setScaleX(1.25f);
            backdrop.setScaleY(1.25f);

            root.setAlpha(0f);
            d.setOnShowListener(x -> {
                play.requestFocus();
                root.animate().alpha(1f).setDuration(200).start();
            });
            d.show();
            update(true);
            handler.post(lyricsTick);
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
                smallCover.setOutlineSpotShadowColor(glowColor);
                smallCover.setOutlineAmbientShadowColor(glowColor);
            }
        }

        /** A soft blur of the cover, made once: shrink, box-blur a few times. */
        private Bitmap softBlur(Bitmap src) {
            int n = 36;
            Bitmap small = Bitmap.createScaledBitmap(src, n, n, true).copy(Bitmap.Config.ARGB_8888, true);
            int[] p = new int[n * n], q = new int[n * n];
            small.getPixels(p, 0, n, 0, 0, n, n);
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        int r = 0, g = 0, b = 0, cnt = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = Math.min(n - 1, Math.max(0, x + dx)), yy = Math.min(n - 1, Math.max(0, y + dy));
                                int c = p[yy * n + xx];
                                r += (c >> 16) & 255;
                                g += (c >> 8) & 255;
                                b += c & 255;
                                cnt++;
                            }
                        }
                        q[y * n + x] = 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
                    }
                }
                int[] t = p;
                p = q;
                q = t;
            }
            small.setPixels(p, 0, n, 0, 0, n, n);
            return small;
        }

        /** Swaps a picture with a short fade instead of a cut. */
        private void crossfade(ImageView v, android.graphics.drawable.Drawable next) {
            android.graphics.drawable.Drawable prev = v.getDrawable();
            if (prev == null) {
                v.setImageDrawable(next);
                return;
            }
            android.graphics.drawable.TransitionDrawable td =
                    new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{prev, next});
            td.setCrossFadeEnabled(true);
            v.setImageDrawable(td);
            td.startTransition(400);
        }

        /** Refreshes from the session; {@code all} also redoes the pictures. */
        void update(boolean all) {
            if (d == null || current == null) return;
            MediaMetadata md = current.getMetadata();
            PlaybackState st = current.getPlaybackState();
            if (md == null) return;
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            String newTitle = String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE));
            if (!newTitle.equals(fullTitle)) {
                if (fullTitle != null) {   // a new song: its name and artist fade in
                    for (View v : new View[]{title, artist, album}) {
                        v.setAlpha(0f);
                        v.animate().alpha(1f).setDuration(320).start();
                    }
                }
                fullTitle = newTitle;
            }
            title.setText(newTitle);
            title.setRunning(true);
            findLyrics(md);
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
                    smallCover.setImageBitmap(c);
                    // Blurred once on the CPU (a tiny picture, softened, then stretched): a live blur
                    // effect was redone on every frame and made the player stutter on TV boxes.
                    android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(
                            act.getResources(), softBlur(c));
                    bd.setFilterBitmap(true);
                    crossfade(backdrop, bd);
                } else {
                    cover.setImageDrawable(appIcon(current.getPackageName()));
                    smallCover.setImageDrawable(appIcon(current.getPackageName()));
                    backdrop.setImageDrawable(null);
                }
                tint();
            }
            cover.setScaleX(1f);
            cover.setScaleY(1f);
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
            if (seekTarget >= 0 && android.os.SystemClock.uptimeMillis() < seekUntil) {
                show(seekTarget, pd[1]);   // the app hasn't caught up with the jump yet
                return;
            }
            seekTarget = -1;
            show(pd[0], pd[1]);
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
            if (playing) postInvalidateDelayed(90);
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
        protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gain, direction, previous);
            track.setColor(gain ? 0x59FFFFFF : 0x33FFFFFF);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, t = isFocused() ? thick * 1.7f : thick, head = t * 1.6f;
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
        if (pick == null && current != null && current.getSessionToken().equals(adopted)) {
            render();   // the woken session isn't listed: keep following it
            return;
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
        if (show && state == PlaybackState.STATE_PLAYING) holdUntil = 0;   // it's playing: back to normal
        if (!show && android.os.SystemClock.uptimeMillis() < holdUntil
                && (card.getVisibility() == View.VISIBLE || mini.getVisibility() == View.VISIBLE)) {
            return;   // a play is starting up: don't drop the card meanwhile
        }
        if (!show && showSaved()) return;   // no live session: the last song, ready to play again
        if (!show) {
            musicAccent = 0;
            if (mood != null) mood.on(glowColor, secondColor, false);
            if (full != null && full.d != null) full.d.dismiss();
            setBreathing(false);
            lastTitle = "";
            if (subTicker != null) subTicker.setRunning(false);
            if (ticker != null) ticker.setRunning(false);
            ticking = false;
            handler.removeCallbacks(tick);
            eq.setPlaying(false);
            miniEq.setPlaying(false);
            if (mini.getVisibility() == View.VISIBLE) {
                if (mini.hasFocus()) act.findViewById(R.id.btnSettings).requestFocus();
                mini.animate().alpha(0f).setDuration(200).withEndAction(() -> mini.setVisibility(View.GONE)).start();
            }
            if (shrink.hasFocus()) act.findViewById(R.id.btnSettings).requestFocus();
            shrink.setVisibility(View.GONE);
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
            if (pic != null) {
                art.setImageBitmap(pic);
                miniArt.setImageBitmap(pic);
            } else {
                Drawable icon = appIcon(current != null ? current.getPackageName() : act.getPackageName());
                art.setImageDrawable(icon);
                miniArt.setImageDrawable(icon);
            }
        };
        boolean changed = !song.toString().equals(lastTitle);
        lastTitle = song.toString();
        if (changed) {
            act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_title", song.toString())
                    .putString("last_line", line.toString()).putString("last_pkg", current.getPackageName())
                    .putLong("last_time", System.currentTimeMillis()).apply();
        }
        if (state == PlaybackState.STATE_PLAYING) {
            act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_time", System.currentTimeMillis()).apply();
        }
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
            saveCover(cover);
        }
        eq.setPlaying(state == PlaybackState.STATE_PLAYING);
        miniEq.setPlaying(state == PlaybackState.STATE_PLAYING);
        setBreathing(false);   // the animated shadow re-rendered the card every frame: kept still
        if (mood != null && lastCover != null) mood.on(glowColor, secondColor != 0 ? secondColor : glowColor,
                state == PlaybackState.STATE_PLAYING);
        if (full != null) full.update(false);
        updateProgress();
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, 1000);
        }
        playPause.setImageResource(state == PlaybackState.STATE_PLAYING ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        reveal();
    }

    private static final long SAVED_FOR_MS = 24 * 60 * 60 * 1000L;
    private Bitmap savedCover;
    private boolean savedCoverLoaded;

    /** The last song played (kept a day): shown, paused, when its app has no live session. */
    private boolean showSaved() {
        android.content.SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String title = p.getString("last_title", null);
        if (title == null || System.currentTimeMillis() - p.getLong("last_time", 0) > SAVED_FOR_MS) return false;
        musicAccent = 0;
        if (mood != null) mood.on(glowColor, secondColor, false);
        if (full != null && full.d != null) full.d.dismiss();
        ticking = false;
        handler.removeCallbacks(tick);
        eq.setPlaying(false);
        miniEq.setPlaying(false);
        ticker.setText(title);
        ticker.setRunning(true);
        subTicker.setText(p.getString("last_line", ""));
        subTicker.setRunning(true);
        lastTitle = title;
        if (!savedCoverLoaded) {
            savedCoverLoaded = true;
            savedCover = android.graphics.BitmapFactory.decodeFile(new java.io.File(act.getFilesDir(), "np_last.png").getPath());
        }
        Bitmap c = lastCover != null ? lastCover : savedCover;
        if (c != null) {
            art.setImageBitmap(c);
            miniArt.setImageBitmap(c);
            if (lastCover == null) {
                lastCover = c;
                tint(c);
                musicAccent = 0;
            }
        } else {
            Drawable icon = appIcon(p.getString("last_pkg", act.getPackageName()));
            art.setImageDrawable(icon);
            miniArt.setImageDrawable(icon);
        }
        progress.setVisibility(View.INVISIBLE);
        time.setText("");
        timeLeft.setText("");
        playPause.setImageResource(R.drawable.ic_np_play);
        reveal();
        return true;
    }

    private void saveCover(Bitmap cover) {
        if (cover == null) return;
        Bitmap b = cover;
        new Thread(() -> {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(act.getFilesDir(), "np_last.png"))) {
                Bitmap small = Bitmap.createScaledBitmap(b, 240, 240, true);
                small.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
            }
        }, "np-cover").start();
        savedCover = cover;
        savedCoverLoaded = true;
    }

    /** Shows the card (or the small pill), whichever the person chose. */
    private void reveal() {
        if (suppressed) return;
        View showView = small() ? mini : card, hideView = small() ? card : mini;
        shrink.animate().cancel();
        shrink.setAlpha(1f);
        shrink.setVisibility(small() ? View.GONE : View.VISIBLE);
        if (hideView.getVisibility() == View.VISIBLE) {
            hideView.animate().cancel();
            hideView.setVisibility(View.GONE);
        }
        if (showView.getVisibility() == View.VISIBLE && showView.getAlpha() < 1f) {
            // A step-aside fade was under way: bring it straight back.
            showView.animate().cancel();
            showView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
        }
        if (showView.getVisibility() != View.VISIBLE) {
            showView.setAlpha(0f);
            showView.setScaleX(0.92f);
            showView.setScaleY(0.92f);
            showView.setPivotX(showView.getWidth() > 0 ? showView.getWidth() : dp(400));
            showView.setPivotY(0);
            showView.setVisibility(View.VISIBLE);
            showView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
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
