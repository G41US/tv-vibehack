package com.gathin.tvhome;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a USSD code (*323#) through a ZTE-made 4G router, the kind Airtel supplies (its page is served
 * by "Demo-Webs" and asks /reqproc/proc_get, or /goform/goform_get_cmd_process on older ones), the way
 * the router's own page does: log in, send the code, wait for the network's reply, read it.
 *
 * Firmwares differ in how they want the password (two ways are tried, and the one that works is kept)
 * and some sign each request (AD); both are handled. Plain sockets: these pages answer in ways
 * Android's web client sometimes refuses.
 */
final class RouterUssd {

    /** What happened, step by step (for TV check), and the network's reply when there is one. */
    final StringBuilder log = new StringBuilder();
    String reply;

    private final InetAddress host;
    private String get, post, cookie = "", ad;
    private int lastStatus;

    RouterUssd(InetAddress host) {
        this.host = host;
    }

    /** Logs in and sends the code; returns the network's reply text, or null (see log). */
    String send(String user, String password, int[] how, String code) {
        try {
            if (status(request("GET", "/reqproc/proc_get?isTest=false&cmd=LD&_=" + System.currentTimeMillis(), null)) == 200) {
                get = "/reqproc/proc_get";
                post = "/reqproc/proc_post";
            } else {
                get = "/goform/goform_get_cmd_process";
                post = "/goform/goform_set_cmd_process";
            }
            log.append("api ").append(get);
            if (!login(user, password, how)) return null;
            signing();
            JSONObject sent = json(postForm("goformId=USSD_PROCESS&USSD_operator=ussd_send&USSD_send_number="
                    + URLEncoder.encode(code, "UTF-8") + "&notCallback=true"));
            log.append(", sent ").append(sent);
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1500);
                JSONObject r = json(read("ussd_write_flag,ussd_data_info,ussd_data,ussd_action,ussd_dcs"));
                String data = r.optString("ussd_data", "");
                JSONObject info = r.optJSONObject("ussd_data_info");
                if (data.isEmpty() && info != null) data = info.optString("ussd_data", "");
                String flag = r.optString("ussd_write_flag", "");
                if (!data.isEmpty()) {
                    reply = decode(data, null);
                    log.append(", reply after ").append((i + 1) * 3 / 2).append("s");
                    break;
                }
                if (i == 19) log.append(", last flag ").append(flag);
            }
            postForm("goformId=USSD_PROCESS&USSD_operator=ussd_cancel");   // close the session on the network
            if (reply == null) log.append(", no reply");
            return reply;
        } catch (Exception e) {
            log.append(", ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
            return null;
        }
    }

    /**
     * The router page's own login (its com.js): username and password, Base64-encoded when the firmware
     * says so (PASSWORD_ENCODE), plain otherwise; both are tried and the one that works is kept
     * ({@code how[0]}: 0 unknown, 1 Base64, 2 plain). Result 0 is logged in, 4 already was.
     */
    private boolean login(String user, String password, int[] how) throws Exception {
        int[] order = how[0] == 2 ? new int[]{2, 1} : new int[]{1, 2};
        for (int way : order) {
            String u = way == 1 ? b64(user) : user, pw = way == 1 ? b64(password) : password;
            String body = postForm("goformId=LOGIN&username=" + URLEncoder.encode(u, "UTF-8") + "&password=" + URLEncoder.encode(pw, "UTF-8"));
            JSONObject r = json(body);
            log.append(", login ").append(way).append('=').append(lastStatus).append(' ').append(r.optString("result", r.toString()));
            if ("0".equals(r.optString("result")) || "4".equals(r.optString("result"))) {
                how[0] = way;
                return true;
            }
            if ("3".equals(r.optString("result")) && way == order[1]) break;   // wrong password both ways
        }
        String info = read("loginfo");
        log.append(", loginfo ").append(info.length() > 60 ? info.substring(0, 60) : info);
        if (info.contains("\"ok\"")) return true;
        log.append(" (password not accepted)");
        return false;
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    /** Firmware that signs requests: AD = md5(md5(version + cr_version) + RD), fresh for each request. */
    private void signing() throws Exception {
        JSONObject v = json(read("wa_inner_version,cr_version"));
        String a = v.optString("wa_inner_version", ""), b = v.optString("cr_version", "");
        if (a.isEmpty()) return;
        ad = md5(a + b);
        log.append(", signs");
    }

    private String read(String cmd) throws IOException {
        return body(request("GET", get + "?isTest=false&cmd=" + cmd + (cmd.contains(",") ? "&multi_data=1" : "") + "&_=" + System.currentTimeMillis(), null));
    }

    private String postForm(String form) throws Exception {
        String extra = "";
        if (ad != null) extra = "&AD=" + md5(ad + json(read("RD")).optString("RD", ""));
        return body(request("POST", post, "isTest=false&" + form + extra));
    }

    private String request(String method, String path, String form) throws IOException {
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress(host, 80), 3000);
            so.setSoTimeout(5000);
            String h = host.getHostAddress();
            StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nConnection: close\r\nHost: ").append(h)
                    .append("\r\nReferer: http://").append(h).append("/index.html\r\nX-Requested-With: XMLHttpRequest\r\n")
                    .append("User-Agent: Mozilla/5.0\r\nAccept: application/json, text/javascript, */*\r\n");
            if (!cookie.isEmpty()) req.append("Cookie: ").append(cookie).append("\r\n");
            byte[] body = form != null ? form.getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (form != null) req.append("Content-Type: application/x-www-form-urlencoded; charset=UTF-8\r\nContent-Length: ")
                    .append(body.length).append("\r\n");
            req.append("\r\n");
            so.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            so.getOutputStream().write(body);
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                for (int n; got.size() < 200_000 && (n = so.getInputStream().read(buf)) > 0; ) got.write(buf, 0, n);
            } catch (SocketTimeoutException ignored) {
            }
            String r = got.toString("UTF-8");
            lastStatus = status(r);
            if (r.contains("Transfer-Encoding: chunked")) r = unchunk(r);
            Matcher c = Pattern.compile("(?im)^Set-Cookie:\\s*([^;\\r\\n]+)").matcher(r);
            while (c.find()) cookie = c.group(1).trim();   // the session (newer firmware keeps it in a cookie)
            return r;
        }
    }

    /** A chunked HTTP/1.1 answer as one body. */
    static String unchunk(String r) {
        int at = r.indexOf("\r\n\r\n");
        if (at < 0) return r;
        StringBuilder out = new StringBuilder(r.substring(0, at + 4));
        int i = at + 4;
        while (i < r.length()) {
            int eol = r.indexOf("\r\n", i);
            if (eol < 0) break;
            int len;
            try {
                len = Integer.parseInt(r.substring(i, eol).split(";")[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (len == 0) break;
            out.append(r, eol + 2, Math.min(r.length(), eol + 2 + len));
            i = eol + 2 + len + 2;
        }
        return out.toString();
    }

    private static int status(String response) {
        Matcher m = Pattern.compile("^HTTP/\\d\\.\\d (\\d{3})").matcher(response);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String body(String response) {
        int at = response.indexOf("\r\n\r\n");
        return at >= 0 ? response.substring(at + 4).trim() : "";
    }

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("raw", s.length() > 120 ? s.substring(0, 120) : s);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    /** The network's reply: plain text, or hex UCS-2 (two bytes a letter) as these routers usually pass it on. */
    static String decode(String data, String unused) {
        String d = data.trim();
        if (d.matches("[0-9A-Fa-f]+") && d.length() % 4 == 0) {
            byte[] b = new byte[d.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(d.substring(2 * i, 2 * i + 2), 16);
            return new String(b, StandardCharsets.UTF_16BE);
        }
        try {
            return java.net.URLDecoder.decode(d, "UTF-8");
        } catch (Exception e) {
            return d;
        }
    }

    private static String sha256(String s) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.US);
    }

    private static String md5(String s) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
