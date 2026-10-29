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

    private static final String PREFS = "sim_data", KEY_LEFT = "left_gb", KEY_AT = "at", KEY_NOTE = "note", KEY_UNTIL = "until";
    private static final String CODE = "*323#", NO_SIM = "Android has no SIM";
    /** The router's page password (the label's default unless changed) and which login form it took. */
    static final String KEY_ROUTER_USER = "router_user", KEY_ROUTER_PW = "router_password", KEY_HOW = "router_login2";
    private static final long EVERY = 3 * 60 * 60 * 1000L, RETRY = 30 * 60 * 1000L;
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

    /**
     * {@code soon}: someone is looking for the amount (the Network panel) and there is none yet, so ask now
     * unless a check started in the last minute.
     */
    static void refresh(Context ctx, Runnable changed, boolean soon) {
        Context c = ctx.getApplicationContext();
        long at = prefs(c).getLong(KEY_AT, 0), now = System.currentTimeMillis();
        if (soon && !busy && (now - at > 60_000 || now < at)) at = 0;
        // Known amount: every few hours; nothing yet: every half hour, or once a day when the router refused the
        // password (so it isn't asked into locking itself).
        long every = left(c) != null ? EVERY : note(c).contains("password not accepted") ? 24 * EVERY / 3 : RETRY;
        if (busy || (now - at < every && now >= at)) return;
        busy = true;
        prefs(c).edit().putLong(KEY_AT, now).apply();
        new Thread(() -> {
            try {
                ask(c, changed);
            } catch (Throwable t) {
                save(c, null, "error: " + t);
                busy = false;
            }
        }, "sim-data").start();
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
                    if (reply == null) continue;
                    last = reply;
                    String amount = amount(reply);
                    if (amount == null) break;   // answered, but with no amount: shown as it is
                    prefs(c).edit().putString(KEY_UNTIL, until(reply)).apply();
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
        Matcher m = chosen(reply);
        if (m == null) return null;
        int end = reply.indexOf(',', m.end());
        Matcher d = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})").matcher(reply.substring(m.end(), end < 0 ? reply.length() : end));
        if (!d.find()) return null;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)) - 1, Integer.parseInt(d.group(1)));
        return new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(cal.getTime());
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
