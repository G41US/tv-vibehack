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
    /** The router's page password (the label's default unless changed) and which login form it took. */
    static final String KEY_ROUTER_USER = "router_user", KEY_ROUTER_PW = "router_password", KEY_HOW = "router_login2";
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
        // Known amount: every few hours; nothing yet: every half hour, or once a day when no way was found.
        long every = left(c) != null ? EVERY : note(c).contains("Router: ") ? 24 * EVERY / 3 : RETRY;
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
            save(c, null, note + lookAround(c));
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
        if (reply == null) return null;
        Matcher m = AMOUNT.matcher(reply);
        String first = null;
        while (m.find()) {
            int from = Math.max(reply.lastIndexOf(',', m.start()), reply.lastIndexOf('\n', m.start()));
            String label = reply.substring(from + 1, m.start());
            String tidy = tidy(Double.parseDouble(m.group(1).replace(',', '.')), m.group(2).toUpperCase(Locale.US));
            if (!SPECIAL.matcher(label).find()) return tidy;
            if (first == null) first = tidy;
        }
        return first;
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
        if (gw != null) for (String path : new String[]{"/", "/api/device/information", "/goform/goform_get_cmd_process?cmd=hardware_version,wa_inner_version,modem_main_state&multi_data=1",
                "/reqproc/proc_get?isTest=false&cmd=hardware_version,wa_inner_version,modem_main_state,sms_unread_num&multi_data=1"}) {
            out.append("\n> ").append(path).append(": ").append(peek(gw, path));
        }
        if (gw != null) out.append("\nAPI: ").append(apiWords(gw));
        return out.toString();
    }

    /** A router page's whole answer (headers and body, up to {@code max} bytes), via a plain socket. */
    private static String get(InetAddress host, String path, int max) throws java.io.IOException {
        try (java.net.Socket so = new java.net.Socket()) {
            so.connect(new java.net.InetSocketAddress(host, 80), 2000);
            so.setSoTimeout(3000);
            so.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nConnection: close\r\nHost: " + host.getHostAddress() + "\r\nReferer: http://"
                    + host.getHostAddress() + "/\r\nUser-Agent: Mozilla/5.0\r\n\r\n").getBytes("UTF-8"));
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                for (int n; got.size() < max && (n = so.getInputStream().read(buf)) > 0; ) got.write(buf, 0, n);
            } catch (java.net.SocketTimeoutException ignored) {
            }
            String r = RouterUssd.normalize(got.toString("UTF-8"));
            return r.contains("Transfer-Encoding: chunked") ? RouterUssd.unchunk(r) : r;
        }
    }

    /**
     * How the router's page talks to the router: the addresses its scripts call (login, SMS, USSD...),
     * found in the page and the scripts it loads.
     */
    private static String apiWords(InetAddress host) {
        try {
            String page = get(host, "/", 200_000);
            // Follow the page's redirect ("/" sends on to /index.html), then any login page it points to.
            for (int hop = 0; hop < 3; hop++) {
                Matcher loc = Pattern.compile("(?im)^Location:\\s*(?:https?://[^/\\s]+)?(/\\S*)").matcher(page);
                Matcher meta = Pattern.compile("(?i)(?:location(?:\\.href)?\\s*=|url=)\\s*[\"']?(/?[A-Za-z0-9_./-]+\\.(?:html?|asp|cgi))").matcher(page);
                String next = loc.find() ? loc.group(1) : page.length() < 3000 && meta.find() ? meta.group(1) : null;
                if (next == null) break;
                page = get(host, next.startsWith("/") ? next : "/" + next, 200_000);
            }
            // Crawl the page's scripts: RequireJS names modules as quoted words ('service', 'lib/jquery.cookie'),
            // loaded from the start script's folder; follow those that exist, a couple of levels deep.
            java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            Matcher main = Pattern.compile("data-main\\s*=\\s*[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(page);
            String start = main.find() ? "/" + main.group(1).replaceFirst("^/", "").replaceFirst("(\\.js)?$", ".js") : "/js/main.js";
            String base = start.substring(0, start.lastIndexOf('/') + 1);
            queue.add(start);
            Matcher src = Pattern.compile("src\\s*=\\s*[\"']([^\"'#?:]+\\.js)", Pattern.CASE_INSENSITIVE).matcher(page);
            while (src.find()) queue.add(src.group(1).startsWith("/") ? src.group(1) : "/" + src.group(1));
            Pattern word = Pattern.compile("[\"']((?:\\.{0,2}/)?[a-zA-Z][a-zA-Z0-9_\\-]*(?:/[a-zA-Z0-9_\\-.]+)*)[\"']");
            java.util.Set<String> tried = new java.util.HashSet<>();
            while (!queue.isEmpty() && tried.size() < 80 && files.size() < 30) {
                String f = queue.poll();
                if (!tried.add(f)) continue;
                String r = get(host, f, 900_000);
                if (!(r.startsWith("HTTP/1.1 200") || r.startsWith("HTTP/1.0 200"))) continue;
                String body = r.substring(Math.max(0, r.indexOf("\r\n\r\n")));
                files.put(f, body);
                if (body.length() > 300_000) continue;   // a library: its quoted words aren't modules
                Matcher w = word.matcher(body);
                while (w.find()) {
                    String m = w.group(1);
                    if (m.length() > 60 || m.contains("..") || m.endsWith(".html") || m.endsWith(".css") || m.endsWith(".png")) continue;
                    if (!m.contains("/") && !m.matches("[a-z][a-zA-Z0-9_]{2,30}")) continue;
                    queue.add(m.startsWith("/") ? (m.endsWith(".js") ? m : m + ".js") : base + m.replaceFirst("^\\./", "") + (m.endsWith(".js") ? "" : ".js"));
                }
            }
            // Where the scripts talk to the router: the text around each request and login.
            StringBuilder o = new StringBuilder("files");
            for (java.util.Map.Entry<String, String> e : files.entrySet()) o.append(' ').append(e.getKey()).append('=').append(e.getValue().length());
            java.util.Set<String> seen = new java.util.HashSet<>();
            Pattern hot = Pattern.compile("USSD|ussd");   // how the page sends a code (if it can)
            int snippets = 0;
            for (java.util.Map.Entry<String, String> e : files.entrySet()) {
                String b = e.getValue();
                if (b.length() > 300_000) continue;
                Matcher m = hot.matcher(b);
                while (m.find() && snippets < 40) {
                    int a = Math.max(0, m.start() - 70), z = Math.min(b.length(), m.end() + 90);
                    String snip = b.substring(a, z).replaceAll("\\s+", " ");
                    if (!seen.add(snip.substring(Math.max(0, snip.length() / 2 - 20), Math.min(snip.length(), snip.length() / 2 + 20)))) continue;
                    o.append("\n[").append(e.getKey()).append("] …").append(snip).append('…');
                    snippets++;
                }
            }
            return o.toString();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
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

    /** The start of a router page's answer (status, server, title or text), via a plain socket. */
    private static String peek(InetAddress host, String path) {
        try {
            String r = get(host, path, 6000);
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
