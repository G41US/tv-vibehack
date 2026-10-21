package com.gathin.tvhome;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.widget.LinearLayout;

import java.io.File;
import java.net.Inet4Address;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The box's settings in TV Home's glass style (press OK on ⚙ Settings): a grid, and a glass page for
 * each area with what an app may read and change itself. Things only Android may do (join a Wi-Fi
 * network with its password, pair a remote, change the resolution or time zone, sign in) are rows
 * marked with an arrow that open that exact page of Android's settings.
 *
 * Switches that change system settings (ad-blocking DNS, animations, screensaver, automatic time) need
 * the WRITE_SECURE_SETTINGS grant the setup script gives; without it they say so instead.
 */
final class TvSettings {

    static final int REQUEST_BT = 41;
    static final String KEY_CLOCK = "clock";   // in the "look" prefs: "", "12" or "24"

    private final MainActivity act;

    TvSettings(MainActivity act) {
        this.act = act;
    }

    private String s(int id, Object... args) {
        return act.getString(id, args);
    }

    private ContentResolver cr() {
        return act.getContentResolver();
    }

    // ---- the grid ---------------------------------------------------------------------------------

    void show() {
        GlassMenu.Tile[] tiles = {
                new GlassMenu.Tile(R.drawable.ic_wifi, s(R.string.tv_network), act.netText, () -> {
                    networkBack = this::show;
                    network(0);
                }),
                new GlassMenu.Tile(R.drawable.ic_s_display, s(R.string.tv_picture), s(R.string.tv_picture_value),
                        () -> picture(0)),
                new GlassMenu.Tile(R.drawable.ic_s_remote, s(R.string.tv_remotes), s(R.string.tv_remotes_value),
                        () -> remotes(0)),
                new GlassMenu.Tile(R.drawable.ic_m_apps, s(R.string.tv_apps), s(R.string.tv_apps_value),
                        () -> apps(0)),
                new GlassMenu.Tile(R.drawable.ic_m_profile, s(R.string.tv_accounts), s(R.string.tv_accounts_value),
                        () -> openSettings(Settings.ACTION_SYNC_SETTINGS, Settings.ACTION_ADD_ACCOUNT)),
                new GlassMenu.Tile(R.drawable.ic_s_clock, s(R.string.tv_date),
                        TimeZone.getDefault().getID().replace('_', ' '), () -> dateTime(0)),
                new GlassMenu.Tile(R.drawable.ic_s_storage, s(R.string.tv_storage), storageFreeLine(),
                        () -> storage(0)),
                new GlassMenu.Tile(R.drawable.ic_s_tune, s(R.string.tv_all), s(R.string.tv_all_value),
                        () -> openSettings(Settings.ACTION_SETTINGS)),
        };
        GlassMenu.grid(act, s(R.string.menu_kicker), s(R.string.tv_title), tiles, 4);
    }

    private void page(int titleId, LinearLayout hero, List<GlassMenu.Row> rows, int focus) {
        GlassMenu.page(act, s(R.string.tv_title), s(titleId), hero, rows, this::show, focus);
    }

    /** The first of these settings pages the box has, or the main settings. */
    void openSettings(String... actions) {
        for (String a : actions) {
            Intent i = new Intent(a);
            if (act.getPackageManager().resolveActivity(i, 0) != null) {
                act.open(i);
                return;
            }
        }
        act.open(new Intent(Settings.ACTION_SETTINGS));
    }

    private boolean canWrite() {
        return SelfHeal.canHeal(act);
    }

    /** Runs the change, or explains that the setup script has to allow it first. */
    private void change(Runnable r, Runnable after) {
        if (!canWrite()) {
            GlassMenu.message(act, s(R.string.tv_needs_setup_title), s(R.string.tv_needs_setup),
                    new String[]{s(android.R.string.ok)}, w -> after.run());
            return;
        }
        try {
            r.run();
        } catch (SecurityException ignored) {
        }
        after.run();
    }

    private String onOff(boolean on) {
        return s(on ? R.string.tv_on : R.string.tv_off);
    }

    // ---- network ----------------------------------------------------------------------------------

    /** Where Back goes from the Network page: the grid, or nowhere when it came from the network pill. */
    private Runnable networkBack;

    /** Pressing OK on the network pill. */
    void networkFromPill() {
        networkBack = null;
        network(0);
    }

