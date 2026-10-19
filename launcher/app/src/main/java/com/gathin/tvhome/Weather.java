package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Current weather for the line above the date: "31°  Partly cloudy  ·  H 33° L 24°  ·  Lagos".
 * Data from Open-Meteo (free, no key). The place is found from the internet connection (ipwho.is)
 * unless one is picked on the TV (hold OK on Settings > Weather). Refreshed at most every 30 minutes;
 * the last result is kept so Home shows it instantly.
 */
final class Weather {

    interface Callback {
        void onWeather(String line);
    }

    static final String AUTO = "auto", OFF = "off";
    /** Cities offered in the picker: name, latitude, longitude. */
    static final String[][] CITIES = {
            {"Lagos", "6.5244", "3.3792"}, {"Abuja", "9.0765", "7.3986"},
            {"Port Harcourt", "4.8156", "7.0498"}, {"Ibadan", "7.3775", "3.9470"},
            {"Kano", "12.0022", "8.5920"}, {"Uyo", "5.0377", "7.9128"},
            {"Enugu", "6.4584", "7.5464"}, {"Benin City", "6.3350", "5.6037"},
            {"Calabar", "4.9757", "8.3417"}, {"Owerri", "5.4840", "7.0351"},
            {"Kaduna", "10.5105", "7.4165"}, {"Jos", "9.8965", "8.8583"},
    };

    private static final String PREFS = "weather", KEY_PLACE = "place", KEY_LINE = "line", KEY_TIME = "time",
            KEY_AUTO_CITY = "auto_city", KEY_AUTO_LAT = "auto_lat", KEY_AUTO_LON = "auto_lon", KEY_AUTO_TIME = "auto_time";
    private static final long FRESH_MS = 30L * 60 * 1000, SHOW_FOR_MS = 3L * 60 * 60 * 1000,
            AUTO_PLACE_MS = 24L * 60 * 60 * 1000;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    Weather(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "auto", "off", or a city name from CITIES. */
    String place() {
        return prefs().getString(KEY_PLACE, AUTO);
    }

    /** City the automatic setting found last, or null. */
    String autoCity() {
        return prefs().getString(KEY_AUTO_CITY, null);
    }

    void setPlace(String place) {
        prefs().edit().putString(KEY_PLACE, place).remove(KEY_LINE).putLong(KEY_TIME, 0).apply();
    }

    /** The last line if it is recent enough to show, else null. */
    String cached() {
        if (OFF.equals(place())) return null;
        long age = System.currentTimeMillis() - prefs().getLong(KEY_TIME, 0);
        return age < SHOW_FOR_MS ? prefs().getString(KEY_LINE, null) : null;
    }

    /** Fetches new weather if the last is older than 30 minutes (or always, when forced). */
    void refresh(boolean force, Callback cb) {
        if (busy || OFF.equals(place())) return;
        if (!force && System.currentTimeMillis() - prefs().getLong(KEY_TIME, 0) < FRESH_MS) return;
        busy = true;
        final String place = place();
        new Thread(() -> {
            String line = null;
            try {
                line = fetch(place);
            } catch (Exception ignored) {
            }
            final String result = line;
            main.post(() -> {
                busy = false;
                if (result != null) {
                    prefs().edit().putString(KEY_LINE, result).putLong(KEY_TIME, System.currentTimeMillis()).apply();
                    cb.onWeather(result);
                }
            });
        }, "weather").start();
    }

    private String fetch(String place) throws Exception {
        String city;
        double lat, lon;
        String[] picked = null;
        for (String[] c : CITIES) if (c[0].equals(place)) picked = c;
        if (picked != null) {
            city = picked[0];
            lat = Double.parseDouble(picked[1]);
            lon = Double.parseDouble(picked[2]);
        } else {
            SharedPreferences p = prefs();
            if (System.currentTimeMillis() - p.getLong(KEY_AUTO_TIME, 0) > AUTO_PLACE_MS || autoCity() == null) {
                JSONObject geo = null;
                try {
                    geo = new JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"));
                    if (!geo.optBoolean("success", false)) geo = null;
                } catch (Exception ignored) {
                }
                if (geo == null) {   // second provider; ad-blocking DNS sometimes blocks one of them
                    geo = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                }
                p.edit().putString(KEY_AUTO_CITY, geo.optString("city", ""))
                        .putString(KEY_AUTO_LAT, String.valueOf(Double.parseDouble(geo.get("latitude").toString())))
                        .putString(KEY_AUTO_LON, String.valueOf(Double.parseDouble(geo.get("longitude").toString())))
                        .putLong(KEY_AUTO_TIME, System.currentTimeMillis()).commit();
            }
            city = p.getString(KEY_AUTO_CITY, "");
            lat = Double.parseDouble(p.getString(KEY_AUTO_LAT, "0"));
            lon = Double.parseDouble(p.getString(KEY_AUTO_LON, "0"));
        }
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=1", lat, lon);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject daily = j.getJSONObject("daily");
        long now = Math.round(cur.getDouble("temperature_2m"));
        long hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
        long lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
        String sky = describe(cur.getInt("weather_code"));
        StringBuilder s = new StringBuilder();
        s.append(now).append("°  ").append(sky).append("  ·  H ").append(hi).append("°  L ").append(lo).append('°');
        if (!city.isEmpty()) s.append("  ·  ").append(city);
        return s.toString();
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
