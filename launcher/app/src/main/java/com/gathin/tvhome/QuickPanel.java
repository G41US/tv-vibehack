package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Quick panel: hold Home anywhere, even inside an app, for a glass panel at the right edge with what's
 * playing (◀ ▶ skip, OK play/pause), the volume, Night mode, Sleep now and Home. Switched off in
 * ⚙ Settings > Picture & sound > Quick panel, after which holding Home just goes Home.
 */
public class QuickPanel extends Activity {

    static final String PREFS = "look", KEY = "quick_panel";
    static volatile boolean showing;

    private Dialog dialog;
    /** A row is about to redraw the panel: closing the old card must not close the panel. */
    private boolean reopening;

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, QuickPanel.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(TextScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sounds.init(this);
        showing = true;
        render(0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        render(0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isChangingConfigurations()) finish();   // Home, an app or sleep took over: close
    }

    @Override
    protected void onDestroy() {
        showing = false;
        if (dialog != null) dialog.setOnDismissListener(null);
        super.onDestroy();
    }

    private MediaController media() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            MediaController paused = null;
            for (MediaController c : msm.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState st = c.getPlaybackState();
                if (st == null || c.getMetadata() == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING) return c;
                if (st.getState() == PlaybackState.STATE_PAUSED && paused == null) paused = c;
            }
            return paused;
        } catch (Exception e) {
            return null;   // no notification access: no media row
        }
    }

    private void render(int focus) {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        List<GlassMenu.Row> rows = new ArrayList<>();

        MediaController mc = media();
        if (mc != null) {
            MediaMetadata md = mc.getMetadata();
            CharSequence title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            boolean playing = mc.getPlaybackState() != null
                    && mc.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
            rows.add(new GlassMenu.Row(playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play,
                    TextUtils.isEmpty(title) ? getString(R.string.qp_media) : title,
                    TextUtils.isEmpty(artist) ? getString(R.string.qp_skip) : artist, () -> {
                reopening = true;
                if (playing) mc.getTransportControls().pause();
                else mc.getTransportControls().play();
                getWindow().getDecorView().postDelayed(() -> render(0), 350);
            }).stepper(dir -> {
                if (dir > 0) mc.getTransportControls().skipToNext();
                else mc.getTransportControls().skipToPrevious();
                getWindow().getDecorView().postDelayed(() -> render(0), 700);   // show the new track
                return "…";
            }));
        }

        if (am != null && !am.isVolumeFixed()) {
            rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, getString(R.string.tv_volume), volume(am), null)
                    .stepper(dir -> {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                        Night.enforceCap(this);
                        return volume(am);
                    }));
        }

        int start = Night.start(this);
        int at = rows.size();
        rows.add(new GlassMenu.Row(R.drawable.ic_m_moon, getString(R.string.tv_night),
                getString(start != 0 ? R.string.tv_on : R.string.tv_off), () -> {
            reopening = true;
            int last = getSharedPreferences(Night.PREFS, MODE_PRIVATE).getInt("last_start", 22);
            if (start != 0) {
                getSharedPreferences(Night.PREFS, MODE_PRIVATE).edit().putInt("last_start", start).apply();
                Night.setStart(this, 0);
            } else {
                Night.setStart(this, last);
                Night.enforceCap(this);
            }
            render(at);
        }));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, getString(R.string.su_title), getString(R.string.su_value), () -> {
            reopening = true;
            SpeedUp.run(this, this::finish);
        }));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_moon, getString(R.string.qp_sleep), null, () -> {
            finish();
            StillWatching.turnOff(getApplicationContext());
        }));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_apps, getString(R.string.qp_home), null, () -> {
            finish();
            HomeKeyService s = HomeKeyService.live;
            if (s != null) s.goHome();
            else startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }));

        String now = DateFormat.getTimeFormat(this).format(new Date());
        dialog = GlassMenu.page(this, getString(R.string.qp_kicker), now, null, rows, null, focus);
        dialog.setOnDismissListener(d -> {
            if (reopening) reopening = false;
            else finish();
        });
        Window w = dialog.getWindow();
        if (w != null) {
            w.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = GlassMenu.dp(this, 36);
            lp.width = GlassMenu.dp(this, 470);
            w.setAttributes(lp);
            w.setWindowAnimations(R.style.QuickPanelAnim);
            w.setDimAmount(0.35f);
        }
    }

    private static String volume(AudioManager am) {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max) + "%";
    }
}
