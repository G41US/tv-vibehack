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
import java.util.Date;
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
            build(act, f);
        });
    }

    private static void build(Activity a, Weather.Forecast f) {
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
        GlassMenu.panel(a, a.getString(R.string.fc_title), f.city, body, 6 * 112 + 5 * 10 + 60, null);
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
