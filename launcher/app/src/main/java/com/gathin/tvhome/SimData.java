package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data left on the SIM, for the Network panel ("7.7 GB, left till 3 Nov"). Asked of the network with
 * *323#, Nigeria's data-balance code on every network, a few times a day, and read from the reply.
 *
 * Asked of Android when it holds the SIM; on a box whose separate router holds it (Airtel's: Android
 * only sees Ethernet), through the router's own page (RouterUssd), choosing "DATA Balance" in the menu.
 * TV check > SIM data shows the amount and the network's whole reply.
 */
final class SimData {

    private static final String PREFS = "sim_data", KEY_LEFT = "left_gb", KEY_AT = "at", KEY_NOTE = "note", KEY_UNTIL = "until",
            KEY_UNTIL_MS = "until_ms", KEY_LEFT_MB = "left_mb", KEY_STATE = "router_state", KEY_WARNED = "warned";
    private static final String CODE = "*323#", NO_SIM = "Android has no SIM";
    /** The router's page password (the label's default unless changed) and which login form it took. */
    static final String KEY_ROUTER_USER = "router_user", KEY_ROUTER_PW = "router_password", KEY_HOW = "router_login2";
    private static final long EVERY = 5 * 60 * 1000L, RETRY = 30 * 60 * 1000L, DAY = 24 * 60 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    private SimData() {
    }

    /** "12.4 GB", or null when not known. */
    /** When the bundle ends ("3 Nov"), or null. */
    static String until(Context c) {
        return prefs(c).getString(KEY_UNTIL, null);
    }

    static String left(Context c) {
        if (!StillWatching.isRouterBox(c)) return null;
        return prefs(c).getString(KEY_LEFT, null);
    }

