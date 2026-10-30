package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Calendar;
import java.util.Random;

/**
 * Screensaver: the Home wallpaper, darkened, drifting and zooming very slowly (a "Ken Burns" pan), with
 * the greeting, a large thin clock, the date and weather, and what's playing, in a corner of the screen.
 * The corner changes every few minutes and the picture never stops moving, so nothing burns in. With
 * a black wallpaper, a slow coloured glow stands in for the picture.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 3 * 60_000L, INFO_MS = 30_000L;
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};
    private static final int[] BUILT_IN = {R.drawable.wp_aurora, R.drawable.wp_midnight, R.drawable.wp_ember,
            R.drawable.wp_ocean, R.drawable.wp_dusk};
    private static final String[] BUILT_IN_KEYS = {"aurora", "midnight", "ember", "ocean", "dusk"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private ImageView photo;
    private TextView greeting, weather, playing;
    private GradientDrawable glow;
    private ValueAnimator drift;
    private int corner = random.nextInt(4), colorIndex, glowColor;
    private boolean hasPhoto;

    private final Runnable move = new Runnable() {
        @Override
        public void run() {
            corner = (corner + 1 + random.nextInt(3)) % 4;   // a different corner each time
            content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                place();
                content.animate().alpha(1f).setDuration(1400).start();
            }).start();
            if (!hasPhoto) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable info = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            handler.postDelayed(this, INFO_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        photo = findViewById(R.id.dreamPhoto);
        greeting = findViewById(R.id.dreamGreeting);
        weather = findViewById(R.id.dreamWeather);
        playing = findViewById(R.id.dreamPlaying);
        ((TextView) findViewById(R.id.dreamClock)).setTypeface(Fonts.weight(this, 200));

        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> glow.setGradientRadius((r - l) * 0.6f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        // Darker towards the corners with text, so it reads on any picture.
        View shade = findViewById(R.id.dreamShade);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x33000000, 0x33000000, 0xB3000000}));
        loadPhoto();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        updateInfo();
        content.setAlpha(0f);
        content.post(() -> {
            place();
            content.animate().alpha(1f).setDuration(1400).start();
        });
        handler.postDelayed(move, MOVE_MS);
        handler.postDelayed(info, INFO_MS);
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        handler.removeCallbacksAndMessages(null);
        if (drift != null) drift.cancel();
    }

    /** The text in one of the four corners, inset from the edges TVs crop. */
    private void place() {
        View parent = (View) content.getParent();
        float mx = parent.getWidth() * 0.06f, my = parent.getHeight() * 0.09f;
        boolean right = corner == 1 || corner == 3, bottom = corner >= 2;
        content.setX(right ? parent.getWidth() - content.getWidth() - mx : mx);
        content.setY(bottom ? parent.getHeight() - content.getHeight() - my : my);
        if (!hasPhoto && parent.getWidth() > 0) {
            glow.setGradientCenter((content.getX() + content.getWidth() / 2f) / parent.getWidth(),
                    (content.getY() + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** Greeting, weather and what's playing (refreshed every half minute). */
    private void updateInfo() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = getString(h < 5 ? R.string.good_night : h < 12 ? R.string.good_morning
                : h < 17 ? R.string.good_afternoon : h < 22 ? R.string.good_evening : R.string.good_night);
        String name = Profiles.activeName(this);
        greeting.setText(name.isEmpty() ? hello : hello + ", " + name);

        // The weather Home showed last ("27° Clear · H 34° L 24°"): just the now part, if it's recent.
        SharedPreferences w = getSharedPreferences("weather", MODE_PRIVATE);
        String line = w.getString("line2", null);
        boolean fresh = System.currentTimeMillis() - w.getLong("time", 0) < 6 * 3_600_000L;
        if (line != null && fresh) {
            int cut = line.indexOf(" · ");
            weather.setText("   ·   " + (cut > 0 ? line.substring(0, cut) : line));
            weather.setVisibility(View.VISIBLE);
        } else {
            weather.setVisibility(View.GONE);
        }

        String song = nowPlaying();
        playing.setText(song != null ? "♪  " + song : "");
        playing.setVisibility(song != null ? View.VISIBLE : View.GONE);
    }

    /** "Title — Artist" of what's playing, or null (needs notification access, as Now playing). */
    private String nowPlaying() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : msm.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState st = c.getPlaybackState();
                MediaMetadata md = c.getMetadata();
                if (st == null || md == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                CharSequence title = md.getText(MediaMetadata.METADATA_KEY_TITLE), artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (TextUtils.isEmpty(title)) continue;
                return TextUtils.isEmpty(artist) ? title.toString() : title + "  —  " + artist;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The Home wallpaper (a photo sent from a phone, or a built-in one), decoded off the main thread. */
    private void loadPhoto() {
        String choice = getSharedPreferences("look", MODE_PRIVATE).getString(Profiles.key(this, "wallpaper"), "theme");
        if ("black".equals(choice)) return;
        File custom = new File(getFilesDir(), Profiles.key(this, "custom_wallpaper") + ".jpg");
        int res = BUILT_IN[0];   // the theme or a moving scene: a still from the built-in set
        for (int i = 0; i < BUILT_IN_KEYS.length; i++) if (BUILT_IN_KEYS[i].equals(choice)) res = BUILT_IN[i];
        final int resId = res;
        final boolean usePhoto = "photo".equals(choice) && custom.isFile();
        new Thread(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; fine for a darkened backdrop
                o.inSampleSize = 1;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                if (usePhoto) BitmapFactory.decodeFile(custom.getPath(), bounds);
                else BitmapFactory.decodeResource(getResources(), resId, bounds);
                while (bounds.outWidth / (o.inSampleSize * 2) >= 1600) o.inSampleSize *= 2;
                b = usePhoto ? BitmapFactory.decodeFile(custom.getPath(), o) : BitmapFactory.decodeResource(getResources(), resId, o);
            } catch (Throwable ignored) {
            }
            final Bitmap got = b;
            handler.post(() -> {
                if (got == null || photo == null) return;
                hasPhoto = true;
                photo.setImageBitmap(got);
                photo.animate().alpha(1f).setDuration(1500).start();
                findViewById(R.id.dreamAmbient).setVisibility(View.GONE);
                startDrift();
            });
        }, "dream-photo").start();
    }

    /** A very slow zoom and pan, back and forth, about a minute and a half each way. */
    private void startDrift() {
        photo.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        float dx = random.nextBoolean() ? 1 : -1, dy = random.nextBoolean() ? 1 : -1;
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(90_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float s = 1.08f + 0.10f * t;
            photo.setScaleX(s);
            photo.setScaleY(s);
            photo.setTranslationX(dx * t * photo.getWidth() * 0.035f);
            photo.setTranslationY(dy * t * photo.getHeight() * 0.03f);
        });
        drift.start();
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x55000000 | c, 0x1A000000 | c, c});
    }
}
