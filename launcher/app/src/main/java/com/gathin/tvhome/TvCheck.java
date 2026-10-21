package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TV check: tests this TV and shows ✓ or ✗ for each part of TV Home, so a problem on any TV (a TCL,
 * a friend's box) shows at a glance, in one photo. Anything not working opens the fix.
 */
final class TvCheck {

    private TvCheck() {
    }

    private static String ok(String s) {
        return "✓  " + s;
    }

    private static String no(String s) {
        return "✗  " + s;
    }

    static void show(Activity a, boolean afterSetup) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            boolean debug = AdbSelf.reachable(SelfSetup.PORT);   // network work: off the main thread
            main.post(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) build(a, afterSetup, debug);
            });
        }, "tv-check").start();
    }

    private static void build(Activity a, boolean afterSetup, boolean debug) {
        PackageManager pm = a.getPackageManager();
        List<GlassMenu.Row> rows = new ArrayList<>();
        Runnable fix = () -> SelfSetup.show(a);

        boolean service = SetupPanel.serviceOn(a);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_apps, a.getString(R.string.chk_home_button),
                service ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), service ? null : fix));

        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        String homePkg = home != null && home.activityInfo != null ? home.activityInfo.packageName : "";
        List<String> takeover = new ArrayList<>();
        for (String t : a.getResources().getStringArray(R.array.takeover_packages)) {
            takeover.add(t.contains("/") ? t.substring(0, t.indexOf('/')) : t);
        }
        String homeValue;
        if (homePkg.equals(a.getPackageName())) homeValue = ok(a.getString(R.string.chk_home_ours));
        else if (takeover.contains(homePkg)) homeValue = ok(a.getString(R.string.chk_home_replaced, homePkg));
        else if (homePkg.isEmpty() || "android".equals(homePkg)) homeValue = ok(a.getString(R.string.chk_home_picker));
        else homeValue = no(a.getString(R.string.chk_home_unknown, homePkg));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_setup, a.getString(R.string.chk_home_screen), homeValue, null));

        boolean heal = SelfHeal.canHeal(a);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_shield, a.getString(R.string.chk_heal),
                heal ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), heal ? null : fix));

        boolean installs = SetupPanel.installsAllowed(a);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_update, a.getString(R.string.setup_updates_title),
                installs ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), installs ? null : fix));

        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        String vol;
        if (am != null && am.isVolumeFixed()) vol = "–  " + a.getString(R.string.chk_vol_tv);
        else if (a.getSharedPreferences(VolumeOverlay.PREFS, Context.MODE_PRIVATE).getString("volume_rect", null) != null)
            vol = ok(a.getString(R.string.chk_vol_found));
        else vol = ok(a.getString(R.string.chk_vol_default));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, a.getString(R.string.tv_volume), vol, null));

        String listeners = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
        boolean np = listeners != null && listeners.contains(new ComponentName(a, MediaListener.class).flattenToString());
        rows.add(new GlassMenu.Row(R.drawable.ic_s_sound, a.getString(R.string.np_now_playing),
                np ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), np ? null : fix));

        int hdmi = 0;
        TvInputManager tim = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo i : tim.getTvInputList()) if (i.isPassthroughInput()) hdmi++;
        }
        rows.add(new GlassMenu.Row(R.drawable.ic_input, a.getString(R.string.chk_inputs),
                hdmi > 0 ? ok(a.getString(R.string.chk_inputs_n, hdmi)) : "–  " + a.getString(R.string.chk_inputs_none), null));

        boolean bt = Build.VERSION.SDK_INT < 31
                || a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        rows.add(new GlassMenu.Row(R.drawable.ic_s_remote, a.getString(R.string.tv_remotes),
                bt ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), bt ? null : fix));

        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, a.getString(R.string.ss_step_debug),
                debug ? ok(a.getString(R.string.chk_on)) : "–  " + a.getString(R.string.chk_off), fix));

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String build;
        try {
            build = String.valueOf(pm.getPackageInfo(a.getPackageName(), 0).getLongVersionCode());
        } catch (Exception e) {
            build = "?";
        }
        LinearLayout hero = GlassMenu.hero(a, Build.MANUFACTURER + " " + Build.MODEL,
                a.getString(R.string.chk_hero, Build.VERSION.RELEASE, build,
                        String.format(java.util.Locale.US, "%.1f", mi.totalMem / 1073741824.0)),
                afterSetup ? a.getString(R.string.chk_after_setup)
                        : SafeStart.lastProblem(a) != null ? a.getString(R.string.chk_last_problem, SafeStart.lastProblem(a)) : null);
        GlassMenu.page(a, a.getString(R.string.menu_setup), a.getString(R.string.chk_title), hero, rows, null, 0);
    }
}