    /** What was found the last time (for TV check). */
    static String note(Context c) {
        return prefs(c).getString(KEY_NOTE, "not checked yet");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Asks again if the last answer is old; {@code changed} runs on the main thread with a new amount. */
    static void refreshIfStale(Context ctx, Runnable changed) {
        refresh(ctx, changed, false);
    }

    /** A check is running now. */
    static boolean checking() {
        return busy;
    }

    /**
     * {@code soon}: someone is looking for the amount (the Network panel) and there is none yet, so ask now
     * unless a check started in the last minute.
     */
    /** Returns whether a check started ({@code changed} then runs when it ends, whatever the outcome). */
    static boolean refresh(Context ctx, Runnable changed, boolean soon) {
        Context c = ctx.getApplicationContext();
        if (!StillWatching.isRouterBox(c)) return false;   // only Airtel's router boxes for now
        long at = prefs(c).getLong(KEY_AT, 0), now = System.currentTimeMillis();
        boolean refused = note(c).contains("password not accepted");
        if (soon && !busy && !refused && (now - at > 60_000 || now < at)) at = 0;
        // Known amount: every 5 minutes while Home is in use; nothing yet: every half hour, or once a day when the router refused the
        // password (so it isn't asked into locking itself).
        long every = refused ? DAY : left(c) != null ? EVERY : RETRY;
        if (busy || (now - at < every && now >= at)) return false;
        busy = true;
        prefs(c).edit().putLong(KEY_AT, now).apply();
        new Thread(() -> {
            try {
                ask(c, changed);
            } catch (Throwable t) {
                save(c, null, "error: " + t);
                busy = false;
            }
            if (changed != null) MAIN.post(changed);   // done, answered or not
        }, "sim-data").start();
        return true;
    }

    @android.annotation.SuppressLint("MissingPermission")   // checked just before
    private static void ask(Context c, Runnable changed) {
        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        int sim = tm != null ? tm.getSimState() : TelephonyManager.SIM_STATE_UNKNOWN;
        if (sim != TelephonyManager.SIM_STATE_READY) {
            // The SIM is in the router: ask it through the router's own page, as a person would there.
            InetAddress gw = gateway(c);
            String note = NO_SIM + " (state " + sim + "): the router holds it.\n";
            if (gw != null) {
                SharedPreferences p = prefs(c);
                int[] how = {p.getInt(KEY_HOW, 0)};
                // *323# answers with a menu: choose "DATA Balance" ("ROUTER Balance" asks for a router number).
                // A second try a little later if the network was still busy with the last request.
                String last = null;
                for (String pick : new String[]{"DATA", "DATA"}) {
                    if (last == null && note.contains("(DATA)")) android.os.SystemClock.sleep(8000);
                    RouterUssd r = new RouterUssd(gw);
                    String reply = r.send(p.getString(KEY_ROUTER_USER, "admin"), p.getString(KEY_ROUTER_PW, "admin"), how, CODE, pick);
                    p.edit().putInt(KEY_HOW, how[0]).apply();
                    note += "Router " + gw.getHostAddress() + " (" + pick + "): " + r.log + "\n";
                    if (r.state != null) {
                        p.edit().putString(KEY_STATE, r.state.toString()).apply();
                        note += "Router state: " + r.state + "\n";   // what the Network panel's router line comes from
                    }
                    if (reply == null) continue;
                    last = reply;
                    String amount = amount(reply);
                    if (amount == null) break;   // answered, but with no amount: shown as it is
                    prefs(c).edit().putString(KEY_UNTIL, until(reply)).putLong(KEY_UNTIL_MS, untilMillis(reply))
                            .putLong(KEY_LEFT_MB, Math.round(mb(reply))).apply();
                    save(c, amount, note + CODE + " said: " + reply);
                    busy = false;
                    if (changed != null) MAIN.post(changed);
                    return;
                }
                if (last != null) {   // the network answered, but with no amount in it (no bundle?)
                    save(c, null, note + CODE + " said: " + last);
                    busy = false;
                    return;
                }
            }
            save(c, null, note);
            busy = false;
            return;
        }
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED && AdbSelf.ready(c)) {
            // Asking the network a code is a "phone" permission: given the way Set up on this TV gives the others.
            try (AdbSelf adb = AdbSelf.connect(c, SelfSetup.PORT, 10_000, () -> {
            })) {
                adb.shell("pm grant " + c.getPackageName() + " " + Manifest.permission.CALL_PHONE);
            } catch (Exception ignored) {
            }
        }
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            save(c, null, "SIM ready (" + tm.getSimOperatorName() + "), but no permission to dial " + CODE);
            busy = false;
            return;
        }
        MAIN.post(() -> {
            try {
                tm.sendUssdRequest(CODE, new TelephonyManager.UssdResponseCallback() {
                    @Override
                    public void onReceiveUssdResponse(TelephonyManager t, String req, CharSequence reply) {
                        String amount = amount(String.valueOf(reply));
                        save(c, amount, "SIM " + tm.getSimOperatorName() + ", " + CODE + " said: " + reply);
                        busy = false;
                        if (amount != null && changed != null) changed.run();
                    }

                    @Override
                    public void onReceiveUssdResponseFailed(TelephonyManager t, String req, int code) {
                        save(c, null, "SIM " + tm.getSimOperatorName() + ", " + CODE + " failed (" + code + ")");
                        busy = false;
                    }
                }, MAIN);
            } catch (Exception e) {
                save(c, null, CODE + " couldn't be sent: " + e);
                busy = false;
            }
        });
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB|KB)", Pattern.CASE_INSENSITIVE);

    /** Bundles for one app or the night only: not "data left" for everything. */
    private static final Pattern SPECIAL = Pattern.compile("youtube|night|social|whatsapp|instagram|tiktok|facebook|bonus|voice|sms",
            Pattern.CASE_INSENSITIVE);

    /**
     * The main data left in the network's reply, tidied ("Weekly Bundle: 7838.23MB till ..., YouTube Night:
     * 15360.06MB ..." gives "7.7 GB"): the first amount whose name isn't an app or night bundle, else the first.
     */
    static String amount(String reply) {
        Matcher m = chosen(reply);
        return m == null ? null : tidy(Double.parseDouble(m.group(1).replace(',', '.')), m.group(2).toUpperCase(Locale.US));
    }

    /** When that bundle ends ("... 7838.23MB till 03-11-2026 ..." gives "3 Nov"), or null. */
    static String until(String reply) {
        long ms = untilMillis(reply);
        return ms == 0 ? null : new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(new java.util.Date(ms));
    }

    /** The same date as a time (the end of that day), or 0. */
    static long untilMillis(String reply) {
        Matcher m = chosen(reply);
        if (m == null) return 0;
        int end = reply.indexOf(',', m.end());
        Matcher d = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})").matcher(reply.substring(m.end(), end < 0 ? reply.length() : end));
        if (!d.find()) return 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)) - 1, Integer.parseInt(d.group(1)), 23, 59, 0);
        return cal.getTimeInMillis();
    }

    /** The chosen amount in MB, or -1. */
    static double mb(String reply) {
        Matcher m = chosen(reply);
        if (m == null) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = m.group(2).toUpperCase(Locale.US);
        return u.equals("TB") ? v * 1024 * 1024 : u.equals("GB") ? v * 1024 : u.equals("KB") ? v / 1024 : v;
    }

    /** The amount to show: the first whose name isn't an app or night bundle, else the first. */
    private static Matcher chosen(String reply) {
        if (reply == null) return null;
        Matcher m = AMOUNT.matcher(reply);
        int first = -1;
        while (m.find()) {
            int from = Math.max(reply.lastIndexOf(',', m.start()), reply.lastIndexOf('\n', m.start()));
            if (!SPECIAL.matcher(reply.substring(from + 1, m.start())).find()) return m;
            if (first < 0) first = m.start();
        }
        if (first < 0) return null;
        m.find(first);
        return m;
    }

    /** 7838.23 MB as "7.7 GB", 512 MB as "512 MB". */
    static String tidy(double v, String unit) {
        double mb = unit.equals("TB") ? v * 1024 * 1024 : unit.equals("GB") ? v * 1024 : unit.equals("KB") ? v / 1024 : v;
        if (mb >= 1024) {
            double gb = mb / 1024;
            return (gb >= 100 ? String.valueOf(Math.round(gb)) : String.format(Locale.US, "%.1f", gb).replace(".0", "")) + " GB";
        }
        return Math.round(mb) + " MB";
    }

    private static void save(Context c, String left, String note) {
        SharedPreferences.Editor e = prefs(c).edit().putString(KEY_NOTE, note.length() > 20000 ? note.substring(0, 20000) : note);
        if (left != null) e.putString(KEY_LEFT, left);
        e.apply();
    }

    // ---- running-out warning ------------------------------------------------------------------

    private static final long LOW_MB = 1024;

    /**
     * Once a day at most: "Data running low" under 1 GB left, or "Data bundle ending" when it ends today
     * or tomorrow. Shown on Home after a check.
     */
    static void maybeWarn(android.app.Activity a) {
        if (left(a) == null) return;
        SharedPreferences p = prefs(a);
        long mb = p.getLong(KEY_LEFT_MB, -1), ends = p.getLong(KEY_UNTIL_MS, 0), now = System.currentTimeMillis();
        String kind = mb >= 0 && mb < LOW_MB ? "low" : ends > now && ends - now < 2 * DAY ? "ends" : null;
        if (kind == null) return;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        if ((today + kind).equals(p.getString(KEY_WARNED, ""))) return;
        p.edit().putString(KEY_WARNED, today + kind).apply();
        boolean low = kind.equals("low");
        GlassMenu.message(a, a.getString(low ? R.string.sd_low_title : R.string.sd_end_title),
                low ? a.getString(R.string.sd_low_msg, left(a)) : a.getString(R.string.sd_end_msg, until(a), left(a)),
                new String[]{a.getString(android.R.string.ok)}, null);
    }

    // ---- the router's state, for the Network panel ---------------------------------------------

    /** "4G  ▂▄▆█  −92 dBm  ·  2 devices": the router's network, signal and devices, or null. */
    static CharSequence routerLine(Context c) {
        if (!StillWatching.isRouterBox(c)) return null;
        org.json.JSONObject st;
        try {
            st = new org.json.JSONObject(prefs(c).getString(KEY_STATE, "{}"));
        } catch (Exception e) {
            return null;
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        String type = st.optString("network_type", "").toUpperCase(Locale.US);
        String gen = type.contains("NR") || type.contains("5G") ? "5G" : type.contains("LTE") || type.contains("4G") ? "4G"
                : type.matches(".*(WCDMA|HSPA|UMTS|3G).*") ? "3G" : type.matches(".*(GSM|EDGE|GPRS|2G).*") ? "2G" : type;
        if (!gen.isEmpty()) out.append(gen).append("  ");
        String bars = st.optString("signalbar", "");
        if (bars.matches("\\d")) {
            int n = Math.min(5, Integer.parseInt(bars));
            String[] glyph = {"▁", "▂", "▄", "▆", "█"};
            for (int i = 0; i < 5; i++) {
                int at = out.length();
                out.append(glyph[i]);
                if (i >= n) out.setSpan(new android.text.style.ForegroundColorSpan(0x40FFFFFF), at, out.length(), 0);
            }
            out.append("  ");
        }
        String dbm = st.optString("lte_rsrp", "");
        if (dbm.isEmpty()) dbm = st.optString("rssi", "");
        if (dbm.matches("-?\\d+")) out.append(dbm.replace("-", "−")).append(" dBm");
        String devices = st.optString("sta_count", "");
        if (devices.matches("\\d+")) out.append(out.length() > 0 ? "  ·  " : "").append(c.getString(R.string.sd_devices, Integer.parseInt(devices)));
        return out.length() == 0 ? null : out;
    }

    /** Messages kept in the router (unread, used and room for), from the last check: {unread, used, total}. */
    static int[] messageCounts(Context c) {
        try {
            org.json.JSONObject st = new org.json.JSONObject(prefs(c).getString(KEY_STATE, "{}"));
            return new int[]{st.optInt("sms_unread_num", 0), st.optInt("sms_dev_used", -1), st.optInt("sms_nv_total", -1)};
        } catch (Exception e) {
            return new int[]{0, -1, -1};
        }
    }

    // ---- the router's text messages ------------------------------------------------------------

    /** The router's messages (off the main thread); {@code done} gets them on the main thread, or null. */
    static void messages(Context ctx, java.util.function.Consumer<org.json.JSONArray> done) {
        Context c = ctx.getApplicationContext();
        new Thread(() -> {
            org.json.JSONArray list = null;
            try {
                RouterUssd r = router(c);
                if (r != null) list = r.messages();
            } catch (Exception ignored) {
            }
            final org.json.JSONArray got = list;
            MAIN.post(() -> done.accept(got));
        }, "router-sms").start();
    }

    /** Deletes the router's messages by id; {@code done} gets whether it worked, on the main thread. */
    static void deleteMessages(Context ctx, java.util.List<String> ids, java.util.function.Consumer<Boolean> done) {
        Context c = ctx.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                RouterUssd r = router(c);
                if (r != null) ok = r.delete(ids);
            } catch (Exception ignored) {
            }
            final boolean result = ok;
            MAIN.post(() -> done.accept(result));
        }, "router-sms-delete").start();
    }

    /** A logged-in connection to the router, or null. */
    private static RouterUssd router(Context c) throws Exception {
        InetAddress gw = gateway(c);
        if (gw == null) return null;
        SharedPreferences p = prefs(c);
        int[] how = {p.getInt(KEY_HOW, 0)};
        RouterUssd r = new RouterUssd(gw);
        return r.open(p.getString(KEY_ROUTER_USER, "admin"), p.getString(KEY_ROUTER_PW, "admin"), how) ? r : null;
    }

    /** The router's address (the default route's gateway), or null. */
    private static InetAddress gateway(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp != null) for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.getGateway() != null) return r.getGateway();
        } catch (Exception ignored) {
        }
        return null;
    }
}
