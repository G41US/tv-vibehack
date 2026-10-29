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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data left on the SIM, for the network pill ("Ethernet · 12.4 GB left"). Asked of the network with
 * *323#, Nigeria's data-balance code on every network, a few times a day, and read from the reply.
 *
 * That works when Android itself holds the SIM. On a box whose router part holds it (Android only sees
 * Ethernet), there is nothing to ask yet: TV check > SIM data then shows what this box has instead
 * (the router's page, Android's modem settings, the firmware's own apps), to find the way in.
 */
final class SimData {

    private static final String PREFS = "sim_data", KEY_LEFT = "left", KEY_AT = "at", KEY_NOTE = "note";
    private static final String CODE = "*323#", NO_SIM = "Android has no SIM";
    private static final long EVERY = 3 * 60 * 60 * 1000L, RETRY = 30 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    private SimData() {
    }

    /** "12.4 GB", or null when not known. */
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
        Context c = ctx.getApplicationContext();
        long at = prefs(c).getLong(KEY_AT, 0), now = System.currentTimeMillis();
        // No SIM in Android: look again once a day only (it switches debugging on to look around).
        long every = left(c) != null ? EVERY : note(c).startsWith(NO_SIM) ? 24 * EVERY / 3 : RETRY;
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
            save(c, null, NO_SIM + " (state " + sim + "): the router part holds it.\n" + lookAround(c));
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

    /** The first data amount in the network's reply ("Your data bal: 2.5GB exp 05/11"), tidied: "2.5 GB". */
    static String amount(String reply) {
        if (reply == null) return null;
        Matcher m = AMOUNT.matcher(reply);
        if (!m.find()) return null;
        return m.group(1).replace(',', '.') + " " + m.group(2).toUpperCase(Locale.US);
    }

    private static void save(Context c, String left, String note) {
        SharedPreferences.Editor e = prefs(c).edit().putString(KEY_NOTE, note.length() > 1500 ? note.substring(0, 1500) : note);
        if (left != null) e.putString(KEY_LEFT, left);
        e.apply();
    }

    /** Where else the SIM's details might be: the router's page, Android's modem settings, the firmware's apps. */
    private static String lookAround(Context c) {
        StringBuilder out = new StringBuilder();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            InetAddress gw = null;
            if (lp != null) for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.getGateway() != null) gw = r.getGateway();
            out.append("Router: ").append(gw != null ? gw.getHostAddress() : "?");
            if (gw != null) {
                HttpURLConnection con = (HttpURLConnection) new URL("http://" + gw.getHostAddress() + "/").openConnection();
                con.setConnectTimeout(3000);
                con.setReadTimeout(3000);
                try {
                    int code = con.getResponseCode();
                    String page = "";
                    try (InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream()) {
                        if (in != null) {
                            byte[] buf = new byte[8192];
                            int len = in.read(buf);
                            page = len > 0 ? new String(buf, 0, len, "UTF-8") : "";
                        }
                    }
                    Matcher t = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(page);
                    out.append(" page ").append(code).append(" \"").append(t.find() ? t.group(1).trim() : "").append("\" server ")
                            .append(con.getHeaderField("Server"));
                } finally {
                    con.disconnect();
                }
            }
        } catch (Exception e) {
            out.append(" (").append(e.getClass().getSimpleName()).append(')');
        }
        if (AdbSelf.ready(c)) {
            try (AdbSelf adb = AdbSelf.connect(c, SelfSetup.PORT, 10_000, () -> {
            })) {
                out.append("\nModem: ").append(adb.shell("getprop | grep -iE 'gsm\\.|ril\\.|modem|radio' | head -8"));
                out.append("\nFirmware apps: ").append(adb.shell("pm list packages | grep -iE 'nes|airtel|router|modem' | head -12"));
                out.append("\nTheir data: ").append(adb.shell(
                        "for p in $(pm list packages | grep -iE 'nes|airtel' | cut -d: -f2); do dumpsys package $p | grep -iE 'authority|Provider\\{' | head -4; done | head -16"));
            } catch (Exception e) {
                out.append("\n(debugging: ").append(e.getClass().getSimpleName()).append(')');
            }
        }
        return out.toString();
    }
}
