package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs;
    private View btnSearch;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            checkNet();
            weather.refresh(false, MainActivity.this::showWeather);
            DataUsage.sample(MainActivity.this);
            updateNight();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeStart.begin(this);   // counts crashes; two in a row: safe mode (effects off)
        created = true;
        BootTrace.watch(this);
        BootTrace.mark(this, "TV Home started", android.os.Process.getStartElapsedRealtime());
        BootTrace.mark(this, "home opening");
        setContentView(R.layout.activity_main);
        // Font weights set here, once per weight (in the layout, Android built a font per view: slow at start-up).
        int[][] weights = {{R.id.heroName, 300}, {R.id.greeting, 500}, {R.id.clock, 300}, {R.id.ampm, 500},
                {R.id.shamsiDate, 400}, {R.id.weather, 400}, {R.id.date, 400}};
        for (int[] w : weights) ((TextView) findViewById(w[0])).setTypeface(Fonts.weight(this, w[1]));
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        // Apps in the dock (and per row), as picked in Look; the theme's or config.xml's until then.
        COLUMNS = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).getInt(HomeSettings.KEY_DOCK, cfg.columns);
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        if (liveTvPkg.isEmpty() && installed("com.tcl.tv")) {
            // TCL TVs: their TV app (aerial and last input) has no leanback entry; the TV action opens it.
            liveTvPkg = "com.tcl.tv";
            if (liveTvAction.isEmpty()) liveTvAction = "android.intent.action.TV";
        }
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        splash = Splash.maybeShow(this);   // first Home after the box starts: the logo animation
        if (getIntent() != null && getIntent().getBooleanExtra(BootHome.EXTRA_PLAYED, false)) {
            // Straight from the start-up screen, which is holding the finished logo: show the same
            // picture until Home is ready underneath, then fade into Home (no black gap).
            logoHold = new ImageView(this);
            logoHold.setScaleType(ImageView.ScaleType.CENTER_CROP);
            logoHold.setImageResource(R.drawable.startup_logo);
            logoHold.setBackgroundColor(Color.BLACK);
            addContentView(logoHold, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            private boolean once;

            @Override
            public void onDraw() {
                if (once || (splash != null && splash.active())) return;
                once = true;
                root.post(() -> {
                    Splash.homeSeen(MainActivity.this);
                    StartupApps.release(MainActivity.this);   // held-back apps back on after the animation
                    BootTrace.mark(MainActivity.this, "home on screen");
                });
            }
        });
        config = cfg;
        updater = new Updater(this);
        setupPanel = new SetupPanel(this, updater);
        tvSettings = new TvSettings(this);
        Sounds.init(this);
        applyWallpaper(cfg);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        greeting.setAllCaps(false);   // capitals done in updateGreeting: Android's own drops the bold name on older boxes
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        weatherView = findViewById(R.id.weather);
        weatherView.getLayoutParams().height = Math.round(22 * density * TextScale.current);
        dateView = findViewById(R.id.date);
        weather = new Weather(this);
        showWeather(weather.cached());

        applyClockStyle();
        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(720f).setDampingRatio(0.78f));   // quick and crisp, little wobble
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnSearch = findViewById(R.id.btnSearch);
        if (searchIntent() != null) {
            btnSearch.setVisibility(View.VISIBLE);
            btnSearch.setOnClickListener(v -> {
                Intent i = searchIntent();
                if (i != null) open(i);
            });
        }
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        // Hold OK: the Network panel (guest Wi-Fi QR code, data used); Wi-Fi settings are one button away there.
        btnWifi.setOnLongClickListener(v -> {
            showNetworkPanel();
            return true;
        });
        btnSettings.setOnClickListener(v -> tvSettings.show());
        // Hold OK on Settings: the launcher's own menu (wallpaper, updates).
        btnSettings.setOnLongClickListener(v -> {
            showHomeMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Search, else Inputs, else Wi-Fi on a box without inputs. Left from
                // there goes to the weather (OK opens the forecast) when it's showing.
                boolean leftEnd = btnSearch.getVisibility() == View.VISIBLE ? v.getId() == R.id.btnSearch
                        : v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE);
                if (leftEnd && weatherView.getVisibility() == View.VISIBLE) weatherBlock.requestFocus();
                return leftEnd;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnPower;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnSearch.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        View btnPower = findViewById(R.id.btnPower);
        btnPower.setOnKeyListener(pillKeys);
        btnPower.setOnClickListener(v -> PowerMenu.show(this, v));

        weatherBlock = findViewById(R.id.weatherBlock);
        weatherBlock.setOnClickListener(v -> ForecastPage.show(this, weather));
        weatherBlock.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View first = btnSearch.getVisibility() == View.VISIBLE ? btnSearch
                        : btnInputs.getVisibility() == View.VISIBLE ? btnInputs : btnWifi;
                first.requestFocus();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP;
        });
        weatherBlock.setFocusable(weatherView.getVisibility() == View.VISIBLE);

        // Now playing card under the top bar (music or video playing in another app).
        nowPlaying = new NowPlaying(this);
        nowPlaying.attach(root, findViewById(R.id.topButtons));
        // Album colour lighting, over the wallpaper and under everything else.
        mood = new MoodLight(this, density);
        mood.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(mood, Math.max(0, root.indexOfChild(ambient)), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nowPlaying.setMood((a, b, playing) -> mood.set(a, b, playing && HomeSettings.moodOn(this)));

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                if (nowPlaying != null && newF != null) nowPlaying.setSuppressed(false);
                hideGlass();
                hideHero();   // the app name and its glow belong to a focused tile only
            }
            if (oldF != null && newF != null) Sounds.move(this);
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        showWallpaper(opts -> android.graphics.BitmapFactory.decodeFile(file.getPath(), opts), file);
    }

    private void showWallpaper(int resId) {
        showWallpaper(opts -> android.graphics.BitmapFactory.decodeResource(getResources(), resId, opts), null);
    }

    /**
     * Decoded on its own thread (reading a phone photo took 6.4 s while the box was starting, and
     * nothing could be drawn meanwhile), then faded in. A big photo is saved back at screen size once,
     * so later starts read a fraction of it.
     */
    private void showWallpaper(java.util.function.Function<android.graphics.BitmapFactory.Options, android.graphics.Bitmap> decoder,
                               File shrink) {
        new Thread(() -> {
            android.graphics.Bitmap bmp = decodeWallpaper(decoder);
            if (bmp == null) return;
            if (shrink != null && shrink.length() > 1_500_000) {
                File tmp = new File(shrink.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
                    out.getFD().sync();
                } catch (Exception | OutOfMemoryError e) {
                    tmp.delete();
                }
                if (tmp.length() > 0 && !tmp.renameTo(shrink)) tmp.delete();
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) addWallpaper(bmp);
            });
        }, "wallpaper").start();
    }

    private android.graphics.Bitmap decodeWallpaper(java.util.function.Function<android.graphics.BitmapFactory.Options, android.graphics.Bitmap> decoder) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            decoder.apply(opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inScaled = false;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            return decoder.apply(opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;   // a bad or huge picture must never take the home screen down; the plain colour stays
        }
    }

    private void addWallpaper(android.graphics.Bitmap bmp) {
        try {
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            FrameLayout layer = new FrameLayout(this);
            layer.addView(wall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(layer, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layer.setAlpha(0f);
            layer.animate().alpha(1f).setDuration(250).start();
            if (LiveWallpaper.enabled(this)) {
                // Moving wallpaper: a slow drift, and the picture's own stars twinkle.
                twinkle = new LiveWallpaper.Twinkle(this, density);
                layer.addView(twinkle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // No continuous drift: moving the full-screen picture every frame slowed the whole home
                // screen on TV boxes. The stars still twinkle (a few at a time, ~12 times a second).
                // Live weather: rain, storms, snow, harmattan haze or fog at the weather's place.
                weatherFx = new WeatherFx(this, density);
                layer.addView(weatherFx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                weatherFx.setMode(HomeSettings.weatherFxOn(this) ? WeatherFx.modeFor(Weather.code(this), Weather.visibility(this)) : WeatherFx.NONE);
                if (resumed) weatherFx.start();
                // Festive looks: confetti, snow or sparkles on a holiday where the person is.
                Festive.Holiday holiday = SafeStart.on(this) ? null : Festive.today(this);
                if (holiday != null) {
                    festiveFx = new Festive.Fx(this, density, holiday);
                    layer.addView(festiveFx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    if (resumed) festiveFx.start();
                }
                final LiveWallpaper.Twinkle tw = twinkle;
                new Thread(() -> {
                    float[][] stars;
                    try {
                        stars = LiveWallpaper.findStars(bmp, 45);
                    } catch (Exception | OutOfMemoryError err) {
                        return;
                    }
                    final float[][] found = stars;
                    runOnUiThread(() -> {
                        tw.setStars(found, bmp.getWidth(), bmp.getHeight());
                        if (hasWindowFocus() || isResumedNow()) tw.start();
                    });
                }).start();
            }
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /** Home is on screen (the box's own home screen is only brought back when it isn't). */
    static volatile boolean visible;

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        if (!isFinishing()) StartupApps.releaseHomes(this);
        // Not in onPause: at start-up Android brings Home to the front again, which pauses it for a moment.
        if (splash != null) splash.end();
    }

    /** Runs r once, just after the next frame is on screen. */
    private void afterFirstFrame(Runnable r) {
        root.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            private boolean done;

            @Override
            public void onDraw() {
                if (done) return;
                done = true;
                handler.post(() -> {
                    root.getViewTreeObserver().removeOnDrawListener(this);
                    r.run();
                });
            }
        });
        root.invalidate();
    }

    /** Home pressed while TV Home is already showing: close whatever is open over it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (nowPlaying != null) nowPlaying.closeFull();
            GlassMenu.closeAll();
            if (phoneDialog != null && phoneDialog.isShowing()) phoneDialog.dismiss();
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(TextScale.wrap(base));   // ⚙ Settings > Picture & sound > Text size
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (HomeSettings.reopenLook >= 0) {
            int row = HomeSettings.reopenLook;
            HomeSettings.reopenLook = -1;
            root.post(() -> new HomeSettings(this).look(row));
        }
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean fromLogo = logoHold != null;
        if (fromLogo) {
            // From the start-up screen: draw the logo first, then load Home under it and fade across.
            ImageView hold = logoHold;
            logoHold = null;
            afterFirstFrame(() -> {
                adapter.reload();
                grid.post(() -> focusTile(Math.max(0, adapter.positionOf(returnKey))));
                afterFirstFrame(() -> hold.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                    if (hold.getParent() instanceof ViewGroup) ((ViewGroup) hold.getParent()).removeView(hold);
                }).start());
            });
        } else if (splash != null && splash.active()) {
            // Start-up: the app list asks Android for every app, slow while the box is still starting
            // (2.7 s). Draw the animation's first frame first; the list fills in behind it.
            splash.whenPlaying(() -> {
                adapter.reload();
                grid.post(() -> focusTile(Math.max(0, adapter.positionOf(returnKey))));
            });
        } else {
            adapter.reload();
            grid.scheduleLayoutAnimation();
        }
        SelfHeal.ensureService(this);
        if (BackupStore.supported() && BackupStore.canRead(this) && !BackupStore.restoreChecked(this)) {
            runRestore();   // first start after a reinstall, once the photos permission is granted
        }
        setupPanel.onResume();
        weather.refresh(false, this::showWeather);
        updateGreeting();
        updateWifiLabel();
        // Checks that can wait a moment, so coming back to Home is instant.
        handler.removeCallbacks(afterResume);
        handler.postDelayed(afterResume, 1500);
        updateInputsButton();
        updateNight();
        resumed = true;
        nowPlaying.start();
        if (weatherFx != null) weatherFx.start();
        if (festiveFx != null) festiveFx.start();
        if (videoWall != null) videoWall.start();
        if (mood != null) mood.resume();
        if (twinkle != null) twinkle.start();
        if (driftAnim != null) {
            if (driftAnim.isPaused()) driftAnim.resume();
            else if (!driftAnim.isStarted()) driftAnim.start();
        }
        handler.postDelayed(minuteTick, MINUTE_MS);
        SafeStart.started(this);
        if (SafeStart.shouldTell(this)) {
            SafeStart.told(this);
            String last = SafeStart.lastProblem(this);
            GlassMenu.message(this, getString(R.string.safe_title),
                    getString(R.string.safe_message) + (last != null ? "\n\n" + last : ""),
                    new String[]{getString(R.string.safe_update), getString(R.string.safe_effects), getString(android.R.string.ok)},
                    w -> {
                        if (w == 0) updater.check(true);
                        else if (w == 1) {
                            SafeStart.leave(this);
                            recreate();
                        }
                    });
        }

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        BackupStore.saveAsync(this);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(afterResume);
        resumed = false;
        nowPlaying.stop();
        if (weatherFx != null) weatherFx.stop();
        if (festiveFx != null) festiveFx.stop();
        if (videoWall != null) videoWall.stop();
        if (mood != null) mood.pause();
        if (twinkle != null) twinkle.stop();
        if (driftAnim != null) driftAnim.pause();
    }

    // ---- night mode, moving wallpaper, sounds --------------------------------------------------

    /** A home screen has been opened in this process (HomeKeyService opens one early at start-up). */
    static volatile boolean created;
    private final Runnable afterResume = this::afterResume;

    private void afterResume() {
        if (!resumed) return;
        DataLimit.maybeWarn(this);
        updater.autoCheck();
        new Thread(() -> DataUsage.sample(this), "data-sample").start();
        SimData.refreshIfStale(this, this::updateWifiLabel);
        checkNet();
    }
    private View nightShade;
    private ImageView logoHold;
    private Splash splash;
    private MoodLight mood;
    private WeatherFx weatherFx;
    private Festive.Fx festiveFx;
    private LiveWallpaper.Twinkle twinkle;
    private android.animation.ValueAnimator driftAnim;
    private NowPlaying nowPlaying;

    /** Night mode: a warm, dimming veil over the home screen between the night hours. */
    private boolean resumed;

    private boolean isResumedNow() {
        return resumed;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    void refreshNight() {
        updateNight();
    }

    private void updateNight() {
        boolean on = Night.active(this);
        if (on && nightShade == null) {
            nightShade = new View(this);
            nightShade.setBackgroundColor(0x5A1A0C00);   // dark amber: dimmer and warmer
            nightShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(nightShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (nightShade != null) nightShade.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (splash != null && splash.active()) {   // any button skips the start-up animation
            if (e.getAction() == KeyEvent.ACTION_UP) splash.end();
            return true;
        }
        Sounds.onKey(this, e);
        if (nowPlaying != null) nowPlaying.noteInput();
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (phoneServer != null) phoneServer.stop();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- TV Home menu: wallpaper and updates (hold OK on Settings) --------------------------------

    private static final String LOOK_PREFS = "look", KEY_WALLPAPER = "wallpaper";
    private static final String WP_BLACK = "black", WP_PHOTO = "photo", WP_THEME = "theme";
    private static final String[] BUILT_IN = {"aurora", "midnight", "ember", "ocean", "dusk"};
    private static final int[] BUILT_IN_RES = {R.drawable.wp_aurora, R.drawable.wp_midnight,
            R.drawable.wp_ember, R.drawable.wp_ocean, R.drawable.wp_dusk};
    private static final int[] BUILT_IN_NAMES = {R.string.wp_aurora, R.string.wp_midnight,
            R.string.wp_ember, R.string.wp_ocean, R.string.wp_dusk};

    private Config config;
    Updater updater;
    SetupPanel setupPanel;

    private boolean restoring;

    private void runRestore() {
        if (restoring) return;
        restoring = true;
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = BackupStore.restore(app);
            runOnUiThread(() -> {
                restoring = false;
                if (!ok || isFinishing() || isDestroyed()) return;
                Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_LONG).show();
                recreate();
            });
        }, "restore").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Back from the default-home picker: onResume refreshes the setup panel.
    }
    private Weather weather;
    private TextView weatherView;
    private View weatherBlock;
    private TextView dateView;

    /** The weather line above the date; with it showing, the date steps down a size like the Shamsi date. */
    private void showWeather(String line) {
        boolean on = line != null && !line.isEmpty();
        // The city moves down to the date line ("WEDNESDAY 21 OCTOBER · ABUJA") so the weather line
        // stays short enough to show in full next to the buttons.
        String city = null;
        if (on) {
            String[] parts = line.split("  ·  ");
            String last = parts[parts.length - 1];
            if (parts.length >= 3 || (parts.length == 2 && !last.contains("°"))) {
                city = last.trim();
                line = line.substring(0, line.length() - last.length() - "  ·  ".length());
            }
        }
        String fmt = getString(R.string.date_format);
        if (city != null && !city.isEmpty()) fmt += "  '·  " + city.replace("'", "''") + "'";
        ((android.widget.TextClock) dateView).setFormat12Hour(fmt);
        ((android.widget.TextClock) dateView).setFormat24Hour(fmt);
        weatherView.setText(on ? line : "");
        if (weatherFx != null) weatherFx.setMode(on && HomeSettings.weatherFxOn(this)
                ? WeatherFx.modeFor(Weather.code(this), Weather.visibility(this)) : WeatherFx.NONE);
        weatherView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (weatherBlock != null) weatherBlock.setFocusable(on);
        boolean small = on || shamsi;
        dateView.setTextSize(small ? 11f : 14f);
        dateView.setPadding(0, small ? (int) (4 * density) : 0, 0, 0);
        if (on && shamsi) shamsiDate.setVisibility(View.GONE);   // one line above the date is enough
        else if (shamsi) shamsiDate.setVisibility(View.VISIBLE);
    }

    void showWeatherMenu() {
        String current = weather.place();
        List<String> labels = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        String found = weather.autoCity();
        labels.add(mark(current, Weather.AUTO, found != null && !found.isEmpty()
                ? getString(R.string.weather_auto_found, found) : getString(R.string.weather_auto)));
        keys.add(Weather.AUTO);
        labels.add("     " + getString(R.string.weather_search));
        keys.add("");
        if (current.indexOf('|') > 0) {   // a city found with Search
            labels.add(mark(current, current, Weather.cityName(current)));
            keys.add(current);
        }
        for (String[] c : Weather.CITIES) {
            labels.add(mark(current, c[0], c[0]));
            keys.add(c[0]);
        }
        labels.add(mark(current, Weather.OFF, getString(R.string.weather_off)));
        keys.add(Weather.OFF);
        GlassMenu.list(this, getString(R.string.menu_title), getString(R.string.menu_weather),
                labels.toArray(new String[0]), which -> {
                    if (keys.get(which).isEmpty()) searchCity();
                    else setWeatherPlace(keys.get(which));
                });
    }

    private void setWeatherPlace(String place) {
        weather.setPlace(place);
        showWeather(null);
        weather.refresh(true, this::showWeather);
    }

    /** Any city in the world: type a name, pick from what Open-Meteo finds. */
    private void searchCity() {
        GlassMenu.input(this, getString(R.string.weather_search), "", text -> {
            String q = text.trim();
            if (q.isEmpty()) return;
            new Thread(() -> {
                List<String[]> found;
                try {
                    found = Weather.search(q);
                } catch (Exception e) {
                    found = null;   // offline
                }
                final List<String[]> f = found;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (f == null || f.isEmpty()) {
                        GlassMenu.message(this, getString(R.string.weather_search),
                                f == null ? getString(R.string.weather_search_offline) : getString(R.string.weather_search_none, q),
                                new String[]{getString(R.string.weather_search_again), getString(android.R.string.cancel)},
                                w -> {
                                    if (w == 0) searchCity();
                                });
                        return;
                    }
                    String[] labels = new String[f.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = f.get(i)[0];
                    GlassMenu.list(this, getString(R.string.menu_weather), q, labels,
                            which -> setWeatherPlace(f.get(which)[1]));
                });
            }, "city-search").start();
        });
    }
    private PhoneServer phoneServer;
    private android.app.Dialog phoneDialog;

    private File customWallpaper() {
        return new File(getFilesDir(), Profiles.key(this, "custom_wallpaper") + ".jpg");
    }

    /** The wallpaper picked on the TV wins; until one is picked, a pushed theme's look applies. */
    private void applyWallpaper(Config cfg) {
        String choice = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).getString(Profiles.key(this, KEY_WALLPAPER), WP_THEME);
        if (WP_THEME.equals(choice)) {
            root.setBackgroundColor(cfg.backgroundColor);
            if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
            return;
        }
        root.setBackgroundColor(Color.BLACK);
        if (WP_PHOTO.equals(choice)) {
            if (customWallpaper().isFile()) showWallpaper(customWallpaper());
            return;
        }
        for (int i = 0; i < BUILT_IN.length; i++) {
            if (BUILT_IN[i].equals(choice)) showWallpaper(BUILT_IN_RES[i]);
        }
        // Safe mode (TV Home kept crashing) leaves moving scenes off, like the other effects.
        if (VideoWallpaper.index(choice) >= 0 && VideoWallpaper.file(this, choice).isFile() && !SafeStart.on(this)) {
            // A moving scene, playing only while Home is on screen; festive touches go on top as usual.
            FrameLayout layer = new FrameLayout(this);
            videoWall = new VideoWallpaper.Player(this, VideoWallpaper.file(this, choice));
            layer.addView(videoWall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Festive.Holiday holiday = SafeStart.on(this) ? null : Festive.today(this);
            if (holiday != null) {
                festiveFx = new Festive.Fx(this, density, holiday);
                layer.addView(festiveFx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            root.addView(layer, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (resumed) {
                videoWall.start();
                if (festiveFx != null) festiveFx.start();
            }
        }
    }

    private VideoWallpaper.Player videoWall;

    private void setWallpaper(String key) {
        getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).edit().putString(Profiles.key(this, KEY_WALLPAPER), key).apply();
        recreate();
    }

    void refreshNowPlaying() {
        if (nowPlaying != null) nowPlaying.refreshView();
    }

    void showHomeMenu() {
        new HomeSettings(this).grid();
    }

    // ---- TV settings (press OK on Settings): see TvSettings ---------------------------------------

    private TvSettings tvSettings;

    NetHealth.State netState() {
        return netHealth.state();
    }

    /** TV Home's clock as 12- or 24-hour, or as the box is set (Date & time page). */
    void applyClockStyle() {
        android.widget.TextClock clock = findViewById(R.id.clock), ampm = findViewById(R.id.ampm);
        if (clock == null || ampm == null) return;
        String style = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).getString(TvSettings.KEY_CLOCK, "");
        if ("24".equals(style)) {
            clock.setFormat12Hour("HH:mm");
            clock.setFormat24Hour("HH:mm");
            ampm.setFormat12Hour("");
            ampm.setFormat24Hour("");
        } else if ("12".equals(style)) {
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("h:mm");
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
        } else {
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("HH:mm");
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == TvSettings.REQUEST_BT) tvSettings.remotes(0);
    }

    // ---- profiles ----------------------------------------------------------------------------------

    private void switchProfile(String id) {
        if (id.equals(Profiles.active(this))) return;
        Profiles.setActive(this, id);
        recreate();
    }

    long installedBuild() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    String wallpaperName() {
        String c = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).getString(Profiles.key(this, KEY_WALLPAPER), WP_THEME);
        if (WP_BLACK.equals(c)) return getString(R.string.wp_black);
        if (WP_PHOTO.equals(c)) return getString(R.string.wp_photo);
        for (int i = 0; i < BUILT_IN.length; i++) if (BUILT_IN[i].equals(c)) return getString(BUILT_IN_NAMES[i]);
        if (VideoWallpaper.index(c) >= 0) return getString(VideoWallpaper.NAMES[VideoWallpaper.index(c)]);
        return config != null && config.wallpaper != null ? getString(R.string.wp_theme) : getString(R.string.wp_black);
    }

    String weatherName() {
        String p = weather.place();
        if (Weather.OFF.equals(p)) return getString(R.string.weather_off);
        if (Weather.AUTO.equals(p)) {
            String found = weather.autoCity();
            return found != null && !found.isEmpty() ? found : getString(R.string.weather_auto);
        }
        return Weather.cityName(p);
    }

    /** Settings > Profiles: the same page, to switch, add or manage. */
    void showProfilesMenu() {
        ProfilePicker.show(this, false, profileHost, null);
    }

    private final ProfilePicker.Host profileHost = new ProfilePicker.Host() {
        @Override
        public void pick(String id) {
            switchProfile(id);
        }

        @Override
        public void add() {
            askName(getString(R.string.profile_add_title), "", name -> switchProfile(Profiles.add(MainActivity.this, name)));
        }

        @Override
        public void edit(String id) {
            editProfile(id);
        }
    };

    private String profileName(String id) {
        for (String[] p : Profiles.list(this)) if (p[0].equals(id)) return p[1];
        return "";
    }

    /** Manage profiles > a person: their picture, name, birthday, or removing them. */
    private void editProfile(String id) {
        String name = profileName(id), pic = Profiles.emoji(this, id), bday = Profiles.birthday(this, id);
        boolean hasPic = Profiles.avatar(this, id) != null;
        Runnable back = () -> ProfilePicker.show(this, true, profileHost, null);
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_m_wallpaper, getString(R.string.pp_picture), pic != null ? pic : hasPic ? "✓" : "–",
                () -> askAvatar(id)));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_text, getString(R.string.pp_name), name,
                () -> askName(getString(R.string.profile_rename_title), name, n -> {
                    Profiles.rename(this, id, n);
                    updateGreeting();
                    editProfile(id);
                })));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_moon, getString(R.string.pp_birthday), bday != null ? birthdayText(bday) : "–",
                () -> askBirthday(id)));
        if (!Profiles.MAIN.equals(id)) {
            rows.add(new GlassMenu.Row(R.drawable.ic_s_shield, getString(R.string.pp_remove, name), null, () -> {
                boolean wasActive = id.equals(Profiles.active(this));
                Profiles.remove(this, id);
                if (wasActive) recreate();
                else back.run();
            }));
        }
        GlassMenu.page(this, getString(R.string.pp_manage), (pic != null ? pic + "  " : "") + name, null, rows, back, 0);
    }

    /** The full-screen picture chooser; "From your phone" opens the phone page for a photo. */
    private void askAvatar(String id) {
        ProfilePicker.pickPicture(this, id, profileName(id), v -> {
            Profiles.setAvatar(this, id, v);
            updateGreeting();
            editProfile(id);
        }, () -> showFaceReceiver(id), () -> editProfile(id));
    }

    /** Phone page for sending a profile photo: cropped square on the phone, checked and shrunk here. */
    private void showFaceReceiver(String id) {
        File target = Profiles.photo(this, id);
        showPhoneLink(R.raw.face, R.string.wp_send_title, R.string.wp_send_message, body -> {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(body.getPath());
            if (b == null) return false;
            int side = Math.min(b.getWidth(), b.getHeight());
            android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            if (side > 512) sq = android.graphics.Bitmap.createScaledBitmap(sq, 512, 512, true);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                return sq.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            }
        }, () -> {
            Profiles.setAvatar(this, id, Profiles.PHOTO);
            updateGreeting();
            editProfile(id);
        });
    }

    /** "14 March" (in the TV's language) for "03-14". */
    private static String birthdayText(String mmdd) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(2000, Integer.parseInt(mmdd.substring(0, 2)) - 1, Integer.parseInt(mmdd.substring(3, 5)));
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMM");
            return new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(c.getTime());
        } catch (Exception e) {
            return mmdd;
        }
    }

    /** Pick a month, then a day; "No birthday" clears it. */
    private void askBirthday(String id) {
        String[] months = new java.text.DateFormatSymbols(Locale.getDefault()).getMonths();
        List<String> labels = new ArrayList<>();
        if (Profiles.birthday(this, id) != null) labels.add(getString(R.string.birthday_none));
        final int offset = labels.size();
        for (int m = 0; m < 12; m++) labels.add(months[m]);
        GlassMenu.list(this, getString(R.string.menu_profiles), getString(R.string.birthday_month_title),
                labels.toArray(new String[0]), which -> {
                    if (which < offset) {
                        Profiles.setBirthday(this, id, null);
                        updateGreeting();
                        return;
                    }
                    int month = which - offset + 1;
                    int days = month == 2 ? 29 : (month == 4 || month == 6 || month == 9 || month == 11) ? 30 : 31;
                    String[] dayLabels = new String[days];
                    for (int d = 1; d <= days; d++) dayLabels[d - 1] = birthdayText(String.format(Locale.US, "%02d-%02d", month, d));
                    GlassMenu.list(this, getString(R.string.menu_profiles), getString(R.string.birthday_day_title), dayLabels, day -> {
                        Profiles.setBirthday(this, id, String.format(Locale.US, "%02d-%02d", month, day + 1));
                        updateGreeting();
                        recreate();   // confetti straight away if it's today
                    });
                });
    }


    private interface NameListener {
        void onName(String name);
    }

    private void askName(String title, String initial, NameListener l) {
        GlassMenu.input(this, title, initial, name -> l.onName(name.length() > 24 ? name.substring(0, 24) : name));
    }

    void showWallpaperMenu() {
        String current = getSharedPreferences(LOOK_PREFS, MODE_PRIVATE).getString(Profiles.key(this, KEY_WALLPAPER), WP_THEME);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(mark(current, WP_BLACK, getString(R.string.wp_black)));
        actions.add(() -> setWallpaper(WP_BLACK));
        for (int i = 0; i < BUILT_IN.length; i++) {
            final String key = BUILT_IN[i];
            labels.add(mark(current, key, getString(BUILT_IN_NAMES[i])));
            actions.add(() -> setWallpaper(key));
        }
        for (int i = 0; i < VideoWallpaper.KEYS.length; i++) {
            final String key = VideoWallpaper.KEYS[i];
            labels.add(mark(current, key, getString(VideoWallpaper.NAMES[i]) + "  ·  " + getString(R.string.wp_v_tag)));
            actions.add(() -> VideoWallpaper.fetch(this, key, () -> setWallpaper(key)));
        }
        if (customWallpaper().isFile()) {
            labels.add(mark(current, WP_PHOTO, getString(R.string.wp_photo)));
            actions.add(() -> setWallpaper(WP_PHOTO));
        }
        if (config != null && config.wallpaper != null) {
            labels.add(mark(current, WP_THEME, getString(R.string.wp_theme)));
            actions.add(() -> setWallpaper(WP_THEME));
        }
        labels.add(getString(R.string.wp_send));
        actions.add(this::showPhotoReceiver);

        GlassMenu.list(this, getString(R.string.menu_title), getString(R.string.menu_wallpaper),
                labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    private static String mark(String current, String key, String label) {
        return (key.equals(current) ? "✓  " : "     ") + label;
    }

    /** Phone page for sending a wallpaper photo. */
    private void showPhotoReceiver() {
        File target = customWallpaper();
        showPhoneLink(R.raw.upload, R.string.wp_send_title, R.string.wp_send_message, body -> {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(body.getPath(), o);
            return o.outWidth > 0 && o.outHeight > 0 && body.renameTo(target);
        }, () -> setWallpaper(WP_PHOTO));
    }

    /**
     * Shows the address of a one-off phone page (PhoneServer) and keeps it running until something
     * arrives or the dialog is closed.
     */
    private void showPhoneLink(int pageRes, int titleRes, int messageRes,
                               PhoneServer.BodyHandler handler, Runnable onDone) {
        byte[] page;
        try (java.io.InputStream in = getResources().openRawResource(pageRes)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            page = out.toByteArray();
        } catch (java.io.IOException e) {
            return;
        }
        if (phoneServer != null) phoneServer.stop();
        File scratch = new File(getCacheDir(), "phone-upload.part");
        phoneServer = new PhoneServer(page, scratch, handler, () -> {
            if (phoneDialog != null) phoneDialog.dismiss();
            onDone.run();
        });
        List<String> ips = PhoneServer.lanAddresses();
        if (ips.isEmpty() || !phoneServer.start()) {
            phoneServer.stop();
            Toast.makeText(this, R.string.wp_send_failed, Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder urls = new StringBuilder();
        for (String ip : ips) {
            if (urls.length() > 0) urls.append('\n');
            urls.append("http://").append(ip).append(':').append(phoneServer.port).append('/').append(phoneServer.pin);
        }
        final PhoneServer running = phoneServer;
        phoneDialog = GlassMenu.message(this, getString(titleRes), getString(messageRes, urls.toString()),
                new String[]{getString(android.R.string.cancel)}, null);
        phoneDialog.setOnDismissListener(d -> running.stop());
    }

    // ---- Network panel: guest Wi-Fi QR code and data used (hold OK on the network pill) ----------

    private static final String WIFI_PREFS = "guest_wifi";

    void showNetworkPanel() {
        android.content.SharedPreferences wifi = getSharedPreferences(WIFI_PREFS, MODE_PRIVATE);
        String ssid = wifi.getString("ssid", "");
        String password = wifi.getString("password", "");
        String security = wifi.getString("security", "WPA");
        boolean hidden = wifi.getBoolean("hidden", false);

        android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_network);
        Sounds.attach(dlg);
        GlassMenu.track(dlg);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }

        ImageView qr = dlg.findViewById(R.id.qr);
        TextView qrEmpty = dlg.findViewById(R.id.qrEmpty);
        TextView netLabel = dlg.findViewById(R.id.netLabel);
        TextView netName = dlg.findViewById(R.id.netName);
        android.graphics.Bitmap bmp = ssid.isEmpty() ? null
                : qrBitmap(wifiQrText(ssid, password, security, hidden), (int) (216 * density));
        if (bmp != null) {
            qr.setImageBitmap(bmp);
            netLabel.setText(R.string.net_scan_label);
            netName.setText(ssid);
        } else {
            qr.setVisibility(View.GONE);
            qrEmpty.setVisibility(View.VISIBLE);
            netLabel.setText(R.string.net_guest_label);
            netName.setText(R.string.net_not_set_up);
        }

        DataUsage.Totals t = DataUsage.read(this);
        long since = DataUsage.since(this);
        java.util.Calendar startOfMonth = java.util.Calendar.getInstance();
        startOfMonth.set(java.util.Calendar.DAY_OF_MONTH, 1);
        startOfMonth.set(java.util.Calendar.HOUR_OF_DAY, 0);
        startOfMonth.set(java.util.Calendar.MINUTE, 0);
        String monthLabel = since > startOfMonth.getTimeInMillis()
                ? getString(R.string.net_since, new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(new java.util.Date(since)))
                : new java.text.SimpleDateFormat("MMMM", Locale.getDefault()).format(new java.util.Date());
        ((TextView) dlg.findViewById(R.id.dataToday)).setText(DataUsage.format(t.today));
        ((TextView) dlg.findViewById(R.id.dataMonth)).setText(DataUsage.format(t.month));
        String limit = DataLimit.panelLine(this, t.month);
        ((TextView) dlg.findViewById(R.id.dataMonthLabel)).setText(limit == null ? monthLabel : monthLabel + "  ·  " + limit);
        dlg.findViewById(R.id.btnDataLimit).setOnClickListener(v -> DataLimit.showSettings(this, () -> {
            dlg.dismiss();
            showNetworkPanel();
        }));
        ((TextView) dlg.findViewById(R.id.dataNote)).setText(t.mobile ? R.string.net_data_mobile : R.string.net_data_all);

        TextView details = dlg.findViewById(R.id.btnWifiDetails);
        details.setText(ssid.isEmpty() ? R.string.net_set_up : R.string.net_change);
        details.setOnClickListener(v -> {
            dlg.dismiss();
            showWifiSetup();
        });
        dlg.findViewById(R.id.btnWifiSettings).setOnClickListener(v -> {
            dlg.dismiss();
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        });
        View close = dlg.findViewById(R.id.btnClose);
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.setOnShowListener(d -> close.requestFocus());
        dlg.show();
    }

    /** Phone page for the Wi-Fi name and password behind the QR code. */
    private void showWifiSetup() {
        showPhoneLink(R.raw.wifi, R.string.net_setup_title, R.string.net_setup_message, body -> {
            byte[] bytes = java.nio.file.Files.readAllBytes(body.toPath());
            org.json.JSONObject j;
            try {
                j = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } catch (org.json.JSONException e) {
                return false;
            }
            String ssid = j.optString("ssid", "").trim();
            if (ssid.isEmpty() || ssid.length() > 64) return false;
            String sec = j.optString("security", "WPA");
            if (!sec.equals("WPA") && !sec.equals("WEP") && !sec.equals("nopass")) sec = "WPA";
            String password = j.optString("password", "");
            if (password.length() > 63) return false;   // the longest a Wi-Fi password can be
            getSharedPreferences(WIFI_PREFS, MODE_PRIVATE).edit()
                    .putString("ssid", ssid)
                    .putString("password", password)
                    .putString("security", sec)
                    .putBoolean("hidden", j.optBoolean("hidden", false))
                    .commit();
            return true;
        }, this::showNetworkPanel);
    }

    /** The standard Wi-Fi QR payload phones understand: WIFI:T:WPA;S:name;P:password;; */
    private static String wifiQrText(String ssid, String password, String security, boolean hidden) {
        StringBuilder s = new StringBuilder("WIFI:T:").append(security).append(";S:").append(qrEscape(ssid)).append(';');
        if (!"nopass".equals(security)) s.append("P:").append(qrEscape(password)).append(';');
        if (hidden) s.append("H:true;");
        return s.append(';').toString();
    }

    private static String qrEscape(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** Black-on-white QR code with a small quiet zone, scaled up without blurring. */
    static android.graphics.Bitmap qrBitmap(String text, int sizePx) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
            android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
            int scale = Math.max(1, sizePx / w);
            return android.graphics.Bitmap.createScaledBitmap(small, w * scale, h * scale, false);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDockChanged(String focusKey) {
        adapter.reload();
        grid.post(() -> {
            int pos = adapter.positionOf(focusKey);
            focusTile(Math.max(pos, 0));
        });
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Below the dock the app rows scroll up under the card's spot: the card steps aside.
        if (nowPlaying != null) nowPlaying.setSuppressed(position >= COLUMNS);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        // With several profiles, greet the person: "Good evening, Ada".
        // Greet the person by their profile's name: "Good evening, Ada".
        // The name stands out: heavier and brighter than the greeting.
        String name = Profiles.activeName(this);
        if (name.isEmpty()) {
            greeting.setText(getString(res).toUpperCase(Locale.getDefault()));
        } else {
            android.text.SpannableString sp = new android.text.SpannableString(
                    (getString(res) + ", " + name).toUpperCase(Locale.getDefault()));
            int from = sp.length() - name.toUpperCase(Locale.getDefault()).length();
            android.graphics.Typeface heavy = Fonts.weight(this, 800);
            sp.setSpan(new android.text.style.MetricAffectingSpan() {
                @Override
                public void updateMeasureState(android.text.TextPaint p) {
                    p.setTypeface(heavy);
                }

                @Override
                public void updateDrawState(android.text.TextPaint p) {
                    p.setTypeface(heavy);
                    p.setColor(Color.WHITE);
                }
            }, from, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            greeting.setText(sp);
        }
        Festive.Holiday holiday = Festive.today(this);
        if (holiday != null) greeting.setText(holiday.text.toUpperCase(Locale.getDefault()));   // "Happy Independence Day 🇳🇬" for the day
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean online = false, wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // Data left on the SIM, when it's known: "Ethernet · 12.4 GB left".
        String simLeft = SimData.left(this);
        if (simLeft != null && !text.equals(getString(R.string.offline))) text = text + " · " + getString(R.string.sim_left, simLeft);
        netText = text;
        android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(text);
        if (vpn) {
            // Network name, then a green lock-and-VPN capsule drawn in place of the word "VPN".
            String badge = getString(R.string.vpn);
            int at = label.length() + 1;
            label.append(' ').append(badge);
            label.setSpan(new VpnBadge(getDrawable(R.drawable.ic_lock), density),
                    at, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Connection health dot (NetHealth): green fine, amber slow or patchy, red not getting through.
        int dot = netHealth != null ? NetHealth.color(netHealth.state()) : 0;
        if (dot != 0 && !text.equals(getString(R.string.offline))) {
            int at = label.length() + 1;
            label.append(" •");
            label.setSpan(new NetHealth.Dot(dot, density), at, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        btnWifi.setText(label);
    }

    /** What the network pill says ("Ethernet", the Wi-Fi name, "Offline"), for the TV settings grid. */
    String netText = "";

    private final NetHealth netHealth = new NetHealth();

    private void checkNet() {
        netHealth.check(s -> {
            if (!isFinishing() && !isDestroyed()) updateWifiLabel();
        });
    }

    /** The first of these the box answers: its assistant, Google search, voice command, web search. */
    private Intent searchIntent() {
        String[] actions = {"android.intent.action.ASSIST", "android.search.action.GLOBAL_SEARCH",
                "android.intent.action.VOICE_COMMAND", "android.speech.action.WEB_SEARCH"};
        for (String a : actions) {
            Intent i = new Intent(a);
            if (i.resolveActivity(getPackageManager()) != null) return i;
        }
        return null;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        if (i != null) open(i);
        else tvSettings.networkFromPill();   // TV Home's glass Network page
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.title)).setTypeface(Fonts.weight(this, 500));
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
