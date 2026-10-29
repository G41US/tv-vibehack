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
 * Data left on the SIM, for the network pill ("Ethernet · 12.4 GB left"). Asked of the network with
 * *323#, Nigeria's data-balance code on every network, a few times a day, and read from the reply.
 *
 * That works when Android itself holds the SIM. On a box whose router part holds it (Android only sees
 * Ethernet), there is nothing to ask yet: TV check > SIM data then shows what this box has instead
 * (the router: its open ports and what its page says it is), to find the way in.
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
        // No SIM in Android: look at the router again once a day only.
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
        SharedPreferences.Editor e = prefs(c).edit().putString(KEY_NOTE, note.length() > 9000 ? note.substring(0, 9000) : note);
        if (left != null) e.putString(KEY_LEFT, left);
        e.apply();
    }

    /** Where else the SIM's details might be: the separate router (its open ports and what its page says it is). */
    private static String lookAround(Context c) {
        StringBuilder out = new StringBuilder();
        InetAddress gw = null;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            if (lp != null) for (RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.getGateway() != null) gw = r.getGateway();
        } catch (Exception ignored) {
        }
        out.append("Router: ").append(gw != null ? gw.getHostAddress() : "?").append(" open:");
        if (gw != null) {
            for (int port : new int[]{80, 443, 8080, 8443, 5000, 7547}) {
                try (java.net.Socket so = new java.net.Socket()) {
                    so.connect(new java.net.InetSocketAddress(gw, port), 1500);
                    out.append(' ').append(port);
                } catch (Exception ignored) {
                }
            }
        }
        // What the router's own page says about itself (its maker and model), asked plainly with a socket:
        // some router pages answer in ways Android's web client refuses.
        if (gw != null) for (String path : new String[]{"/", "/api/device/information", "/goform/goform_get_cmd_process?cmd=hardware_version,wa_inner_version,modem_main_state&multi_data=1"}) {
            out.append("\n> ").append(path).append(": ").append(peek(gw, path));
        }
        return out.toString();
    }

    /** The start of a router page's answer (status, server, title or text), via a plain socket. */
    private static String peek(InetAddress host, String path) {
        try (java.net.Socket so = new java.net.Socket()) {
            so.connect(new java.net.InetSocketAddress(host, 80), 2000);
            so.setSoTimeout(3000);
            so.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + host.getHostAddress() + "\r\nReferer: http://"
                    + host.getHostAddress() + "/\r\nUser-Agent: Mozilla/5.0\r\n\r\n").getBytes("UTF-8"));
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                for (int n; got.size() < 6000 && (n = so.getInputStream().read(buf)) > 0; ) got.write(buf, 0, n);
            } catch (java.net.SocketTimeoutException ignored) {
            }
            String r = got.toString("UTF-8");
            StringBuilder o = new StringBuilder(r.split("\r?\n", 2)[0]);
            Matcher m = Pattern.compile("(?im)^(Server|Location|WWW-Authenticate):.*$").matcher(r);
            while (m.find()) o.append(" | ").append(m.group().trim());
            Matcher t = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(r);
            if (t.find()) o.append(" | title: ").append(t.group(1).trim());
            int body = r.indexOf("\r\n\r\n");
            String rest = body >= 0 ? r.substring(body + 4).replaceAll("\\s+", " ").trim() : "";
            if (!rest.isEmpty()) o.append(" | ").append(rest.length() > 300 ? rest.substring(0, 300) + "…" : rest);
            return o.toString();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /** TV check's "Look again": forget the last look and look now. */
    static void lookNow(Context ctx, Runnable done) {
        Context c = ctx.getApplicationContext();
        prefs(c).edit().putLong(KEY_AT, 0).putString(KEY_NOTE, "looking…").apply();
        refreshIfStale(c, null);
        new Thread(() -> {
            for (int i = 0; i < 120 && busy; i++) android.os.SystemClock.sleep(500);
            MAIN.post(done);
        }, "sim-look").start();
    }
}
