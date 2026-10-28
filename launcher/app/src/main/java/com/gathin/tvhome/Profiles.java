package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

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

    static final String PREFS = "profiles", KEY_LIST = "list", KEY_ACTIVE = "active";
    static final String MAIN = "0";
    /** Pictures a profile can pick (emoji, so they look the same on every TV and need no files). */
    static final String[] AVATARS = {"🦁", "🐯", "🐼", "🦊", "🐨", "🐸", "🐵", "🦄", "🐶", "🐱", "🐰", "🐻",
            "🐧", "🦉", "🐙", "🦋", "⚽", "🎮", "🎧", "🎨", "🚀", "🌟", "🌈", "👑"};

    /**
     * Drawn pictures (DiceBear: Avataaars and Bottts by Pablo Stanley, free for any use; Micah by Micah
     * Lanier, CC BY 4.0), stored as "pic:<n>". Listed here so the build keeps them.
     */
    static final int[] PICS = {R.drawable.av_01, R.drawable.av_02, R.drawable.av_03, R.drawable.av_04,
            R.drawable.av_05, R.drawable.av_06, R.drawable.av_07, R.drawable.av_08, R.drawable.av_09,
            R.drawable.av_10, R.drawable.av_11, R.drawable.av_12, R.drawable.av_13, R.drawable.av_14,
            R.drawable.av_15, R.drawable.av_16, R.drawable.av_17, R.drawable.av_18, R.drawable.av_19,
            R.drawable.av_20, R.drawable.av_21, R.drawable.av_22, R.drawable.av_23, R.drawable.av_24,
            R.drawable.av_25, R.drawable.av_26, R.drawable.av_27, R.drawable.av_28, R.drawable.av_29,
            R.drawable.av_30};
    /** PICS from here on: people, robots, characters. */
    static final int PEOPLE = 0, ROBOTS = 14, CHARACTERS = 22;
    /** A photo sent from a phone. */
    static final String PHOTO = "photo";

    /** The person's own colours (tiles, glow), picked by their id so they never change. */
    static final int[][] COLOURS = {
            {0xFF5B6CFF, 0xFF9A5BFF}, {0xFFFF6B6B, 0xFFFF9F43}, {0xFF1DD1A1, 0xFF10AC84},
            {0xFF48DBFB, 0xFF0ABDE3}, {0xFFFF9FF3, 0xFFF368E0}, {0xFFFECA57, 0xFFFF9F43},
            {0xFF54A0FF, 0xFF2E86DE}, {0xFFA29BFE, 0xFF6C5CE7}};

    static int[] colours(String id) {
        return COLOURS[Math.abs(id.hashCode()) % COLOURS.length];
    }

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
        setAvatar(ctx, id, null);
        //noinspection ResultOfMethodCallIgnored
        photo(ctx, id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), "custom_wallpaper_" + id + ".jpg").delete();
        if (id.equals(prefs(ctx).getString(KEY_ACTIVE, MAIN))) setActive(ctx, MAIN);
    }

    /** A profile's picture: an emoji, "pic:<n>", PHOTO, or null. */
    static String avatar(Context ctx, String id) {
        return prefs(ctx).getString("avatar_" + id, null);
    }

    static void setAvatar(Context ctx, String id, String emoji) {
        if (emoji == null) prefs(ctx).edit().remove("avatar_" + id).apply();
        else prefs(ctx).edit().putString("avatar_" + id, emoji).apply();
    }

    /** The emoji, if the picture is one (shown in text); null for none or a drawn picture or photo. */
    static String emoji(Context ctx, String id) {
        String a = avatar(ctx, id);
        return a == null || a.startsWith("pic:") || a.equals(PHOTO) ? null : a;
    }

    static File photo(Context ctx, String id) {
        return new File(ctx.getFilesDir(), "avatar_" + id + ".jpg");
    }

    /** A drawn picture or photo as an image, or null (none, or an emoji). */
    static Drawable picture(Context ctx, String id) {
        String a = avatar(ctx, id);
        if (a == null) return null;
        try {
            if (a.startsWith("pic:")) return ctx.getDrawable(PICS[Integer.parseInt(a.substring(4))]);
            if (a.equals(PHOTO)) {
                Bitmap b = BitmapFactory.decodeFile(photo(ctx, id).getPath());
                return b == null ? null : new BitmapDrawable(ctx.getResources(), b);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** "🦁  Ada", or just the name without a picture. */
    static String withAvatar(Context ctx, String id, String name) {
        String a = emoji(ctx, id);
        return a == null ? name : a + "  " + name;
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
}
