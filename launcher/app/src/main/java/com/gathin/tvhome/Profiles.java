package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * People on one TV, each with their own dock and wallpaper. Profile "0" is the main one and uses the
 * plain setting keys, so a box with one profile behaves exactly as before; other profiles add
 * "_<id>" to the keys (Profiles.key). With more than one profile, Home asks "Who's watching?" after
 * the box starts and after "Still watching?" turned it off.
 *
 * Every value is stored as a String or boolean so BackupStore can carry the whole file.
 */
final class Profiles {

    static final String PREFS = "profiles", KEY_LIST = "list", KEY_ACTIVE = "active", KEY_ASK = "ask",
            KEY_PENDING = "ask_pending";
    static final String MAIN = "0";

    private Profiles() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** [id, name] pairs, main profile first. */
    static List<String[]> list(Context ctx) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray p = a.getJSONArray(i);
                out.add(new String[]{p.getString(0), p.getString(1)});
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() || !MAIN.equals(out.get(0)[0])) {
            out.add(0, new String[]{MAIN, ctx.getString(R.string.profile_main)});
        }
        return out;
    }

    private static void save(Context ctx, List<String[]> list) {
        JSONArray a = new JSONArray();
        for (String[] p : list) a.put(new JSONArray().put(p[0]).put(p[1]));
        prefs(ctx).edit().putString(KEY_LIST, a.toString()).apply();
    }

    static boolean multiple(Context ctx) {
        return list(ctx).size() > 1;
    }

    static String active(Context ctx) {
        String id = prefs(ctx).getString(KEY_ACTIVE, MAIN);
        for (String[] p : list(ctx)) if (p[0].equals(id)) return id;
        return MAIN;
    }

    static String activeName(Context ctx) {
        String id = active(ctx);
        for (String[] p : list(ctx)) if (p[0].equals(id)) return p[1];
        return "";
    }

    static void setActive(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply();
    }

    /** The setting key for the active profile: "order" for the main one, "order_<id>" for others. */
    static String key(Context ctx, String base) {
        String id = active(ctx);
        return MAIN.equals(id) ? base : base + "_" + id;
    }

    static String add(Context ctx, String name) {
        List<String[]> list = list(ctx);
        String id = String.valueOf(System.currentTimeMillis() % 100000000L);
        list.add(new String[]{id, name});
        save(ctx, list);
        return id;
    }

    static void rename(Context ctx, String id, String name) {
        List<String[]> list = list(ctx);
        for (String[] p : list) if (p[0].equals(id)) p[1] = name;
        save(ctx, list);
    }

    /** Removes a profile and its dock, wallpaper and photo. The main profile stays. */
    static void remove(Context ctx, String id) {
        if (MAIN.equals(id)) return;
        List<String[]> list = list(ctx);
        list.removeIf(p -> p[0].equals(id));
        save(ctx, list);
        ctx.getSharedPreferences(AppAdapter.PREFS, Context.MODE_PRIVATE).edit()
                .remove(AppAdapter.KEY_ORDER + "_" + id).apply();
        ctx.getSharedPreferences("look", Context.MODE_PRIVATE).edit().remove("wallpaper_" + id).apply();
        setBirthday(ctx, id, null);
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), "custom_wallpaper_" + id + ".jpg").delete();
        if (id.equals(prefs(ctx).getString(KEY_ACTIVE, MAIN))) setActive(ctx, MAIN);
    }

    /** A profile's birthday as "MM-DD", or null. */
    static String birthday(Context ctx, String id) {
        return prefs(ctx).getString("birthday_" + id, null);
    }

    static void setBirthday(Context ctx, String id, String mmdd) {
        if (mmdd == null) prefs(ctx).edit().remove("birthday_" + id).apply();
        else prefs(ctx).edit().putString("birthday_" + id, mmdd).apply();
    }

    /** Names of the profiles whose birthday is today (29 February counts on the 28th in other years). */
    static List<String> birthdaysToday(Context ctx) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int m = now.get(java.util.Calendar.MONTH) + 1, d = now.get(java.util.Calendar.DAY_OF_MONTH);
        String today = String.format(java.util.Locale.US, "%02d-%02d", m, d);
        boolean leap = new java.util.GregorianCalendar().isLeapYear(now.get(java.util.Calendar.YEAR));
        List<String> out = new ArrayList<>();
        for (String[] p : list(ctx)) {
            String b = birthday(ctx, p[0]);
            if (b == null) continue;
            if (b.equals(today) || (!leap && b.equals("02-29") && today.equals("02-28"))) out.add(p[1]);
        }
        return out;
    }

    static boolean askEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ASK, true);
    }

    static void setAsk(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ASK, on).apply();
    }

    /** The box just started or woke: ask who's watching next time Home shows. */
    static void markPending(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_PENDING, true).apply();
    }

    static boolean shouldAsk(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PENDING, false) && askEnabled(ctx) && multiple(ctx);
    }

    static void asked(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_PENDING, false).apply();
    }
}
