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

    /** The page's own meanings of ussd_write_flag (from its com.js). */
    private static final java.util.Map<String, String> FLAGS = new java.util.HashMap<>();

    static {
        FLAGS.put("1", "no service");
        FLAGS.put("2", "network ended it");
        FLAGS.put("3", "timed out");
        FLAGS.put("4", "timed out");
        FLAGS.put("10", "try again");
        FLAGS.put("41", "not supported");
        FLAGS.put("99", "USSD not supported");
        FLAGS.put("unknown", "timed out");
        FLAGS.put("", "no answer");
    }

    private final InetAddress host;
    private String get, post, cookie = "", ad;
    private int lastStatus;
    private String lastHead = "";

    RouterUssd(InetAddress host) {
        this.host = host;
    }

    /** The router's state after the last send (signal, network, devices, messages), or null. */
    JSONObject state;

    /** Finds the router's API, opens its page and logs in (see log). */
    boolean open(String user, String password, int[] how) throws Exception {
        if (status(request("GET", "/reqproc/proc_get?isTest=false&cmd=LD&_=" + System.currentTimeMillis(), null)) == 200) {
            get = "/reqproc/proc_get";
            post = "/reqproc/proc_post";
        } else {
            get = "/goform/goform_get_cmd_process";
            post = "/goform/goform_set_cmd_process";
        }
        log.append("api ").append(get);
        // The page first: some firmware only answers its requests for a browser that has it open (session cookie).
        String page = request("GET", "/index.html", null);
        log.append(", page ").append(lastStatus).append(cookie.isEmpty() ? " no cookie" : " cookie");
        if (!login(user, password, how)) return false;
        signing();
        return true;
    }

    /** Logs in and sends the code; returns the network's reply text, or null (see log). */
    /** {@code pick}: in a menu reply, the line to choose (by a word in it), or null. */
    String send(String user, String password, int[] how, String code, String pick) {
        try {
            if (!open(user, password, how)) return null;
            // As the page does: send, then ask ussd_write_flag each second (15 waiting, 16 answered), then read it.
            reply = answer(postForm("goformId=USSD_PROCESS&USSD_operator=ussd_send&USSD_send_number="
                    + URLEncoder.encode(code, "UTF-8") + "&notCallback=true"));
            // A menu ("1 DATA Balance / 2 ROUTER Balance ..."): reply with the number of the wanted line.
            for (int level = 0; level < 3 && reply != null && pick != null && SimData.amount(reply) == null; level++) {
                Matcher m = Pattern.compile("(?im)^\\s*(\\d+)[.):]?\\s+.*" + Pattern.quote(pick)).matcher(reply);
                if (!m.find()) break;
                log.append(", chose ").append(m.group(1));
                String next = answer(postForm("goformId=USSD_PROCESS&USSD_operator=ussd_reply&USSD_reply_number="
                        + m.group(1) + "&notCallback=true"));
                if (next == null) break;
                reply = next;
            }
            postForm("goformId=USSD_PROCESS&USSD_operator=ussd_cancel");   // close the session on the network
            // While logged in: how the router is doing (the same values its own status bar shows).
            state = json(read("signalbar,network_type,network_provider,rssi,lte_rsrp,sta_count,sms_dev_used,sms_nv_total,sms_unread_num"));
            if (reply == null) log.append(", no reply");
            return reply;
        } catch (Exception e) {
            log.append(", ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
            return null;
        }
    }

    /** The router's own text messages (newest first): id, number, content, date. Call after open(). */
    org.json.JSONArray messages() throws IOException {
        JSONObject r = json(read("sms_data_total&page=0&data_per_page=100&mem_store=1&tags=10&order_by=order+by+id+desc"));
        org.json.JSONArray list = r.optJSONArray("messages");
        return list != null ? list : new org.json.JSONArray();
    }

    /**
     * The router's own internet (its "Connect Status" switch): off, nothing goes out over the SIM, so the TV
     * box falls back to a hotspot; on again for Airtel. Call after open().
     */
    boolean setInternet(boolean on) throws Exception {
        return "success".equals(json(postForm("goformId=" + (on ? "CONNECT_NETWORK" : "DISCONNECT_NETWORK") + "&notCallback=true")).optString("result"));
    }

    /** Deletes messages by id (as the page does: "1;2;3;"). Call after open(). */
    boolean delete(java.util.List<String> ids) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) sb.append(id).append(';');
        return "success".equals(json(postForm("goformId=DELETE_SMS&msg_id=" + URLEncoder.encode(sb.toString(), "UTF-8")
                + "&notCallback=true")).optString("result"));
    }

    /** After a send or reply: waits for the network's answer the way the page does, and reads it (null: none). */
    private String answer(String postBody) throws Exception {
        log.append(", sent ").append(json(postBody).optString("result", postBody));
        String flag = "";
        for (int i = 0; i < 40; i++) {
            Thread.sleep(1000);
            flag = json(read("ussd_write_flag")).optString("ussd_write_flag", "");
            if (!(flag.equals("15") || flag.isEmpty() || flag.equals("0"))) break;
        }
        if (!flag.equals("16")) {
            log.append(", flag ").append(flag).append(" (").append(FLAGS.containsKey(flag) ? FLAGS.get(flag) : "?").append(')');
            return null;
        }
        String data = json(read("ussd_data_info")).optString("ussd_data", "");
        return data.isEmpty() ? null : decode(data, null);
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
            if (body.isEmpty()) log.append(", headers [").append(lastHead).append(']');
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
        return body(request("GET", get + "?cmd=" + cmd + (cmd.contains(",") ? "&multi_data=1" : "") + "&_=" + System.currentTimeMillis(), null));
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
            String r = normalize(got.toString("UTF-8"));
            lastStatus = status(r);
            int he = r.indexOf("\r\n\r\n");
            lastHead = (he > 0 ? r.substring(0, he) : r).replace("\r\n", " | ");
            if (lastHead.length() > 300) lastHead = lastHead.substring(0, 300);
            if (r.contains("Transfer-Encoding: chunked")) r = unchunk(r);
            Matcher c = Pattern.compile("(?im)^Set-Cookie:\\s*([^;\\r\\n]+)").matcher(r);
            while (c.find()) cookie = c.group(1).trim();   // the session (newer firmware keeps it in a cookie)
            return r;
        }
    }

    /** This router ends its header lines with a bare newline: made standard (CRLF) so the body can be found. */
    static String normalize(String r) {
        Matcher m = Pattern.compile("\r?\n\r?\n").matcher(r);
        if (!m.find()) return r;
        return r.substring(0, m.start()).replaceAll("\r?\n", "\r\n") + "\r\n\r\n" + r.substring(m.end());
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
