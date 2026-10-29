package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Join a Wi-Fi hotspot (a phone's, say) on boxes whose settings show no Wi-Fi (Airtel's router box
 * shows Ethernet only). Done the way Android's own shell tool does it, over TV Home's debugging
 * connection: switch Wi-Fi on, scan, list what's near, connect with the password.
 *
 * Passwords are remembered on the box. "Use <hotspot> instead of the router" switches the router's own
 * internet off (no Airtel data used) so Android moves to the hotspot; "Use the Airtel router" switches back.
 */
final class Hotspots {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Hotspots() {
    }

    /** A network nearby: name, strongest signal (dBm), security ("wpa2", "wpa3" or "open"). */
    static final class Net {
        String ssid, security;
        int rssi;
    }

    private static final String PREFS = "hotspots", KEY_LAST = "last", KEY_MODE = "use_hotspot";

    private static android.content.SharedPreferences prefs(android.content.Context c) {
        return c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private static android.net.ConnectivityManager.NetworkCallback keep;

    /**
     * Android disconnects Wi-Fi it thinks nobody needs: with the wired router up, a joined hotspot is
     * dropped after a few seconds. Asking for a Wi-Fi connection (and holding the request) keeps it up,
     * ready for the switch. Held by the Home button service, which runs all the time.
     */
    static synchronized void keepWifi(android.content.Context ctx) {
        if (keep != null || !StillWatching.isRouterBox(ctx)) return;
        try {
            android.net.ConnectivityManager cm = ctx.getApplicationContext().getSystemService(android.net.ConnectivityManager.class);
            keep = new android.net.ConnectivityManager.NetworkCallback();
            cm.requestNetwork(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), keep);
        } catch (Exception e) {
            keep = null;
        }
    }

