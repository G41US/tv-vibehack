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
            // Other countries where Airtel sells the box
            {"Antananarivo", "-18.8792", "47.5079"}, {"Brazzaville", "-4.2634", "15.2429"},
            {"Dar es Salaam", "-6.7924", "39.2083"}, {"Kampala", "0.3476", "32.5825"},
            {"Kigali", "-1.9441", "30.0619"}, {"Kinshasa", "-4.4419", "15.2663"},
            {"Libreville", "0.4162", "9.4673"}, {"Lilongwe", "-13.9626", "33.7741"},
            {"Lubumbashi", "-11.6876", "27.5026"}, {"Lusaka", "-15.3875", "28.3228"},
            {"Mombasa", "-4.0435", "39.6682"}, {"N'Djamena", "12.1348", "15.0557"},
            {"Nairobi", "-1.2864", "36.8172"}, {"Niamey", "13.5116", "2.1254"},
            {"Pointe-Noire", "-4.7761", "11.8635"}, {"Victoria", "-4.6191", "55.4513"},
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

    static final String KEY_CODE = "code", KEY_VIS = "visibility", KEY_SUNSET = "sunset";

    /** The current WMO weather code (-1 unknown) and visibility in metres, as last fetched. */
    static int code(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CODE, -1);
    }

    static float visibility(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_VIS, 99999f);
    }

    /** Today's sunset "18:21" for the place, or null if not known yet. */
    static String sunset(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUNSET, "");
        return s.matches("\\d{1,2}:\\d{2}") ? s : null;
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

    /** The forecast page: now, and the next days. */
    static final class Forecast {
        String city;
        int now, feels, humidity, code;
        double wind;
        String sunrise, sunset;   // "06:32"
        final java.util.List<Day> days = new java.util.ArrayList<>();
    }

    static final class Day {
        String date;   // "2026-10-21"
        int code, hi, lo, rain;
    }

    interface ForecastCallback {
        void onForecast(Forecast f);   // null when it couldn't be fetched
    }

    void forecast(ForecastCallback cb) {
        final String place = OFF.equals(place()) ? AUTO : place();
        new Thread(() -> {
            Forecast f = null;
            try {
                f = fetchForecast(place);
            } catch (Exception ignored) {
            }
            final Forecast result = f;
            main.post(() -> cb.onForecast(result));
        }, "forecast").start();
    }

    private Forecast fetchForecast(String place) throws Exception {
        Object[] where = locate(place);
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=6", (Double) where[1], (Double) where[2]);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject d = j.getJSONObject("daily");
        Forecast f = new Forecast();
        f.city = (String) where[0];
        f.now = (int) Math.round(cur.getDouble("temperature_2m"));
        f.feels = (int) Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")));
        f.humidity = (int) Math.round(cur.optDouble("relative_humidity_2m", 0));
        f.wind = cur.optDouble("wind_speed_10m", 0);
        f.code = cur.getInt("weather_code");
        f.sunrise = timeOf(d.getJSONArray("sunrise").optString(0, ""));
        f.sunset = timeOf(d.getJSONArray("sunset").optString(0, ""));
        org.json.JSONArray dates = d.getJSONArray("time");
        for (int i = 0; i < dates.length(); i++) {
            Day day = new Day();
            day.date = dates.getString(i);
            day.code = d.getJSONArray("weather_code").optInt(i, 0);
            day.hi = (int) Math.round(d.getJSONArray("temperature_2m_max").optDouble(i, 0));
            day.lo = (int) Math.round(d.getJSONArray("temperature_2m_min").optDouble(i, 0));
            day.rain = d.has("precipitation_probability_max")
                    ? (int) Math.round(d.getJSONArray("precipitation_probability_max").optDouble(i, 0)) : 0;
            f.days.add(day);
        }
        return f;
    }

    private static String timeOf(String iso) {
        int t = iso.indexOf('T');
        return t >= 0 ? iso.substring(t + 1) : iso;
    }

    /** {city, lat, lon} for a place from CITIES, or found from the connection. */
    private Object[] locate(String place) throws Exception {
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
        return new Object[]{city, lat, lon};
    }

    private String fetch(String place) throws Exception {
        Object[] where = locate(place);
        String city = (String) where[0];
        double lat = (Double) where[1], lon = (Double) where[2];
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,visibility&daily=temperature_2m_max,temperature_2m_min,sunset"
                + "&timezone=auto&forecast_days=1", lat, lon);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject daily = j.getJSONObject("daily");
        long now = Math.round(cur.getDouble("temperature_2m"));
        long hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
        long lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
        String sky = describe(ctx, cur.getInt("weather_code"));
        // Kept for the wallpaper's weather effects and Night mode from sunset.
        String sunset = daily.has("sunset") ? timeOf(daily.getJSONArray("sunset").optString(0, "")) : "";
        prefs().edit().putInt(KEY_CODE, cur.getInt("weather_code"))
                .putFloat(KEY_VIS, (float) cur.optDouble("visibility", 99999))
                .putString(KEY_SUNSET, sunset).apply();
        StringBuilder s = new StringBuilder();
        s.append(now).append("°  ").append(sky).append("  ·  ")
                .append(ctx.getString(R.string.weather_hilo, (int) hi, (int) lo));
        if (!city.isEmpty()) s.append("  ·  ").append(city);
        return s.toString();
    }

    /** A picture for a WMO weather code (the forecast page). */
    static String emoji(int code) {
        if (code == 0 || code == 1) return "☀️";
        if (code == 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 95) return "⛈️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 80) return "🌦️";
        if (code >= 51) return "🌧️";
        return "🌤️";
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static String describe(Context ctx, int code) {
        int id;
        switch (code) {
            case 0: id = R.string.wx_clear; break;
            case 1: id = R.string.wx_mostly_clear; break;
            case 2: id = R.string.wx_partly_cloudy; break;
            case 3: id = R.string.wx_cloudy; break;
            case 45: case 48: id = R.string.wx_fog; break;
            case 51: case 53: case 55: id = R.string.wx_drizzle; break;
            case 56: case 57: id = R.string.wx_freezing_drizzle; break;
            case 61: id = R.string.wx_light_rain; break;
            case 63: id = R.string.wx_rain; break;
            case 65: id = R.string.wx_heavy_rain; break;
            case 66: case 67: id = R.string.wx_freezing_rain; break;
            case 71: case 73: case 75: case 77: id = R.string.wx_snow; break;
            case 80: id = R.string.wx_light_showers; break;
            case 81: id = R.string.wx_showers; break;
            case 82: id = R.string.wx_heavy_showers; break;
            case 85: case 86: id = R.string.wx_snow_showers; break;
            case 95: id = R.string.wx_thunderstorm; break;
            case 96: case 99: id = R.string.wx_thunderstorm_hail; break;
            default: return "";
        }
        return ctx.getString(id);
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
