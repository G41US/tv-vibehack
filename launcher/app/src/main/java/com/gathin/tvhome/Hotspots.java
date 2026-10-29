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
 * Android keeps using the wired router while it has internet; when it hasn't (data finished), the
 * hotspot takes over by itself. Unplugging the router's cable makes the TV use the hotspot at once.
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

    /** Scans and shows what's near; choosing one asks its password and connects. */
    static void show(Activity a, Runnable back) {
        Dialog wait = GlassMenu.message(a, a.getString(R.string.hs_join), a.getString(R.string.hs_scanning), new String[0], null);
        new Thread(() -> {
            List<Net> nets = null;
            try (AdbSelf adb = shell(a)) {
                if (adb != null) nets = scan(adb);
            } catch (Exception ignored) {
            }
            final List<Net> found = nets;
            MAIN.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (a.isFinishing()) return;
                if (found == null) {
                    GlassMenu.message(a, a.getString(R.string.hs_join), a.getString(R.string.hs_no_wifi),
                            new String[]{a.getString(android.R.string.ok)}, w -> back.run());
                    return;
                }
                List<GlassMenu.Row> rows = new ArrayList<>();
                rows.add(new GlassMenu.Row(R.drawable.ic_m_update, a.getString(R.string.hs_rescan), null, () -> show(a, back)));
                for (Net n : found) {
                    String bars = n.rssi >= -60 ? "▂▄▆█" : n.rssi >= -70 ? "▂▄▆" : n.rssi >= -80 ? "▂▄" : "▂";
                    rows.add(new GlassMenu.Row(R.drawable.ic_s_data, n.ssid, bars + (n.security.equals("open") ? "" : "  🔒"), () -> {
                        if (n.security.equals("open")) connect(a, n, "", back);
                        else GlassMenu.input(a, a.getString(R.string.hs_password, n.ssid), "", pw -> connect(a, n, pw, back));
                    }));
                }
                GlassMenu.page(a, a.getString(R.string.net_title), found.isEmpty() ? a.getString(R.string.hs_none) : a.getString(R.string.hs_join),
                        null, rows, back, found.isEmpty() ? 0 : 1);
            });
        }, "hotspots").start();
    }

    private static void connect(Activity a, Net n, String password, Runnable back) {
        Dialog wait = GlassMenu.message(a, n.ssid, a.getString(R.string.hs_connecting, n.ssid), new String[0], null);
        new Thread(() -> {
            boolean ok = false;
            try (AdbSelf adb = shell(a)) {
                if (adb != null) {
                    adb.shell("cmd wifi connect-network " + quote(n.ssid) + " " + n.security
                            + (n.security.equals("open") ? "" : " " + quote(password)));
                    for (int i = 0; i < 20 && !ok; i++) {
                        android.os.SystemClock.sleep(1000);
                        String st = adb.shell("cmd wifi status");
                        ok = st.contains("connected to") && st.contains(n.ssid);
                    }
                }
            } catch (Exception ignored) {
            }
            final boolean joined = ok;
            MAIN.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (a.isFinishing()) return;
                GlassMenu.message(a, n.ssid, a.getString(joined ? R.string.hs_connected : R.string.hs_failed, n.ssid),
                        new String[]{a.getString(android.R.string.ok)}, w -> back.run());
            });
        }, "hotspot-join").start();
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
