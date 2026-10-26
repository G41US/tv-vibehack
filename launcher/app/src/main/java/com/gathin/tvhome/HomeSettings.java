package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * TV Home's own settings in one place (hold OK on ⚙): Look, Weather, Profiles, Night & sleep, Music,
 * Sound & remote, Get apps, Updates & setup. Each tile opens a glass page; Back returns to the grid.
 * The box's own settings stay under ⚙ Settings (press OK).
 */
final class HomeSettings {

    static final String PREFS = "look", KEY_DOCK = "dock_size";
    /** Dock sizes offered in Look; every row of apps follows it. */
    static final int[] DOCK_SIZES = {3, 4, 5, 6};
    /** Row of the Look page to reopen after TV Home redraws at a new text size; -1 none. */
    static int reopenLook = -1;

    private final MainActivity act;

    HomeSettings(MainActivity act) {
        this.act = act;
    }

    private String s(int id, Object... args) {
        return act.getString(id, args);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean flag(Context c, String key) {
        return prefs(c).getBoolean(key, true);
    }

    private static void flip(Context c, String key) {
        prefs(c).edit().putBoolean(key, !flag(c, key)).apply();
    }

    static boolean weatherFxOn(Context c) {
        return flag(c, "weather_fx") && !SafeStart.on(c);
    }

    static boolean moodOn(Context c) {
        return flag(c, "mood") && !SafeStart.on(c);
    }

    static boolean autoPlayerOn(Context c) {
        return flag(c, "np_auto");
    }

    static boolean lyricsOn(Context c) {
        return flag(c, "lyrics_on");
    }

    private String onOff(boolean on) {
        return s(on ? R.string.tv_on : R.string.tv_off);
    }

    private void page(int titleId, List<GlassMenu.Row> rows, int focus) {
        GlassMenu.page(act, s(R.string.menu_title), s(titleId), null, rows, this::grid, focus);
    }

    // ---- the grid ---------------------------------------------------------------------------------

    void grid() {
        GlassMenu.Tile[] tiles = {
                new GlassMenu.Tile(R.drawable.ic_m_wallpaper, s(R.string.hs_look), act.wallpaperName(), () -> look(0)),
                new GlassMenu.Tile(R.drawable.ic_m_weather, s(R.string.menu_weather), act.weatherName(),
                        act::showWeatherMenu),
                new GlassMenu.Tile(R.drawable.ic_m_profile, s(R.string.menu_profiles), Profiles.activeName(act),
                        act::showProfilesMenu),
                new GlassMenu.Tile(R.drawable.ic_m_moon, s(R.string.hs_night), nightName(), () -> night(0)),
                new GlassMenu.Tile(R.drawable.ic_s_sound, s(R.string.hs_music), s(R.string.hs_music_value), () -> music(0)),
                new GlassMenu.Tile(R.drawable.ic_s_volume, s(R.string.hs_sound), s(R.string.hs_sound_value), () -> sound(0)),
                new GlassMenu.Tile(R.drawable.ic_m_apps, s(R.string.menu_apps),
                        s(AppPack.smartTubeInstalled(act) ? R.string.apps_value_done : R.string.apps_value),
                        () -> AppPack.show(act, act.updater)),
                new GlassMenu.Tile(R.drawable.ic_m_update, s(R.string.hs_setup),
                        s(R.string.updates_value, String.valueOf(act.installedBuild())), () -> setup(0)),
        };
        GlassMenu.grid(act, s(R.string.menu_kicker), s(R.string.menu_title), tiles, 4);
    }

    // ---- pages ------------------------------------------------------------------------------------

    void look(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_m_wallpaper, s(R.string.menu_wallpaper), act.wallpaperName(),
                act::showWallpaperMenu));
        boolean motion = LiveWallpaper.enabled(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, s(R.string.hs_motion), onOff(motion), () -> {
            LiveWallpaper.setEnabled(act, !motion);
            reopenLook = 1;
            act.recreate();
        }));
        boolean fx = weatherFxOn(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_weather, s(R.string.hs_weather_fx), onOff(fx), () -> {
            flip(act, "weather_fx");
            reopenLook = 2;
            act.recreate();
        }));
        int text = TextScale.level(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_text, s(R.string.tv_text), s(TextScale.NAMES[text]), () -> {
            TextScale.setLevel(act, (text + 1) % TextScale.LEVELS.length);
            reopenLook = 3;   // TV Home redraws at the new size, then this page comes back
            act.recreate();
        }));
        boolean festive = Festive.on(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_wallpaper, s(R.string.hs_festive), onOff(festive), () -> {
            prefs(act).edit().putBoolean("festive", !festive).apply();
            reopenLook = 4;
            act.recreate();
        }));
        int dock = MainActivity.COLUMNS;
        rows.add(new GlassMenu.Row(R.drawable.ic_m_apps, s(R.string.hs_dock_size), s(R.string.hs_dock_size_value, dock), () -> {
            int i = 0;
            for (int k = 0; k < DOCK_SIZES.length; k++) if (DOCK_SIZES[k] == dock) i = k;
            prefs(act).edit().putInt(KEY_DOCK, DOCK_SIZES[(i + 1) % DOCK_SIZES.length]).apply();
            reopenLook = 5;   // TV Home redraws with the new dock, then this page comes back
            act.recreate();
        }));
        page(R.string.hs_look, rows, focus);
    }

    private String nightName() {
        int start = Night.start(act);
        if (start == 0) return s(R.string.tv_off);
        java.text.DateFormat tf = DateFormat.getTimeFormat(act);
        Calendar end = Calendar.getInstance();
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.HOUR_OF_DAY, Night.END);
        Calendar from = Calendar.getInstance();
        if (start == Night.SUNSET) {
            int ss = Night.sunsetMinutes(act);
            from.set(Calendar.HOUR_OF_DAY, ss / 60);
            from.set(Calendar.MINUTE, ss % 60);
            return s(R.string.tv_night_sunset, tf.format(from.getTime())) + " – " + tf.format(end.getTime());
        }
        from.set(Calendar.HOUR_OF_DAY, start);
        from.set(Calendar.MINUTE, 0);
        return tf.format(from.getTime()) + " – " + tf.format(end.getTime());
    }

    void night(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        int start = Night.start(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_moon, s(R.string.tv_night), nightName(), () -> {
            int i = 0;
            for (int k = 0; k < Night.STARTS.length; k++) if (Night.STARTS[k] == start) i = k;
            Night.setStart(act, Night.STARTS[(i + 1) % Night.STARTS.length]);
            act.refreshNight();
            night(0);
        }));
        int cap = Night.cap(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_night_cap),
                cap <= 0 ? s(R.string.tv_night_cap_none) : cap + "%", () -> {
            int i = 0;
            for (int k = 0; k < Night.CAPS.length; k++) if (Night.CAPS[k] == cap) i = k;
            Night.setCap(act, Night.CAPS[(i + 1) % Night.CAPS.length]);
            Night.enforceCap(act);
            night(1);
        }));
        int still = StillWatching.hours(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_pw_sleep, s(R.string.menu_still),
                still <= 0 ? s(R.string.still_off) : s(R.string.still_value, still), () -> StillWatching.showSettings(act)));
        page(R.string.hs_night, rows, focus);
    }

    void music(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        boolean small = prefs(act).getBoolean(NowPlaying.KEY_MINI, false);
        rows.add(new GlassMenu.Row(R.drawable.ic_np_small, s(R.string.hs_np_size),
                s(small ? R.string.hs_np_small : R.string.hs_np_full), () -> {
            prefs(act).edit().putBoolean(NowPlaying.KEY_MINI, !small).apply();
            act.refreshNowPlaying();
            music(0);
        }));
        boolean auto = autoPlayerOn(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_np_big, s(R.string.hs_np_auto), onOff(auto), () -> {
            flip(act, "np_auto");
            music(1);
        }));
        boolean lyrics = lyricsOn(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_np_lyrics, s(R.string.hs_lyrics), onOff(lyrics), () -> {
            flip(act, "lyrics_on");
            music(2);
        }));
        boolean mood = moodOn(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_wallpaper, s(R.string.hs_mood), onOff(mood), () -> {
            flip(act, "mood");
            act.refreshNowPlaying();
            music(3);
        }));
        page(R.string.hs_music, rows, focus);
    }

    void sound(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        boolean snd = Sounds.enabled(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_sound, s(R.string.tv_sounds), onOff(snd), () -> {
            Sounds.setEnabled(act, !snd);
            if (!snd) Sounds.select(act);   // a sample of what they sound like
            sound(0);
        }));
        boolean homeStyle = VolumeOverlay.enabled(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_vol_style),
                s(homeStyle ? R.string.tv_vol_style_home : R.string.tv_vol_style_android), () -> {
            act.getSharedPreferences(VolumeOverlay.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(VolumeOverlay.KEY_STYLE, homeStyle ? "android" : "").apply();
            sound(1);
        }));
        boolean quick = QuickPanel.enabled(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, s(R.string.tv_quick), onOff(quick), () -> {
            QuickPanel.setEnabled(act, !quick);
            sound(2);
        }));
        page(R.string.hs_sound, rows, focus);
    }

    void setup(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_m_update, s(R.string.hs_check_updates),
                s(R.string.updates_value, String.valueOf(act.installedBuild())), () -> act.updater.check(true)));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_setup, s(R.string.ss_menu_quick), null, act.setupPanel::show));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_play, s(R.string.ss_menu_self), null, () -> SelfSetup.show(act)));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_shield, s(R.string.chk_title), null, () -> TvCheck.show(act, false)));
        page(R.string.hs_setup, rows, focus);
    }
}
