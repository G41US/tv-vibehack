package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Song pop-up inside apps: when the song changes while another app is on screen, a small glass card
 * slides in at the top right with the cover, song and artist, then goes after a few seconds. Not shown
 * over TV Home (it has its own card) or over the music app itself. Drawn by HomeKeyService as an
 * accessibility overlay, so it sits above any app. Switched off in Music > Song pop-up in apps.
 */
final class SongPopup {

    private static final long SHOW_MS = 4500;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;
    private final long startedAt = System.currentTimeMillis();
    private String lastSong = "";
    private View card;
    private ImageView art;
    private TextView title, artist;
    private final Runnable hide = this::hide;

    /** The app on screen, kept up to date by HomeKeyService. */
    static volatile String front = "";

    SongPopup(AccessibilityService s) {
        service = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        try {
            MediaSessionManager msm = (MediaSessionManager) s.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(s, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, listener, h);
            watch(msm.getActiveSessions(listener));
        } catch (SecurityException ignored) {
            // no notification access yet: no pop-ups
        }
    }

    static boolean on(Context c) {
        return c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).getBoolean("song_popup", true);
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callbackFor(c));
        watched.clear();
        if (list == null) return;
        for (MediaController c : list) {
            c.registerCallback(callbackFor(c), h);
            watched.add(c);
        }
    }

    private final java.util.Map<String, MediaController.Callback> callbacks = new java.util.HashMap<>();

    /** One callback per session, so a song change knows which app it came from. */
    private MediaController.Callback callbackFor(MediaController c) {
        String key = c.getSessionToken().toString();
        MediaController.Callback cb = callbacks.get(key);
        if (cb == null) {
            cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata md) {
                    changed(c, md);
                }
            };
            callbacks.put(key, cb);
        }
        return cb;
    }

    private void changed(MediaController c, MediaMetadata md) {
        if (md == null || !on(service)) return;
        CharSequence song = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence by = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(song)) return;
        String key = song + "|" + by;
        Bitmap cover = CoverArt.of(service, md, () -> {   // a linked cover arriving a moment later
            Bitmap late = CoverArt.of(service, md, null);
            if (card != null && late != null && card.getParent() != null) art.setImageBitmap(late);
        });
        if (key.equals(lastSong)) {   // the same song again, often with its cover arriving late
            if (card != null && cover != null) art.setImageBitmap(cover);
            return;
        }
        lastSong = key;
        if (System.currentTimeMillis() - startedAt < 3000) return;   // not for what was already playing
        PlaybackState st = c.getPlaybackState();
        if (st != null && st.getState() != PlaybackState.STATE_PLAYING && st.getState() != PlaybackState.STATE_BUFFERING) return;
        String app = front;
        if (app.isEmpty() || app.equals(service.getPackageName()) || app.equals(c.getPackageName())) return;
        show(song, by, cover);
    }

    private int dp(float v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private void show(CharSequence song, CharSequence by, Bitmap cover) {
        if (card == null) build();
        title.setText(song);
        artist.setText(by);
        artist.setVisibility(TextUtils.isEmpty(by) ? View.GONE : View.VISIBLE);
        if (cover != null) art.setImageBitmap(cover);
        else art.setImageResource(R.drawable.ic_s_sound);
        if (card.getParent() == null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(440), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(64);   // clear of the edge many TVs crop
            lp.y = dp(48);
            try {
                wm.addView(card, lp);
            } catch (Exception e) {
                return;
            }
            card.setAlpha(0f);
            card.setTranslationX(dp(40));
        }
        card.animate().alpha(1f).translationX(0).setDuration(260).start();
        h.removeCallbacks(hide);
        h.postDelayed(hide, SHOW_MS);
    }

    private void hide() {
        if (card == null || card.getParent() == null) return;
        card.animate().alpha(0f).translationX(dp(40)).setDuration(260).withEndAction(() -> {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void build() {
        Context c = service;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xE62A3560, 0xE60E1428});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x40FFFFFF);
        box.setBackground(bg);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(dp(12));
        round.setColor(0x33FFFFFF);
        art.setBackground(round);
        box.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        title = new TextView(c);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = new TextView(c);
        artist.setTextColor(0xB3FFFFFF);
        artist.setTextSize(15);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(artist);
        box.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card = box;
    }

    void release() {
        h.removeCallbacksAndMessages(null);
        for (MediaController c : watched) c.unregisterCallback(callbackFor(c));
        watched.clear();
        try {
            ((MediaSessionManager) service.getSystemService(Context.MEDIA_SESSION_SERVICE)).removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
        }
        if (card != null && card.getParent() != null) {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
        }
    }
}
