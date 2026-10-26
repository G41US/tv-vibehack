package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The weather forecast (press OK on the weather on Home): now, feels-like, humidity, wind, sunrise
 * and sunset, and a tile for today and each of the next five days with the high, low and chance of
 * rain. Data from Open-Meteo, for the same place as the weather line.
 */
final class ForecastPage {

    private ForecastPage() {
    }

    static void show(Activity act, Weather weather) {
        GlassMenu.Progress wait = GlassMenu.progress(act, act.getString(R.string.fc_loading));
        wait.set("");
        weather.forecast(f -> {
            wait.dismiss();
            if (act.isFinishing() || act.isDestroyed()) return;
            if (f == null) {
                GlassMenu.message(act, act.getString(R.string.fc_title), act.getString(R.string.fc_failed),
                        new String[]{act.getString(android.R.string.ok)}, null);
                return;
            }
            build(act, f, 0);
        });
    }

    private static void build(Activity a, Weather.Forecast f, int focus) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);

        String sky = Weather.describe(a, f.code, f.day);
        LinearLayout hero = GlassMenu.hero(a, f.now + "°   " + Weather.emoji(f.code) + "  " + sky,
                a.getString(f.mph ? R.string.fc_details_mph : R.string.fc_details, f.feels, f.humidity, (int) Math.round(f.wind)),
                a.getString(R.string.fc_sun, clock(a, f.sunrise), clock(a, f.sunset)));
        CharSequence[] today = {((TextView) hero.getChildAt(0)).getText(), ((TextView) hero.getChildAt(1)).getText(),
                ((TextView) hero.getChildAt(2)).getText()};
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = GlassMenu.dp(a, 20);
        body.addView(hero, hlp);

        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        ColorStateList dim = a.getColorStateList(R.color.menu_ink_dim);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int n = Math.min(6, f.days.size());
        for (int i = 0; i < n; i++) {
            Weather.Day d = f.days.get(i);
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setFocusable(true);
            final int at = i;
            tile.setOnClickListener(v -> {
                GlassMenu.closeAll();
                showDay(a, f, at);
            });
            tile.setOnFocusChangeListener((v, has) -> {
                if (!has) return;
                CharSequence[] show = at == 0 ? today : new CharSequence[]{
                        dayName(d.date) + "   " + d.hi + "° / " + d.lo + "°   " + Weather.emoji(d.code) + "  " + Weather.describe(a, d.code),
                        a.getString(f.mph ? R.string.fc_day_details_mph : R.string.fc_day_details, d.feels, d.rain, d.wind),
                        a.getString(R.string.fc_sun, clock(a, d.sunrise), clock(a, d.sunset))};
                for (int k = 0; k < 3; k++) ((TextView) hero.getChildAt(k)).setText(show[k]);
            });
            tile.setBackgroundResource(R.drawable.menu_tile_bg);
            tile.setPadding(0, GlassMenu.dp(a, 14), 0, GlassMenu.dp(a, 14));
            tile.setGravity(Gravity.CENTER);
            TextView name = GlassMenu.text(a, i == 0 ? a.getString(R.string.fc_today) : dayName(d.date), 16, ink);
            // Today's tile shows the sky as it is now when the day isn't stormier than that.
            TextView icon = GlassMenu.text(a, Weather.emoji(i == 0 && d.code <= 3 ? f.code : d.code), 34, 0xFFFFFFFF);
            icon.setPadding(0, GlassMenu.dp(a, 8), 0, GlassMenu.dp(a, 6));
            // High in full, low dimmer, like a weather app.
            android.text.SpannableString temps = new android.text.SpannableString(d.hi + "°  " + d.lo + "°");
            temps.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), String.valueOf(d.hi).length() + 3,
                    temps.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView hilo = GlassMenu.text(a, temps, 18, ink);
            TextView rain = GlassMenu.text(a, d.rain > 0 ? "💧 " + d.rain + "%" : " ", 13, dim);
            rain.setPadding(0, GlassMenu.dp(a, 3), 0, 0);
            // A vertical LinearLayout gives children full width, so each line centres its own text.
            for (TextView v : new TextView[]{name, icon, hilo, rain}) {
                v.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.addView(v);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 112),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i == 0 ? 0 : GlassMenu.dp(a, 10);
            row.addView(tile, lp);
        }
        body.addView(row);
        GlassMenu.panel(a, a.getString(R.string.fc_title), f.city, body, WIDTH_DP, null);
        if (focus > 0 && focus < row.getChildCount()) row.post(() -> row.getChildAt(focus).requestFocus());
    }

    private static final int WIDTH_DP = 6 * 112 + 5 * 10 + 60;

    // ---- one day, hour by hour (press OK on a day) -----------------------------------------------

    private static void showDay(Activity a, Weather.Forecast f, int index) {
        Weather.Day d = f.days.get(index);
        List<Weather.Hour> hours = new ArrayList<>();
        SimpleDateFormat placeClock = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US);
        placeClock.setTimeZone(new java.util.SimpleTimeZone(f.utcOffset * 1000, "place"));   // "now" where the city is
        String now = placeClock.format(new Date());
        for (Weather.Hour h : f.hours) {
            if (index == 0) {
                // Today: from this hour to midnight, running into tomorrow if only a few hours are left.
                if (h.time.compareTo(now) >= 0 && (h.time.startsWith(d.date) || hours.size() < 6)) hours.add(h);
            } else if (h.time.startsWith(d.date) && h.time.compareTo(d.date + "T06:00") >= 0) {
                hours.add(h);
            }
        }

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView line = GlassMenu.text(a, summary(a, hours), 20, 0xFFFFFFFF);
        line.setPadding(0, 0, 0, GlassMenu.dp(a, 18));
        body.addView(line);

        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        ColorStateList dim = a.getColorStateList(R.color.menu_ink_dim);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < hours.size(); i++) {
            Weather.Hour h = hours.get(i);
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setFocusable(true);
            cell.setBackgroundResource(R.drawable.menu_tile_bg);
            cell.setPadding(0, GlassMenu.dp(a, 12), 0, GlassMenu.dp(a, 12));
            String label = index == 0 && i == 0 ? a.getString(R.string.fc_now) : hourLabel(a, h.time, 0);
            TextView time = GlassMenu.text(a, label, 15, ink);
            String pic = !h.day && h.code <= 1 ? "🌙" : Weather.emoji(h.code);
            TextView icon = GlassMenu.text(a, pic, 28, 0xFFFFFFFF);
            icon.setPadding(0, GlassMenu.dp(a, 6), 0, GlassMenu.dp(a, 4));
            TextView temp = GlassMenu.text(a, h.temp + "°", 18, ink);
            TextView rain = GlassMenu.text(a, h.rain >= 10 ? "💧 " + h.rain + "%" : " ", 13, dim);
            for (TextView v : new TextView[]{time, icon, temp, rain}) {
                v.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(v);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i == 0 ? 0 : GlassMenu.dp(a, 8);
            row.addView(cell, lp);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(row);
        body.addView(scroll);

        String title = index == 0 ? a.getString(R.string.fc_today)
                : new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(parse(d.date));
        GlassMenu.panel(a, f.city, title, body, WIDTH_DP, () -> build(a, f, index));
    }

    /** "Storms likely 2 PM – 6 PM  ·  Hottest at 3 PM, 32°" for the hours shown. */
    private static String summary(Activity a, List<Weather.Hour> hours) {
        int n = hours.size();
        int[] rain = new int[n], code = new int[n], temp = new int[n];
        for (int i = 0; i < n; i++) {
            rain[i] = hours.get(i).rain;
            code[i] = hours.get(i).code;
            temp[i] = hours.get(i).temp;
        }
        int[] s = DaySummary.of(rain, code, temp);
        String main;
        switch (s[0]) {
            case DaySummary.STORMS:
                main = a.getString(R.string.fc_sum_storms, hourLabel(a, hours.get(s[1]).time, 0), hourLabel(a, hours.get(s[2]).time, 1));
                break;
            case DaySummary.RAIN:
                main = a.getString(R.string.fc_sum_rain, hourLabel(a, hours.get(s[1]).time, 0), hourLabel(a, hours.get(s[2]).time, 1));
                break;
            case DaySummary.CHANCE:
                main = a.getString(R.string.fc_sum_chance, hourLabel(a, hours.get(s[1]).time, 0));
                break;
            default:
                main = a.getString(R.string.fc_sum_dry);
        }
        if (s[3] < 0) return main;
        return main + "  ·  " + a.getString(R.string.fc_sum_hot, hourLabel(a, hours.get(s[3]).time, 0), temp[s[3]]);
    }

    /** "2 PM" or "14:00" (as the box is set) for "2026-10-26T14:00", plus some hours. */
    private static String hourLabel(Activity a, String iso, int plusHours) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso));
            c.add(Calendar.HOUR_OF_DAY, plusHours);
            String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), DateFormat.is24HourFormat(a) ? "Hm" : "ha");
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(c.getTime());
        } catch (Exception e) {
            return iso;
        }
    }

    private static Date parse(String isoDate) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate);
        } catch (Exception e) {
            return new Date();
        }
    }

    private static String dayName(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            return new SimpleDateFormat("EEE d", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    /** "06:32" in the box's 12- or 24-hour style. */
    private static String clock(Activity a, String hhmm) {
        try {
            String[] p = hhmm.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
            return DateFormat.getTimeFormat(a).format(c.getTime());
        } catch (Exception e) {
            return hhmm;
        }
    }
}
