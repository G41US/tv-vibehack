package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics for the full-screen player, from LRCLIB (lrclib.net), a free community lyrics database
 * with no account or key. Timed ("synced") lyrics are preferred; plain ones are shown as they are.
 */
final class Lyrics {

    /** Timed lines (times in ms, ascending), or plain lines with no times. */
    static final class Song {
        final List<Long> times = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        boolean synced;

        /** The line playing at {@code ms} (synced), or -1 before the first. */
        int lineAt(long ms) {
            int i = Collections.binarySearch(times, ms);
            return i >= 0 ? i : -i - 2;
        }
    }

    interface Callback {
        void onLyrics(Song song);   // null: none found
    }

    private static final Map<String, Song> CACHE = new HashMap<>();
    private static final Song NONE = new Song();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    private Lyrics() {
    }

    static void find(String title, String artist, String album, long durationMs, Callback cb) {
        String key = title + "|" + artist;
        Song hit = CACHE.get(key);
        if (hit != null) {
            cb.onLyrics(hit == NONE ? null : hit);
            return;
        }
        new Thread(() -> {
            Song s = null;
            try {
                s = fetch(clean(title), firstArtist(artist), album, durationMs / 1000);
            } catch (Exception ignored) {
            }
            final Song result = s;
            MAIN.post(() -> {
                CACHE.put(key, result != null ? result : NONE);
                cb.onLyrics(result);
            });
        }, "lyrics").start();
    }

    /** "Song (feat. X) - Remastered 2011" → "Song". */
    static String clean(String title) {
        String t = title.replaceAll("(?i)\\s*[(\\[](feat|ft|with)\\.?[^)\\]]*[)\\]]", "");
        t = t.replaceAll("(?i)\\s+-\\s+(remaster|live|radio edit|single version|mono|stereo).*$", "");
        return t.trim();
    }

    private static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",|&| feat\\.| x ")[0];
        return a.trim();
    }

    private static Song fetch(String title, String artist, String album, long seconds) throws Exception {
        String q = "track_name=" + enc(title) + "&artist_name=" + enc(artist);
        try {
            String url = "https://lrclib.net/api/get?" + q
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + (seconds > 0 ? "&duration=" + seconds : "");
            Song s = parse(new JSONObject(get(url)));
            if (s != null) return s;
        } catch (Exception ignored) {
            // not an exact match: search instead
        }
        JSONArray all = new JSONArray(get("https://lrclib.net/api/search?" + q));
        Song plain = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            double d = o.optDouble("duration", 0);
            if (seconds > 0 && d > 0 && Math.abs(d - seconds) > 4) continue;
            Song s = parse(o);
            if (s == null) continue;
            if (s.synced) return s;
            if (plain == null) plain = s;
        }
        return plain;
    }

    static Song parse(JSONObject o) {
        if (o.optBoolean("instrumental", false)) return null;
        String synced = o.optString("syncedLyrics", "");
        if (!synced.isEmpty() && !"null".equals(synced)) {
            Song s = new Song();
            s.synced = true;
            List<long[]> order = new ArrayList<>();
            List<String> text = new ArrayList<>();
            for (String raw : synced.split("\n")) {
                Matcher m = TAG.matcher(raw);
                List<Long> at = new ArrayList<>();
                int end = 0;
                while (m.find()) {
                    long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                    if (m.group(3) != null) {
                        String f = (m.group(3) + "00").substring(0, 3);
                        ms += Long.parseLong(f);
                    }
                    at.add(ms);
                    end = m.end();
                }
                if (at.isEmpty()) continue;
                String line = raw.substring(end).trim();
                for (long ms : at) {
                    order.add(new long[]{ms, text.size()});
                    text.add(line.isEmpty() ? "♪" : line);
                }
            }
            Collections.sort(order, (x, y) -> Long.compare(x[0], y[0]));
            for (long[] e : order) {
                s.times.add(e[0]);
                s.lines.add(text.get((int) e[1]));
            }
            if (!s.lines.isEmpty()) return s;
        }
        String plain = o.optString("plainLyrics", "");
        if (plain.isEmpty() || "null".equals(plain)) return null;
        Song s = new Song();
        for (String line : plain.split("\n")) s.lines.add(line.trim().isEmpty() ? "" : line.trim());
        return s.lines.isEmpty() ? null : s;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "TV Home (github.com/G41US/tv-vibehack)");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
