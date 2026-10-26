package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Festive looks: on a holiday in the country of the weather's place (or the TV's region), the greeting
 * becomes "Happy Independence Day 🇳🇬", "Eid Mubarak", "Merry Christmas" and so on, and the wallpaper
 * gets a matching touch (confetti in the flag's colours, snow, gold sparkles). Back to normal the next
 * day. Switched off in Look > Festive looks.
 */
final class Festive {

    static final int CONFETTI = 0, SNOW = 1, SPARKLE = 2;

    static final class Holiday {
        final String text;
        final int kind;
        final int[] colors;

        Holiday(String text, int kind, int... colors) {
            this.text = text;
            this.kind = kind;
            this.colors = colors;
        }
    }

    private Festive() {
    }

    // National days: country, month, day, independence (true) or national day, flag colours.
    private static final Object[][] NATIONAL = {
            {"NG", 10, 1, true, new int[]{0xFF008751, 0xFFFFFFFF}},
            {"GH", 3, 6, true, new int[]{0xFFCE1126, 0xFFFCD116, 0xFF006B3F}},
            {"KE", 12, 12, true, new int[]{0xFF1A1A1A, 0xFFBB0000, 0xFF006600, 0xFFFFFFFF}},
            {"TZ", 12, 9, true, new int[]{0xFF1EB53A, 0xFFFCD116, 0xFF00A3DD}},
            {"UG", 10, 9, true, new int[]{0xFF1A1A1A, 0xFFFCDC04, 0xFFD90000}},
            {"RW", 7, 1, true, new int[]{0xFF00A1DE, 0xFFFAD201, 0xFF20603D}},
            {"ZA", 4, 27, false, new int[]{0xFF007A4D, 0xFFFFB612, 0xFFDE3831, 0xFF002395}},
            {"ZM", 10, 24, true, new int[]{0xFF198A00, 0xFFDE2010, 0xFFEF7D00}},
            {"MW", 7, 6, true, new int[]{0xFF1A1A1A, 0xFFCE1126, 0xFF339E35}},
            {"CD", 6, 30, true, new int[]{0xFF007FFF, 0xFFF7D618, 0xFFCE1021}},
            {"CG", 8, 15, true, new int[]{0xFF009543, 0xFFFBDE4A, 0xFFDC241F}},
            {"GA", 8, 17, true, new int[]{0xFF009E60, 0xFFFCD116, 0xFF3A75C4}},
            {"TD", 8, 11, true, new int[]{0xFF002664, 0xFFFECB00, 0xFFC60C30}},
            {"NE", 8, 3, true, new int[]{0xFFE05206, 0xFFFFFFFF, 0xFF0DB02B}},
            {"MG", 6, 26, true, new int[]{0xFFFFFFFF, 0xFFFC3D32, 0xFF007E3A}},
            {"SC", 6, 29, true, new int[]{0xFF003F87, 0xFFFCD856, 0xFFD62828, 0xFF007A3D}},
            {"CM", 5, 20, false, new int[]{0xFF007A5E, 0xFFCE1126, 0xFFFCD116}},
            {"CI", 8, 7, true, new int[]{0xFFF77F00, 0xFFFFFFFF, 0xFF009E60}},
            {"SN", 4, 4, true, new int[]{0xFF00853F, 0xFFFDEF42, 0xFFE31B23}},
            {"MA", 11, 18, true, new int[]{0xFFC1272D, 0xFF006233}},
            {"EG", 7, 23, false, new int[]{0xFFCE1126, 0xFFFFFFFF, 0xFF1A1A1A}},
            {"JM", 8, 6, true, new int[]{0xFF009B3A, 0xFFFED100, 0xFF1A1A1A}},
            {"US", 7, 4, true, new int[]{0xFFB22234, 0xFFFFFFFF, 0xFF3C3B6E}},
            {"CA", 7, 1, false, new int[]{0xFFD52B1E, 0xFFFFFFFF}},
            {"MX", 9, 16, true, new int[]{0xFF006847, 0xFFFFFFFF, 0xFFCE1126}},
            {"BR", 9, 7, true, new int[]{0xFF009C3B, 0xFFFFDF00, 0xFF002776}},
            {"IN", 8, 15, true, new int[]{0xFFFF9933, 0xFFFFFFFF, 0xFF138808}},
            {"IN", 1, 26, false, new int[]{0xFFFF9933, 0xFFFFFFFF, 0xFF138808}},
            {"PK", 8, 14, true, new int[]{0xFF01411C, 0xFFFFFFFF}},
            {"BD", 3, 26, true, new int[]{0xFF006A4E, 0xFFF42A41}},
            {"ID", 8, 17, true, new int[]{0xFFFF0000, 0xFFFFFFFF}},
            {"PH", 6, 12, true, new int[]{0xFF0038A8, 0xFFCE1126, 0xFFFCD116}},
            {"FR", 7, 14, false, new int[]{0xFF0055A4, 0xFFFFFFFF, 0xFFEF4135}},
            {"DE", 10, 3, false, new int[]{0xFF1A1A1A, 0xFFDD0000, 0xFFFFCE00}},
            {"ES", 10, 12, false, new int[]{0xFFAA151B, 0xFFF1BF00}},
            {"IT", 6, 2, false, new int[]{0xFF009246, 0xFFFFFFFF, 0xFFCE2B37}},
            {"PT", 6, 10, false, new int[]{0xFF006600, 0xFFFF0000}},
            {"IE", 3, 17, false, new int[]{0xFF169B62, 0xFFFFFFFF, 0xFFFF883E}},
            {"AU", 1, 26, false, new int[]{0xFF012169, 0xFFFFFFFF, 0xFFE4002B}},
            {"CN", 10, 1, false, new int[]{0xFFDE2910, 0xFFFFDE00}},
            {"TH", 12, 5, false, new int[]{0xFFA51931, 0xFFFFFFFF, 0xFF2D2A4A}},
            {"AE", 12, 2, false, new int[]{0xFF00732F, 0xFFFFFFFF, 0xFFFF0000}},
            {"SA", 9, 23, false, new int[]{0xFF006C35, 0xFFFFFFFF}},
    };

    /** Where Eid is a public holiday. */
    private static final Set<String> EID = new HashSet<>(Arrays.asList(
            "NG", "NE", "TD", "SN", "ML", "GN", "SL", "GM", "CM", "CI", "GH", "KE", "TZ", "UG", "ET", "SD", "SO",
            "DZ", "MA", "TN", "LY", "EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "IQ", "SY", "LB", "YE",
            "TR", "PK", "BD", "IN", "ID", "MY", "BN", "AF", "MV", "DJ", "MR", "KM"));
    /** Where Christmas isn't a holiday. */
    private static final Set<String> NO_CHRISTMAS = new HashSet<>(Arrays.asList(
            "SA", "AE", "QA", "KW", "BH", "OM", "YE", "IR", "AF", "LY", "DZ", "MA", "TN", "MR", "SO", "KP"));
    private static final Set<String> DIWALI = new HashSet<>(Arrays.asList(
            "IN", "NP", "LK", "SG", "MY", "MU", "FJ", "TT", "GY", "SR"));
    /** shortcut: Diwali follows the Hindu lunar calendar; these are the published dates, add more by 2030. */
    private static final String[] DIWALI_DATES = {"2026-11-08", "2027-10-29", "2028-10-17", "2029-11-05", "2030-10-26"};
    private static final Set<String> LUNAR = new HashSet<>(Arrays.asList(
            "CN", "TW", "HK", "MO", "SG", "MY", "VN", "KR"));

    static boolean on(Context c) {
        return c.getSharedPreferences(HomeSettings.PREFS, Context.MODE_PRIVATE).getBoolean("festive", true);
    }

    /** Today's birthday or holiday where the person is, or null. */
    static Holiday today(Context ctx) {
        // Birthdays (set per profile in Profiles) come first, whatever Festive looks is set to.
        java.util.List<String> names = Profiles.birthdaysToday(ctx);
        if (!names.isEmpty()) {
            names.remove(ctx.getString(R.string.profile_main));   // the unnamed main profile: just "Happy birthday"
            String text = names.isEmpty() ? ctx.getString(R.string.fx_birthday_plain)
                    : ctx.getString(R.string.fx_birthday, String.join(" & ", names));
            return new Holiday(text + "  🎂", CONFETTI, 0xFFFF6B9D, 0xFFFFD166, 0xFF4CC9F0, 0xFF7B5CFA, 0xFF2ECC9A);
        }
        if (!on(ctx)) return null;
        String cc = Weather.country(ctx);
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.MONTH) + 1, d = now.get(Calendar.DAY_OF_MONTH);
        for (Object[] n : NATIONAL) {
            if (n[0].equals(cc) && (Integer) n[1] == m && (Integer) n[2] == d) {
                String text = ctx.getString((Boolean) n[3] ? R.string.fx_independence : R.string.fx_national);
                return new Holiday(text + "  " + flag(cc), CONFETTI, (int[]) n[4]);
            }
        }
        if (EID.contains(cc)) {
            try {
                android.icu.util.IslamicCalendar ic = new android.icu.util.IslamicCalendar();
                ic.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
                ic.setTime(now.getTime());
                int im = ic.get(android.icu.util.IslamicCalendar.MONTH), id = ic.get(android.icu.util.IslamicCalendar.DAY_OF_MONTH);
                // Eid al-Fitr: 1–2 Shawwal (month 9 from 0); Eid al-Adha: 10–11 Dhu al-Hijjah (month 11).
                if ((im == 9 && id <= 2) || (im == 11 && (id == 10 || id == 11))) {
                    return new Holiday(ctx.getString(R.string.fx_eid) + "  🌙", SPARKLE, 0xFFF4C542, 0xFF2E9E5B, 0xFFFFFFFF);
                }
            } catch (RuntimeException ignored) {
                // no Islamic calendar on this box
            }
        }
        if (DIWALI.contains(cc)) {
            String iso = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), m, d);
            for (String dd : DIWALI_DATES) {
                if (dd.equals(iso)) return new Holiday(ctx.getString(R.string.fx_diwali) + "  🪔", SPARKLE, 0xFFFF9F1C, 0xFFFFD166, 0xFFFF6B6B);
            }
        }
        if (LUNAR.contains(cc)) {
            try {
                android.icu.util.ChineseCalendar cn = new android.icu.util.ChineseCalendar();
                cn.setTime(now.getTime());
                if (cn.get(android.icu.util.ChineseCalendar.MONTH) == 0 && cn.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 0
                        && cn.get(android.icu.util.ChineseCalendar.DAY_OF_MONTH) <= 3) {
                    return new Holiday(ctx.getString(R.string.fx_lunar) + "  🧧", SPARKLE, 0xFFE63946, 0xFFF4C542);
                }
            } catch (RuntimeException ignored) {
                // no Chinese calendar on this box
            }
        }
        if (m == 12 && d >= 24 && d <= 26 && !NO_CHRISTMAS.contains(cc)) {
            return new Holiday(ctx.getString(R.string.fx_christmas) + "  🎄", SNOW, 0xFFFFFFFF);
        }
        if ((m == 12 && d == 31) || (m == 1 && d == 1)) {
            return new Holiday(ctx.getString(R.string.fx_new_year) + "  🎉", SPARKLE, 0xFFF4C542, 0xFFE5E5E5, 0xFFFFFFFF);
        }
        return null;
    }

    /** "NG" → 🇳🇬 */
    static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "";
        cc = cc.toUpperCase(Locale.ROOT);
        return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
    }

    /** The wallpaper touch: confetti, snow or sparkles, at 20 frames a second, pausing while the remote is used. */
    static final class Fx extends View {
        private final Holiday h;
        private final float density;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random(11);
        private final float[] x, y, v, r, phase;
        private final int[] col;
        private long last;
        private boolean running;

        Fx(Context c, float density, Holiday h) {
            super(c);
            this.h = h;
            this.density = density;
            int n = h.kind == SPARKLE ? 34 : 40;
            x = new float[n];
            y = new float[n];
            v = new float[n];
            r = new float[n];
            phase = new float[n];
            col = new int[n];
            for (int i = 0; i < n; i++) seed(i, true);
        }

        private void seed(int i, boolean anywhere) {
            float w = Math.max(1, getWidth() > 0 ? getWidth() : 1920 * density / 1.5f);
            float hh = Math.max(1, getHeight() > 0 ? getHeight() : 1080 * density / 1.5f);
            x[i] = rnd.nextFloat() * w;
            y[i] = h.kind == SPARKLE || anywhere ? rnd.nextFloat() * hh : -10 * density;
            v[i] = (h.kind == SNOW ? 16 + rnd.nextFloat() * 26 : 26 + rnd.nextFloat() * 34) * density;
            r[i] = (h.kind == SPARKLE ? 2.2f + rnd.nextFloat() * 2.6f : 1.6f + rnd.nextFloat() * 2.2f) * density;
            phase[i] = rnd.nextFloat() * 6.28f;
            col[i] = h.colors[rnd.nextInt(h.colors.length)];
        }

        void start() {
            running = true;
            last = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.06f, (now - last) / 1000f), t = now / 1000f;
            last = now;
            float hh = getHeight();
            for (int i = 0; i < x.length; i++) {
                if (h.kind == SPARKLE) {
                    // Twinkle in place: fade in and out, each at its own pace.
                    float a = 0.5f + 0.5f * (float) Math.sin(t * (1.2f + (i % 5) * 0.25f) + phase[i]);
                    p.setColor(col[i]);
                    p.setAlpha((int) (200 * a * a));
                    float s = r[i] * (0.6f + 0.4f * a);
                    c.drawCircle(x[i], y[i], s * 0.45f, p);
                    p.setStrokeWidth(s * 0.35f);
                    c.drawLine(x[i] - s * 1.6f, y[i], x[i] + s * 1.6f, y[i], p);
                    c.drawLine(x[i], y[i] - s * 1.6f, x[i], y[i] + s * 1.6f, p);
                    continue;
                }
                y[i] += v[i] * dt;
                x[i] += (float) Math.sin(t + phase[i]) * 10 * density * dt;
                p.setColor(col[i]);
                p.setAlpha(h.kind == SNOW ? 200 : 210);
                if (h.kind == SNOW) {
                    c.drawCircle(x[i], y[i], r[i], p);
                } else {   // confetti: small strips turning as they fall
                    c.save();
                    c.rotate((t * 90 + phase[i] * 57) % 360, x[i], y[i]);
                    float flip = Math.abs((float) Math.cos(t * 2 + phase[i]));
                    c.drawRect(x[i] - r[i] * 1.4f, y[i] - r[i] * 0.6f * flip, x[i] + r[i] * 1.4f, y[i] + r[i] * 0.6f * flip, p);
                    c.restore();
                }
                if (y[i] > hh + 12 * density) seed(i, false);
            }
            if (running) {
                if (LiveWallpaper.busy()) postDelayed(this::invalidate, 300);   // the remote is in use: wait
                else postInvalidateDelayed(50);
            }
        }
    }
}
