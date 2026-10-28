package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * "Who's watching?": the profiles as big tiles, full screen, like a streaming service's profile page.
 * Each person has their own colour and picture (or initial), the focused tile grows and lights up,
 * the current profile says so, birthdays get a cake. "Manage profiles" turns the tiles into edit
 * buttons (picture, name, birthday, remove), and "Ask at start" sits on the same screen.
 */
final class ProfilePicker {

    interface Host {
        void pick(String id);

        void add();

        void edit(String id);
    }

    /** One colour pair per person, picked by their id so it never changes. */
    private static final int[][] COLOURS = {
            {0xFF5B6CFF, 0xFF9A5BFF}, {0xFFFF6B6B, 0xFFFF9F43}, {0xFF1DD1A1, 0xFF10AC84},
            {0xFF48DBFB, 0xFF0ABDE3}, {0xFFFF9FF3, 0xFFF368E0}, {0xFFFECA57, 0xFFFF9F43},
            {0xFF54A0FF, 0xFF2E86DE}, {0xFFA29BFE, 0xFF6C5CE7}};

    private ProfilePicker() {
    }

    static Dialog show(Activity a, boolean manage, Host host, Runnable dismissed) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        GlassMenu.track(d);
        Sounds.attach(d);
        FrameLayout root = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF20B1024, 0xF2060812, 0xFA03040A});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView kicker = GlassMenu.text(a, greeting(a), 16, 0xB3FFFFFF);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.18f);
        col.addView(kicker);
        TextView title = GlassMenu.text(a, a.getString(manage ? R.string.pp_manage : R.string.profile_who), 40, Color.WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = GlassMenu.dp(a, 6);
        tlp.bottomMargin = GlassMenu.dp(a, 40);
        col.addView(title, tlp);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int pad = GlassMenu.dp(a, 40);
        row.setPadding(pad, GlassMenu.dp(a, 16), pad, GlassMenu.dp(a, 16));
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setFillViewport(true);
        scroll.addView(row);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Dialog[] self = {d};
        String current = Profiles.active(a);
        List<String> today = Profiles.birthdaysToday(a);
        List<String[]> people = Profiles.list(a);
        View focus = null;
        int i = 0;
        for (String[] p : people) {
            String id = p[0], name = p[1];
            String pic = Profiles.avatar(a, id);
            String face = pic != null ? pic : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            String note = manage ? "✎" : id.equals(current) ? a.getString(R.string.pp_current) : null;
            View tile = tile(a, face, pic == null, name, note, COLOURS[Math.abs(id.hashCode()) % COLOURS.length],
                    today.contains(name), i++, () -> {
                        self[0].setOnDismissListener(null);
                        self[0].dismiss();
                        if (manage) host.edit(id);
                        else host.pick(id);
                    });
            row.addView(tile);
            if (focus == null || id.equals(current)) focus = tile.findViewById(R.id.card);
        }
        if (!manage) {
            row.addView(tile(a, "+", true, a.getString(R.string.profile_add), null, null, false, i, () -> {
                self[0].setOnDismissListener(null);
                self[0].dismiss();
                host.add();
            }));
        }

        // Manage / Done, and whether to ask after the TV starts.
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = GlassMenu.dp(a, 36);
        col.addView(bar, blp);
        bar.addView(GlassMenu.pill(a, a.getString(manage ? R.string.pp_done : R.string.pp_manage), v -> {
            d.setOnDismissListener(null);
            d.dismiss();
            show(a, !manage, host, dismissed);
        }));
        if (people.size() > 1) {
            boolean ask = Profiles.askEnabled(a);
            TextView askPill = GlassMenu.pill(a, a.getString(R.string.pp_ask) + ": " + a.getString(ask ? R.string.tv_on : R.string.tv_off), null);
            askPill.setOnClickListener(v -> {
                boolean now = !Profiles.askEnabled(a);
                Profiles.setAsk(a, now);
                askPill.setText(a.getString(R.string.pp_ask) + ": " + a.getString(now ? R.string.tv_on : R.string.tv_off));
            });
            bar.addView(askPill);
        }

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setWindowAnimations(R.style.GlassDialogAnim);
        }
        final View first = focus;
        d.setOnShowListener(x -> {
            if (first != null) first.requestFocus();
        });
        if (dismissed != null) d.setOnDismissListener(x -> dismissed.run());
        d.show();
        return d;
    }

    /** A person: the coloured square with their picture or initial, their name, and a note under it. */
    private static View tile(Activity a, String face, boolean letter, String name, String note, int[] colours,
                             boolean birthday, int index, Runnable click) {
        int size = GlassMenu.dp(a, 168);
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);

        FrameLayout card = new FrameLayout(a);
        card.setId(R.id.card);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable fill;
        if (colours != null) {
            fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colours);
        } else {   // "Add": an outline only
            fill = new GradientDrawable();
            fill.setColor(0x14FFFFFF);
            fill.setStroke(GlassMenu.dp(a, 2), 0x66FFFFFF, GlassMenu.dp(a, 10), GlassMenu.dp(a, 8));
        }
        fill.setCornerRadius(GlassMenu.dp(a, 34));
        card.setBackground(fill);
        card.setElevation(GlassMenu.dp(a, 6));
        TextView f = new TextView(a);
        f.setText(face);
        f.setGravity(Gravity.CENTER);
        f.setTextColor(Color.WHITE);
        f.setIncludeFontPadding(false);
        f.setTextSize(letter ? 72 : 88);
        if (letter) f.setTypeface(Fonts.weight(a, 600));
        card.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (birthday) {
            TextView cake = new TextView(a);
            cake.setText("🎂");
            cake.setTextSize(30);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            clp.setMargins(0, GlassMenu.dp(a, 8), GlassMenu.dp(a, 10), 0);
            card.addView(cake, clp);
        }
        t.addView(card, new LinearLayout.LayoutParams(size, size));

        TextView n = GlassMenu.text(a, name, 20, Color.WHITE);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setGravity(Gravity.CENTER_HORIZONTAL);
        n.setAlpha(0.7f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = GlassMenu.dp(a, 16);
        t.addView(n, nlp);
        TextView s = GlassMenu.text(a, note != null ? note : " ", 14, 0x99FFFFFF);
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(GlassMenu.dp(a, 34));
        ring.setStroke(GlassMenu.dp(a, 4), Color.WHITE);
        card.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).translationZ(has ? GlassMenu.dp(a, 14) : 0)
                    .setDuration(180).start();
            card.setForeground(has ? ring : null);
            n.animate().alpha(has ? 1f : 0.7f).setDuration(180).start();
        });
        card.setOnClickListener(v -> click.run());

        // Slide in one after another when the page opens.
        t.setAlpha(0f);
        t.setTranslationY(GlassMenu.dp(a, 36));
        t.animate().alpha(1f).translationY(0).setStartDelay(70L * index + 80).setDuration(320).start();
        return t;
    }

    private static String greeting(Activity a) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return a.getString(h < 5 ? R.string.good_night : h < 12 ? R.string.good_morning
                : h < 17 ? R.string.good_afternoon : h < 22 ? R.string.good_evening : R.string.good_night);
    }
}
