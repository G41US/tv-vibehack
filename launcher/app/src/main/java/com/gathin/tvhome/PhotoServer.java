package com.gathin.tvhome;

import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A tiny web server for sending a wallpaper from a phone on the same Wi-Fi. It runs only while the
 * "Send a photo" dialog is open, and only answers under a random 4-digit path shown on the TV.
 * The phone's browser crops and compresses the picture to 1920x1080 before sending it, so the TV
 * only ever receives a small JPEG.
 */
final class PhotoServer {

    interface Listener {
        void onPhoto(File file);
    }

    private static final int MAX_BODY = 20 * 1024 * 1024;

    private final byte[] page;
    private final File target;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    final String pin;
    int port;
    private ServerSocket server;
    private volatile boolean running;

    PhotoServer(byte[] page, File target, Listener listener) {
        this.page = page;
        this.target = target;
        this.listener = listener;
        this.pin = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
    }

    boolean start() {
        for (int p = 8765; p < 8775 && server == null; p++) {
            try {
                server = new ServerSocket(p);
                port = p;
            } catch (IOException ignored) {
            }
        }
        if (server == null) return false;
        running = true;
        new Thread(this::loop, "photo-server").start();
        return true;
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        while (running) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(30000);
                handle(s);
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return;
        String method = first[0], path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        long length = 0;
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                try {
                    length = Long.parseLong(l.substring(c + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String home = "/" + pin;
        if (method.equals("GET") && (path.equals(home) || path.equals(home + "/"))) {
            respond(out, "200 OK", "text/html; charset=utf-8", page);
        } else if (method.equals("POST") && path.equals(home + "/upload")) {
            if (length <= 0 || length > MAX_BODY) {
                respond(out, "413 Payload Too Large", "text/plain", "too big".getBytes(StandardCharsets.UTF_8));
                return;
            }
            File tmp = new File(target.getPath() + ".part");
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("short body");
                    f.write(buf, 0, n);
                    left -= n;
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || !tmp.renameTo(target)) {
                tmp.delete();
                respond(out, "400 Bad Request", "text/plain", "not a picture".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            main.post(() -> listener.onPhoto(target));
        } else {
            respond(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Request line and headers, up to the blank line; null if the client sent nothing useful. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 16 * 1024) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** The TV's addresses on the home network, 192.168.x.x first. */
    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("192.168.")) out.add(0, ip);
                        else out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
