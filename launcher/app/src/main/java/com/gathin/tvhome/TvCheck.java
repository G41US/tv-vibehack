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
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TV check: tests this TV and shows ✓ or ✗ for each part of TV Home, so a problem on any TV (a TCL,
 * a friend's box) shows at a glance, in one photo. Anything not working opens the fix. "Send a report"
 * shows a QR code: the phone opens a GitHub issue already filled in, so support for a new TV can be added.
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
            boolean debug = AdbSelf.ready(a);   // network work: off the main thread (switches it on if TV Home keeps it off)
            main.post(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) build(a, afterSetup, debug);
            });
        }, "tv-check").start();
    }

    private static void build(Activity a, boolean afterSetup, boolean debug) {
        PackageManager pm = a.getPackageManager();
        List<GlassMenu.Row> rows = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        Runnable fix = () -> SelfSetup.show(a);

        boolean service = SetupPanel.serviceOn(a);
        line(report, "Home button service", service);
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
        report.append("- Home screen: ").append(homePkg.isEmpty() ? "picker" : homePkg)
                .append(takeover.contains(homePkg) || homePkg.equals(a.getPackageName()) ? " (handled)" : " (NOT HANDLED)").append('\n');

        boolean heal = SelfHeal.canHeal(a);
        line(report, "Self-repair", heal);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_shield, a.getString(R.string.chk_heal),
                heal ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), heal ? null : fix));

        boolean installs = SetupPanel.installsAllowed(a);
        line(report, "Installs", installs);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_update, a.getString(R.string.setup_updates_title),
                installs ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), installs ? null : fix));

        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        String vol;
        if (am != null && am.isVolumeFixed()) vol = "–  " + a.getString(R.string.chk_vol_tv);
        else if (a.getSharedPreferences(VolumeOverlay.PREFS, Context.MODE_PRIVATE).getString(VolumeOverlay.KEY_RECT, null) != null)
            vol = ok(a.getString(R.string.chk_vol_found));
        else vol = ok(a.getString(R.string.chk_vol_default));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, a.getString(R.string.tv_volume), vol, null));
        report.append("- Volume: ").append(am != null && am.isVolumeFixed() ? "fixed (TV controls it)"
                : vol.startsWith("✓") && vol.contains(a.getString(R.string.chk_vol_found)) ? "bar found" : "bar not seen yet").append('\n');

        String listeners = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
        boolean np = listeners != null && listeners.contains(new ComponentName(a, MediaListener.class).flattenToString());
        line(report, "Now playing", np);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_sound, a.getString(R.string.np_now_playing),
                np ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), np ? null : fix));

        int hdmi = 0;
        TvInputManager tim = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo i : tim.getTvInputList()) if (i.isPassthroughInput()) hdmi++;
        }
        rows.add(new GlassMenu.Row(R.drawable.ic_input, a.getString(R.string.chk_inputs),
                hdmi > 0 ? ok(a.getString(R.string.chk_inputs_n, hdmi)) : "–  " + a.getString(R.string.chk_inputs_none), null));

        report.append("- HDMI inputs: ").append(hdmi).append('\n');
        boolean bt = Build.VERSION.SDK_INT < 31
                || a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        rows.add(new GlassMenu.Row(R.drawable.ic_s_remote, a.getString(R.string.tv_remotes),
                bt ? ok(a.getString(R.string.chk_on)) : no(a.getString(R.string.chk_off)), bt ? null : fix));

        line(report, "Bluetooth", bt);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, a.getString(R.string.ss_step_debug),
                debug ? ok(a.getString(R.string.chk_on)) : "–  " + a.getString(R.string.chk_off), fix));
        line(report, "Debugging (port 5555)", debug);
        report.append("- Debugging only when needed: ").append(AdbSelf.guardOn(a) ? "on" : "off").append('\n');
        report.append("- Start-up animation: ").append(Splash.on(a) ? Splash.last(a) : "off").append('\n');
        report.append("- Last start-up: ").append(BootTrace.text(a)).append('\n');
        report.append("- Faster start-up: ").append(StartupApps.state(a)).append('\n');

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String build;
        try {
            build = String.valueOf(pm.getPackageInfo(a.getPackageName(), 0).getLongVersionCode());
        } catch (Exception e) {
            build = "?";
        }
        String problem = SafeStart.lastProblem(a);
        String text = "**" + Build.MANUFACTURER + " " + Build.MODEL + "** (" + Build.DEVICE + ")\n"
                + "- Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), TV Home build " + build + "\n"
                + "- Language/region: " + java.util.Locale.getDefault().toLanguageTag() + "\n"
                + report + (problem != null ? "- Last problem: " + problem + "\n" : "")
                + "\nWhat isn't working? (write here)\n";
        String trace = BootTrace.text(a) + "\n\n" + a.getString(R.string.hs_startup) + ": " + (Splash.on(a) ? Splash.last(a) : "off")
                + "\n" + a.getString(R.string.fs_title) + ": " + StartupApps.state(a)
                + (SafeStart.lastProblem(a) != null ? "\nLast problem: " + SafeStart.lastProblem(a) : "");
        String simLeft = SimData.left(a);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, a.getString(R.string.chk_sim), simLeft != null ? simLeft : "–",
                () -> simPage(a)));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, a.getString(R.string.chk_startup), null,
                () -> GlassMenu.message(a, a.getString(R.string.chk_startup), trace, new String[]{a.getString(android.R.string.ok)}, w -> {
                })));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_open, a.getString(R.string.chk_report), a.getString(R.string.chk_report_value),
                () -> showReport(a, Build.MANUFACTURER + " " + Build.MODEL, text)));
        LinearLayout hero = GlassMenu.hero(a, Build.MANUFACTURER + " " + Build.MODEL,
                a.getString(R.string.chk_hero, Build.VERSION.RELEASE, build,
                        String.format(java.util.Locale.US, "%.1f", mi.totalMem / 1073741824.0)),
                afterSetup ? a.getString(R.string.chk_after_setup)
                        : problem != null ? a.getString(R.string.chk_last_problem, problem) : null);
        GlassMenu.page(a, a.getString(R.string.menu_setup), a.getString(R.string.chk_title), hero, rows, null, 0);
    }

    private static void line(StringBuilder r, String what, boolean on) {
        r.append("- ").append(what).append(": ").append(on ? "on" : "OFF").append('\n');
    }

    /** A QR code for the phone: a new GitHub issue with this report filled in. Nothing leaves the TV itself. */
    /** What was found about the SIM; "Look again" looks now and shows the new findings. */
    private static void simPage(Activity a) {
        GlassMenu.message(a, a.getString(R.string.chk_sim), SimData.note(a),
                new String[]{a.getString(R.string.chk_look_again), a.getString(R.string.chk_on_phone), a.getString(android.R.string.ok)}, w -> {
                    if (w == 0) SimData.lookNow(a, () -> {
                        if (!a.isFinishing()) simPage(a);
                    });
                    if (w == 1) onPhone(a, a.getString(R.string.chk_sim), SimData.note(a));
                });
    }

    /** Shows a long text on a phone on the same Wi-Fi (to copy and send), while this message is open. */
    private static void onPhone(Activity a, String title, String text) {
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + android.text.TextUtils.htmlEncode(title) + "</title><body style='font:14px system-ui;padding:16px;background:#0b0d14;color:#e8e9ef'>"
                + "<button onclick=\"var r=document.createRange();r.selectNodeContents(document.getElementById('t'));var s=getSelection();s.removeAllRanges();s.addRange(r);document.execCommand('copy')\" style='padding:12px 18px;font-size:16px'>Copy</button>"
                + "<pre id=t style='white-space:pre-wrap;word-break:break-word'>" + android.text.TextUtils.htmlEncode(text) + "</pre>";
        PhoneServer server = new PhoneServer(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new java.io.File(a.getCacheDir(), "phone-text.part"), body -> false, () -> {
        });
        java.util.List<String> ips = PhoneServer.lanAddresses();
        if (ips.isEmpty() || !server.start()) {
            server.stop();
            return;
        }
        String url = "http://" + ips.get(0) + ":" + server.port + "/" + server.pin;
        android.app.Dialog d = GlassMenu.message(a, title, a.getString(R.string.chk_on_phone_msg, url),
                new String[]{a.getString(android.R.string.ok)}, w -> {
                });
        d.setOnDismissListener(x -> server.stop());
    }

    private static void showReport(Activity a, String model, String text) {
        String url = "https://github.com/G41US/tv-vibehack/issues/new?title=" + Uri.encode("TV check: " + model)
                + "&body=" + Uri.encode(text);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView qr = new ImageView(a);
        qr.setImageBitmap(MainActivity.qrBitmap(url, GlassMenu.dp(a, 300)));
        body.addView(qr, new LinearLayout.LayoutParams(GlassMenu.dp(a, 300), GlassMenu.dp(a, 300)));
        TextView hint = new TextView(a);
        hint.setText(R.string.chk_report_hint);
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, GlassMenu.dp(a, 16), 0, 0);
        body.addView(hint);
        GlassMenu.panel(a, a.getString(R.string.chk_title), a.getString(R.string.chk_report), body, 520,
                () -> show(a, false));
    }
}