    void network(int focus) {
        NetHealth.State st = act.netState();
        String health = st == NetHealth.State.GOOD ? s(R.string.tv_net_good)
                : st == NetHealth.State.SLOW ? s(R.string.tv_net_slow)
                : st == NetHealth.State.DOWN ? s(R.string.tv_net_down) : null;
        String ip = ipAddress();
        LinearLayout hero = GlassMenu.hero(act, act.netText, health,
                ip != null ? s(R.string.tv_net_ip, ip) : null);

        String dns = Settings.Global.getString(cr(), "private_dns_mode");
        String host = Settings.Global.getString(cr(), "private_dns_specifier");
        boolean adblock = "hostname".equals(dns) && host != null && host.contains("adguard");
        boolean custom = "hostname".equals(dns) && !adblock;

        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_wifi, s(R.string.tv_net_guest), null, act::showNetworkPanel));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_shield, s(R.string.tv_net_adblock),
                custom ? host : onOff(adblock), () -> change(() -> {
                    if (adblock || custom) {
                        Settings.Global.putString(cr(), "private_dns_mode", "opportunistic");
                    } else {
                        Settings.Global.putString(cr(), "private_dns_specifier", "dns.adguard-dns.com");
                        Settings.Global.putString(cr(), "private_dns_mode", "hostname");
                    }
                }, () -> network(1))));
        int gb = DataLimit.gb(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_data, s(R.string.limit_title),
                gb <= 0 ? s(R.string.limit_none) : s(R.string.limit_gb, gb),
                () -> DataLimit.showSettings(act, () -> network(2))));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, s(R.string.tv_net_join), null,
                () -> openSettings(Settings.ACTION_WIFI_SETTINGS)).external());
        GlassMenu.page(act, s(R.string.tv_title), s(R.string.tv_network), hero, rows, networkBack, focus);
    }

    private String ipAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- picture & sound --------------------------------------------------------------------------

    private static final String[] SCALE_KEYS = {
            Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE};

    /** Row of the Picture & sound page to reopen once TV Home has redrawn at a new text size; -1 none. */
    static int reopenPicture = -1;

    void picture(int focus) {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        List<GlassMenu.Row> rows = new ArrayList<>();

        int text = TextScale.level(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_text, s(R.string.tv_text), s(TextScale.NAMES[text]), () -> {
            TextScale.setLevel(act, (text + 1) % TextScale.LEVELS.length);
            reopenPicture = 0;   // TV Home redraws at the new size, then this page comes back
            act.recreate();
        }));

        if (am != null && !am.isVolumeFixed()) {
            rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_volume), volume(am), null)
                    .stepper(dir -> {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                        return volume(am);
                    }));
        } else {
            rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_volume), s(R.string.tv_volume_tv), null));
        }

        boolean homeStyle = VolumeOverlay.enabled(act);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_vol_style),
                s(homeStyle ? R.string.tv_vol_style_home : R.string.tv_vol_style_android), () -> {
            act.getSharedPreferences(VolumeOverlay.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(VolumeOverlay.KEY_STYLE, homeStyle ? "android" : "").apply();
            picture(2);
        }));

        float scale = Settings.Global.getFloat(cr(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        int anim = scale <= 0.01f ? 2 : scale < 0.9f ? 1 : 0;
        String[] animNames = {s(R.string.tv_anim_normal), s(R.string.tv_anim_fast), s(R.string.tv_off)};
        rows.add(new GlassMenu.Row(R.drawable.ic_s_speed, s(R.string.tv_anim), animNames[anim], () -> change(() -> {
            float next = anim == 0 ? 0.5f : anim == 1 ? 0f : 1f;
            for (String k : SCALE_KEYS) Settings.Global.putFloat(cr(), k, next);
        }, () -> picture(3))));

        String mine = act.getPackageName() + "/" + ClockDream.class.getName();
        String comp = Settings.Secure.getString(cr(), "screensaver_components");
        boolean ssOn = Settings.Secure.getInt(cr(), "screensaver_enabled", 1) == 1;
        boolean ours = comp != null && (comp.startsWith(mine) || comp.startsWith(act.getPackageName() + "/.ClockDream"));
        String ssName = !ssOn ? s(R.string.tv_off) : ours ? s(R.string.tv_ss_clock) : s(R.string.tv_ss_other);
        rows.add(new GlassMenu.Row(R.drawable.ic_m_moon, s(R.string.tv_ss), ssName, () -> change(() -> {
            if (ssOn && ours) {
                Settings.Secure.putInt(cr(), "screensaver_enabled", 0);
            } else {
                Settings.Secure.putString(cr(), "screensaver_components", mine);
                Settings.Secure.putInt(cr(), "screensaver_enabled", 1);
            }
        }, () -> picture(4))));

        rows.add(new GlassMenu.Row(R.drawable.ic_s_display, s(R.string.tv_resolution), null,
                () -> openSettings(Settings.ACTION_DISPLAY_SETTINGS)).external());
        rows.add(new GlassMenu.Row(R.drawable.ic_s_volume, s(R.string.tv_sound_out), null,
                () -> openSettings(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)).external());
        page(R.string.tv_picture, null, rows, focus);
    }

    private String volume(AudioManager am) {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        return Math.round(cur * 100f / max) + "%";
    }

    // ---- remotes & bluetooth ----------------------------------------------------------------------

    void remotes(int focus) {
        List<GlassMenu.Row> rows = new ArrayList<>();
        LinearLayout hero = null;
        boolean allowed = Build.VERSION.SDK_INT < 31
                || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        BluetoothManager bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null) {
            hero = GlassMenu.hero(act, s(R.string.tv_bt_none));
        } else if (!allowed) {
            hero = GlassMenu.hero(act, s(R.string.tv_remotes), s(R.string.tv_bt_ask));
            rows.add(new GlassMenu.Row(R.drawable.ic_s_remote, s(R.string.tv_bt_show), null,
                    () -> act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT)));
        } else {
            try {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                List<BluetoothDevice> list = new ArrayList<>(bonded != null ? bonded : new HashSet<>());
                for (BluetoothDevice d : list) {
                    String name = d.getName() != null ? d.getName() : d.getAddress();
                    boolean on = callBool(d, "isConnected");
                    int battery = callInt(d, "getBatteryLevel");
                    String value = s(on ? R.string.tv_bt_connected : R.string.tv_bt_paired)
                            + (on && battery >= 0 ? "  ·  " + battery + "%" : "");
                    rows.add(new GlassMenu.Row(R.drawable.ic_s_remote, name, value, () -> forget(d, name)));
                }
                if (list.isEmpty()) hero = GlassMenu.hero(act, s(R.string.tv_bt_empty));
            } catch (SecurityException e) {
                hero = GlassMenu.hero(act, s(R.string.tv_bt_none));
            }
        }
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, s(R.string.tv_bt_pair), null,
                () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)).external());
        page(R.string.tv_remotes, hero, rows, focus);
    }

    private void forget(BluetoothDevice d, String name) {
        GlassMenu.message(act, name, s(R.string.tv_bt_forget_msg),
                new String[]{s(R.string.tv_bt_keep), s(R.string.tv_bt_forget)}, w -> {
                    if (w == 1) {
                        try {
                            d.getClass().getMethod("removeBond").invoke(d);
                        } catch (Exception ignored) {
                        }
                        act.getWindow().getDecorView().postDelayed(() -> remotes(0), 800);
                    } else {
                        remotes(0);
                    }
                });
    }

    private static boolean callBool(Object o, String method) {
        try {
            return Boolean.TRUE.equals(o.getClass().getMethod(method).invoke(o));
        } catch (Exception e) {
            return false;
        }
    }

    private static int callInt(Object o, String method) {
        try {
            Object r = o.getClass().getMethod(method).invoke(o);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- apps -------------------------------------------------------------------------------------

    private List<String> launchablePackages() {
        PackageManager pm = act.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(act.getPackageName()) || !seen.add(p)) continue;
                out.add(p);
            }
        }
        return out;
    }

    void apps(int focus) {
        PackageManager pm = act.getPackageManager();
        List<String> pkgs = launchablePackages();
        List<String[]> named = new ArrayList<>();
        for (String p : pkgs) {
            try {
                named.add(new String[]{String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))), p});
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Collections.sort(named, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        List<GlassMenu.Row> rows = new ArrayList<>();
        for (int i = 0; i < named.size(); i++) {
            String label = named.get(i)[0], p = named.get(i)[1];
            final int at = i;
            rows.add(new GlassMenu.Row(appIcon(p), label, version(p), () -> app(p, () -> apps(at))));
        }
        page(R.string.tv_apps, null, rows, focus);
    }

    private Object appIcon(String pkg) {
        try {
            return act.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return R.drawable.ic_m_apps;
        }
    }

    private String version(String pkg) {
        try {
            String v = act.getPackageManager().getPackageInfo(pkg, 0).versionName;
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private long appSize(ApplicationInfo ai) {
        long n = new File(ai.sourceDir).length();
        if (ai.splitSourceDirs != null) for (String sp : ai.splitSourceDirs) n += new File(sp).length();
        return n;
    }

    /** One app: version, size and when it was updated; open it, uninstall it, or Android's app page. */
    void app(String pkg, Runnable back) {
        PackageManager pm = act.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            back.run();
            return;
        }
        ApplicationInfo ai = pi.applicationInfo;
        String label = String.valueOf(pm.getApplicationLabel(ai));
        String updated = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(pi.lastUpdateTime));
        LinearLayout hero = GlassMenu.hero(act, label,
                pi.versionName != null ? s(R.string.tv_app_version, pi.versionName) : null,
                s(R.string.tv_app_size, Formatter.formatShortFileSize(act, appSize(ai))),
                s(R.string.tv_app_updated, updated));

        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_s_play, s(R.string.tv_app_open), null, () -> {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) act.open(i);
        }));
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            rows.add(new GlassMenu.Row(R.drawable.ic_s_delete, s(R.string.tv_app_uninstall), null,
                    () -> act.open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)))).external());
        }
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, s(R.string.tv_app_more), null,
                () -> act.open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))))
                .external());
        GlassMenu.page(act, s(R.string.tv_apps), label, hero, rows, back, 0);
    }

    // ---- date & time ------------------------------------------------------------------------------

    void dateTime(int focus) {
        Date now = new Date();
        TimeZone tz = TimeZone.getDefault();
        int off = tz.getOffset(now.getTime()) / 60000;
        String gmt = String.format(Locale.US, "GMT%s%02d:%02d", off < 0 ? "-" : "+", Math.abs(off) / 60, Math.abs(off) % 60);
        LinearLayout hero = GlassMenu.hero(act, DateFormat.getTimeFormat(act).format(now),
                new SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(now),
                tz.getDisplayName(tz.inDaylightTime(now), TimeZone.LONG) + "  ·  " + gmt);

        List<GlassMenu.Row> rows = new ArrayList<>();
        boolean auto = Settings.Global.getInt(cr(), Settings.Global.AUTO_TIME, 1) == 1;
        rows.add(new GlassMenu.Row(R.drawable.ic_s_clock, s(R.string.tv_date_auto), onOff(auto), () -> change(() -> {
            Settings.Global.putInt(cr(), Settings.Global.AUTO_TIME, auto ? 0 : 1);
            Settings.Global.putInt(cr(), Settings.Global.AUTO_TIME_ZONE, auto ? 0 : 1);
        }, () -> dateTime(0))));

        SharedPreferences look = act.getSharedPreferences("look", Context.MODE_PRIVATE);
        String clock = look.getString(KEY_CLOCK, "");
        String clockName = "24".equals(clock) ? s(R.string.tv_clock_24) : "12".equals(clock) ? s(R.string.tv_clock_12)
                : s(R.string.tv_clock_system);
        rows.add(new GlassMenu.Row(R.drawable.ic_s_clock, s(R.string.tv_clock), clockName, () -> {
            String next = "".equals(clock) ? "12" : "12".equals(clock) ? "24" : "";
            look.edit().putString(KEY_CLOCK, next).apply();
            act.applyClockStyle();
            dateTime(1);
        }));
        rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, s(R.string.tv_date_zone), null,
                () -> openSettings(Settings.ACTION_DATE_SETTINGS)).external());
        page(R.string.tv_date, hero, rows, focus);
    }

    // ---- storage ----------------------------------------------------------------------------------

    String storageFreeLine() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            return s(R.string.tv_storage_value, Formatter.formatShortFileSize(act, fs.getAvailableBytes()));
        } catch (Exception e) {
            return "";
        }
    }

    void storage(int focus) {
        LinearLayout hero;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes(), used = total - free;
            hero = GlassMenu.hero(act, s(R.string.tv_storage_value, Formatter.formatShortFileSize(act, free)),
                    s(R.string.tv_storage_of, Formatter.formatShortFileSize(act, total),
                            Math.round(used * 100f / Math.max(1, total))));
            hero.addView(GlassMenu.meter(act, used / (float) Math.max(1, total)));
        } catch (Exception e) {
            hero = null;
        }

        PackageManager pm = act.getPackageManager();
        List<Object[]> sized = new ArrayList<>();
        for (String p : launchablePackages()) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                sized.add(new Object[]{appSize(ai), String.valueOf(pm.getApplicationLabel(ai)), p});
            } catch (Exception ignored) {
            }
        }
        Collections.sort(sized, (a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        List<GlassMenu.Row> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(6, sized.size()); i++) {
            Object[] x = sized.get(i);
            String p = (String) x[2];
            final int at = i;
            rows.add(new GlassMenu.Row(appIcon(p), (String) x[1], Formatter.formatShortFileSize(act, (Long) x[0]),
                    () -> app(p, () -> storage(at))));
        }
        rows.add(new GlassMenu.Row(R.drawable.ic_s_storage, s(R.string.tv_storage_more), null,
                () -> openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS))
                .external());
        page(R.string.tv_storage, hero, rows, focus);
    }
}