    /** What Android says about the connections now (for when something doesn't work): Wi-Fi, default, who switched it. */
    private static void details(Activity a, Runnable back) {
        Dialog wait = GlassMenu.message(a, a.getString(R.string.hs_details), a.getString(R.string.sd_loading), new String[0], null);
        new Thread(() -> {
            String out;
            try (AdbSelf adb = shell(a)) {
                out = adb == null ? "(no debugging connection)" : adb.shell("cmd wifi status | head -4; echo; "
                        + "dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo\\{' | sed 's/LinkProperties.*Capabilities/ Caps/' | cut -c1-260 | head -6; echo; "
                        + "dumpsys wifi | grep -iE 'setWifiEnabled|disconnect|unwanted' | tail -12 | cut -c1-200");
            } catch (Exception e) {
                out = e.toString();
            }
            final String text = out;
            MAIN.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (!a.isFinishing()) GlassMenu.message(a, a.getString(R.string.hs_details), text,
                        new String[]{a.getString(android.R.string.ok)}, w -> back.run());
            });
        }, "hotspot-details").start();
    }

    /** The hotspot is in use instead of the router (its internet switched off). */
    static boolean usingHotspot(android.content.Context c) {
        return prefs(c).getBoolean(KEY_MODE, false);
    }

    /**
     * The hotspot page: which internet the TV uses (and a switch to the other), then what's near, with the
     * one connected and the ones whose password is remembered marked. Choosing one connects (asking the
     * password only the first time).
     */
    static void show(Activity a, Runnable back) {
        Dialog wait = GlassMenu.message(a, a.getString(R.string.hs_join), a.getString(R.string.hs_scanning), new String[0], null);
        new Thread(() -> {
            List<Net> nets = null;
            String connected = null;
            try (AdbSelf adb = shell(a)) {
                if (adb != null) {
                    nets = scan(adb);
                    Matcher m = Pattern.compile("connected to \"(.*?)\"").matcher(adb.shell("cmd wifi status"));
                    if (m.find()) connected = m.group(1);
                }
            } catch (Exception ignored) {
            }
            final List<Net> found = nets;
            final String now = connected;
            MAIN.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (a.isFinishing()) return;
                if (found == null) {
                    GlassMenu.message(a, a.getString(R.string.hs_join), a.getString(R.string.hs_no_wifi),
                            new String[]{a.getString(android.R.string.ok)}, w -> back.run());
                    return;
                }
                android.content.SharedPreferences p = prefs(a);
                String last = p.getString(KEY_LAST, null);
                Runnable again = () -> show(a, back);
                List<GlassMenu.Row> rows = new ArrayList<>();
                // Which internet: the router's (Airtel SIM) or the hotspot's, and the switch to the other.
                if (usingHotspot(a)) {
                    rows.add(new GlassMenu.Row(R.drawable.ic_s_data, a.getString(R.string.hs_use_router),
                            a.getString(R.string.hs_using, last != null ? last : "Wi-Fi"), () -> useRouter(a, again)));
                } else if (last != null && p.contains("pw_" + last)) {
                    rows.add(new GlassMenu.Row(R.drawable.ic_s_data, a.getString(R.string.hs_use_hotspot, last),
                            a.getString(R.string.hs_using_router), () -> useHotspot(a, last, again)));
                }
                rows.add(new GlassMenu.Row(R.drawable.ic_m_update, a.getString(R.string.hs_rescan), null, again));
                rows.add(new GlassMenu.Row(R.drawable.ic_s_tune, a.getString(R.string.hs_details), null, () -> details(a, again)));
                for (Net n : found) {
                    String bars = n.rssi >= -60 ? "▂▄▆█" : n.rssi >= -70 ? "▂▄▆" : n.rssi >= -80 ? "▂▄" : "▂";
                    boolean saved = p.contains("pw_" + n.ssid);
                    String state = n.ssid.equals(now) ? a.getString(R.string.hs_connected_now) : saved ? a.getString(R.string.hs_saved) : "";
                    rows.add(new GlassMenu.Row(R.drawable.ic_s_data, n.ssid,
                            (state.isEmpty() ? "" : state + "  ") + bars + (n.security.equals("open") || saved ? "" : "  🔒"), () -> {
                        if (n.security.equals("open")) connect(a, n, "", back);
                        else if (saved) connect(a, n, p.getString("pw_" + n.ssid, ""), back);
                        else GlassMenu.input(a, a.getString(R.string.hs_password, n.ssid), "", pw -> connect(a, n, pw, back));
                    }));
                }
                GlassMenu.page(a, a.getString(R.string.net_title), found.isEmpty() ? a.getString(R.string.hs_none) : a.getString(R.string.hs_join),
                        null, rows, back, 0);
            });
        }, "hotspots").start();
    }

    private static void connect(Activity a, Net n, String password, Runnable back) {
        Dialog wait = GlassMenu.message(a, n.ssid, a.getString(R.string.hs_connecting, n.ssid), new String[0], null);
        new Thread(() -> {
            boolean ok;
            try (AdbSelf adb = shell(a)) {
                ok = adb != null && join(adb, n.ssid, n.security, password);
            } catch (Exception e) {
                ok = false;
            }
            // Remembered on this box only, so it's not asked again (and the switch can rejoin it).
            if (ok) prefs(a).edit().putString("pw_" + n.ssid, password).putString("sec_" + n.ssid, n.security).putString(KEY_LAST, n.ssid).apply();
            else prefs(a).edit().remove("pw_" + n.ssid).apply();   // a wrong remembered password: ask next time
            final boolean joined = ok;
            MAIN.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (a.isFinishing()) return;
                GlassMenu.message(a, n.ssid, a.getString(joined ? R.string.hs_connected : R.string.hs_failed, n.ssid),
                        new String[]{a.getString(android.R.string.ok)}, w -> back.run());
            });
        }, "hotspot-join").start();
    }

    /** Connects to a network and waits up to 20 s for it. */
    private static boolean join(AdbSelf adb, String ssid, String security, String password) throws Exception {
        if (adb.shell("cmd wifi status").contains("Wifi is disabled")) adb.shell("cmd wifi set-wifi-enabled enabled");
        adb.shell("cmd wifi connect-network " + quote(ssid) + " " + security + (security.equals("open") ? "" : " " + quote(password)));
        for (int i = 0; i < 20; i++) {
            android.os.SystemClock.sleep(1000);
            String st = adb.shell("cmd wifi status");
            if (st.contains("connected to") && st.contains(ssid)) return true;
        }
        return false;
    }

    /**
     * Hotspot instead of the router: join the hotspot, switch the router's own internet off (no Airtel data
     * used), and tell Android the wired connection no longer reaches the internet so it moves to Wi-Fi now.
     */
    private static void useHotspot(Activity a, String ssid, Runnable done) {
        Dialog wait = GlassMenu.message(a, ssid, a.getString(R.string.hs_connecting, ssid), new String[0], null);
        new Thread(() -> {
            boolean ok = false;
            try (AdbSelf adb = shell(a)) {
                android.content.SharedPreferences p = prefs(a);
                if (adb != null && join(adb, ssid, p.getString("sec_" + ssid, "wpa2"), p.getString("pw_" + ssid, ""))) {
                    RouterUssd r = SimData.router(a);
                    ok = r != null && r.setInternet(false);
                }
            } catch (Exception ignored) {
            }
            if (ok) {
                prefs(a).edit().putBoolean(KEY_MODE, true).apply();
                recheckWired(a, false);
            }
            finish(a, wait, ssid, ok ? a.getString(R.string.hs_switched_hotspot, ssid) : a.getString(R.string.hs_switch_failed), done);
        }, "use-hotspot").start();
    }

    /** Back to the router: its internet on again; Android prefers the wired connection once it works. */
    private static void useRouter(Activity a, Runnable done) {
        String title = a.getString(R.string.hs_use_router);
        Dialog wait = GlassMenu.message(a, title, a.getString(R.string.sd_loading), new String[0], null);
        new Thread(() -> {
            boolean ok = false;
            try {
                RouterUssd r = SimData.router(a);
                ok = r != null && r.setInternet(true);
            } catch (Exception ignored) {
            }
            if (ok) {
                prefs(a).edit().putBoolean(KEY_MODE, false).apply();
                android.os.SystemClock.sleep(5000);   // the router reconnects to Airtel
                recheckWired(a, true);
            }
            finish(a, wait, title, a.getString(ok ? R.string.hs_switched_router : R.string.hs_switch_failed), done);
        }, "use-router").start();
    }

    /** Asks Android to check the wired connection again now (instead of minutes later). */
    private static void recheckWired(android.content.Context c, boolean works) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) cm.reportNetworkConnectivity(n, works);
            }
        } catch (Exception ignored) {
        }
    }

    private static void finish(Activity a, Dialog wait, String title, String message, Runnable done) {
        MAIN.post(() -> {
            if (wait.isShowing()) wait.dismiss();
            if (a.isFinishing()) return;
            GlassMenu.message(a, title, message, new String[]{a.getString(android.R.string.ok)}, w -> done.run());
        });
    }

    /** TV Home's debugging connection, or null when it can't be had. Call off the main thread. */
    private static AdbSelf shell(Activity a) throws Exception {
        if (!AdbSelf.ready(a)) return null;
        return AdbSelf.connect(a, SelfSetup.PORT, 10_000, () -> {
        });
    }

    /** Wi-Fi on, a fresh scan, then the networks heard (strongest first, one line per name); null: no Wi-Fi. */
    static List<Net> scan(AdbSelf adb) throws Exception {
        String st = adb.shell("cmd wifi status");
        if (st.contains("Unknown command") || st.contains("Can't find service")) return null;
        if (st.contains("Wifi is disabled")) {
            adb.shell("cmd wifi set-wifi-enabled enabled");
            for (int i = 0; i < 8 && adb.shell("cmd wifi status").contains("Wifi is disabled"); i++) android.os.SystemClock.sleep(500);
            if (adb.shell("cmd wifi status").contains("Wifi is disabled")) return null;
        }
        adb.shell("cmd wifi start-scan");
        android.os.SystemClock.sleep(5000);
        return parse(adb.shell("cmd wifi list-scan-results"));
    }

    /** "  aa:bb:..  2412  -45  1.2  My Phone  [WPA2-PSK-CCMP][ESS]" lines, as networks. */
    static List<Net> parse(String out) {
        Map<String, Net> best = new LinkedHashMap<>();
        Pattern line = Pattern.compile("^\\s*([0-9a-fA-F:]{17})\\s+\\d+\\s+(-?\\d+)\\s+\\S+\\s+(.*?)\\s*(\\[.*)?$");
        for (String l : out.split("\n")) {
            Matcher m = line.matcher(l);
            if (!m.find()) continue;
            String ssid = m.group(3).trim(), flags = m.group(4) != null ? m.group(4) : "";
            if (ssid.isEmpty() || flags.contains("WEP") || flags.contains("EAP")) continue;   // hidden, or not joinable here
            Net n = new Net();
            n.ssid = ssid;
            n.rssi = Integer.parseInt(m.group(2));
            n.security = flags.contains("SAE") && !flags.contains("PSK") ? "wpa3" : flags.contains("PSK") ? "wpa2" : "open";
            Net was = best.get(ssid);
            if (was == null || n.rssi > was.rssi) best.put(ssid, n);
        }
        List<Net> list = new ArrayList<>(best.values());
        list.sort((x, y) -> Integer.compare(y.rssi, x.rssi));
        return list;
    }

    /** A shell word in single quotes. */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
