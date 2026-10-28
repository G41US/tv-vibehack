package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * "Who's watching?": the profiles as big tiles, full screen. Home stays faintly visible behind,
 * blurred; a large glow in the focused person's colour fills the screen and follows focus; the
 * focused tile springs forward and its picture floats while the others step back; the person's name
 * shows large in the middle. "Manage profiles" turns the tiles into edit buttons (picture, name,
 * birthday, remove), and "Ask at start" sits on the same screen.
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
    private static final int ADD_COLOUR = 0xFF8090B0;

    private ProfilePicker() {
    }

    static Dialog show(Activity a, boolean manage, Host host, Runnable dismissed) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        GlassMenu.track(d);
        Sounds.attach(d);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF04060C);

        // Home behind, blurred and dimmed.
        ImageView behind = new ImageView(a);
        behind.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(behind, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ambient.show(behind, Ambient.tiny(a.getWindow().getDecorView()));
        View dim = new View(a);
        dim.setBackgroundColor(0xB8040610);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The focused person's colour, as a big soft glow that follows focus.
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.48f);
        View glowView = new View(a);
        glowView.setBackground(glow);
        root.addView(glowView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glowView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> glow.setGradientRadius((r - l) * 0.55f));
        final int[] glowNow = {0x00000000};
        ValueAnimator[] glowAnim = new ValueAnimator[1];

        // Clock, top right.
        TextView clock = GlassMenu.text(a, DateFormat.getTimeFormat(a).format(new Date()), 22, 0xCCFFFFFF);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, GlassMenu.dp(a, 40), GlassMenu.dp(a, 64), 0);
        root.addView(clock, cl);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView kicker = GlassMenu.text(a, greeting(a), 16, 0xB3FFFFFF);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.22f);
        col.addView(kicker);
        TextView title = GlassMenu.text(a, a.getString(manage ? R.string.pp_manage : R.string.profile_who), 46, Color.WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = GlassMenu.dp(a, 4);
        tlp.bottomMargin = GlassMenu.dp(a, 34);
        col.addView(title, tlp);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int pad = GlassMenu.dp(a, 48);
        row.setPadding(pad, GlassMenu.dp(a, 24), pad, GlassMenu.dp(a, 24));
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addView(row);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The focused person, large: name, then what's special about them.
        TextView bigName = GlassMenu.text(a, " ", 34, Color.WHITE);
        bigName.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bnl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bnl.topMargin = GlassMenu.dp(a, 6);
        col.addView(bigName, bnl);
        TextView bigNote = GlassMenu.text(a, " ", 16, 0xB3FFFFFF);
        bigNote.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(bigNote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Dialog[] self = {d};
        String current = Profiles.active(a);
        List<String> today = Profiles.birthdaysToday(a);
        List<String[]> people = Profiles.list(a);
        List<View> cards = new ArrayList<>();
        View focus = null;
        int i = 0;
        for (String[] p : people) {
            String id = p[0], name = p[1];
            String pic = Profiles.avatar(a, id);
            String face = pic != null ? pic : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            boolean bday = today.contains(name);
            String note = bday ? "🎂  " + a.getString(R.string.pp_birthday) : manage ? "✎  " + a.getString(R.string.pp_manage)
                    : id.equals(current) ? a.getString(R.string.pp_current) : " ";
            int[] colours = COLOURS[Math.abs(id.hashCode()) % COLOURS.length];
            View tile = tile(a, face, pic == null, colours, bday, manage, i++, () -> {
                self[0].setOnDismissListener(null);
                self[0].dismiss();
                if (manage) host.edit(id);
                else host.pick(id);
            });
            row.addView(tile);
            View card = tile.findViewById(R.id.card);
            cards.add(card);
            card.setTag(new Object[]{name, note, colours[0]});
            if (focus == null || id.equals(current)) focus = card;
        }
        if (!manage) {
            View add = tile(a, "+", true, null, false, false, i, () -> {
                self[0].setOnDismissListener(null);
                self[0].dismiss();
                host.add();
            });
            row.addView(add);
            View card = add.findViewById(R.id.card);
            cards.add(card);
            card.setTag(new Object[]{a.getString(R.string.profile_add), " ", ADD_COLOUR});
        }

        // Focus: this tile springs up, the rest step back, the glow and the big name follow.
        for (View card : cards) {
            View.OnFocusChangeListener own = card.getOnFocusChangeListener();
            card.setOnFocusChangeListener((v, has) -> {
                if (own != null) own.onFocusChange(v, has);
                if (!has) return;
                for (View other : cards) {
                    if (other == v) continue;
                    ((View) other.getParent()).animate().alpha(0.55f).setDuration(200).start();
                }
                ((View) v.getParent()).animate().alpha(1f).setDuration(200).start();
                Object[] info = (Object[]) v.getTag();
                bigName.setText((String) info[0]);
                bigNote.setText((String) info[1]);
                int to = ((int) info[2] & 0x00FFFFFF) | 0x8C000000;
                if (glowAnim[0] != null) glowAnim[0].cancel();
                ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), glowNow[0], to);
                va.setDuration(420);
                va.addUpdateListener(an -> {
                    glowNow[0] = (int) an.getAnimatedValue();
                    glow.setColors(new int[]{glowNow[0], glowNow[0] & 0x30FFFFFF, 0x00000000});
                });
                va.start();
                glowAnim[0] = va;
            });
        }

        // Manage / Done, and whether to ask after the TV starts.
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = GlassMenu.dp(a, 32);
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

    /** A person: the coloured square with their picture or initial (no name: it shows large below). */
    private static View tile(Activity a, String face, boolean letter, int[] colours, boolean birthday, boolean manage,
                             int index, Runnable click) {
        int size = GlassMenu.dp(a, 176);
        FrameLayout t = new FrameLayout(a);
        t.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 214), size + GlassMenu.dp(a, 30));
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
            fill.setColor(0x1AFFFFFF);
            fill.setStroke(GlassMenu.dp(a, 2), 0x80FFFFFF, GlassMenu.dp(a, 10), GlassMenu.dp(a, 8));
        }
        fill.setCornerRadius(GlassMenu.dp(a, 38));
        card.setBackground(fill);
        card.setElevation(GlassMenu.dp(a, 8));
        // A soft sheen over the top half, like glass.
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x33FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(GlassMenu.dp(a, 38));
        View sheenView = new View(a);
        sheenView.setBackground(sheen);
        card.addView(sheenView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size / 2));
        TextView f = new TextView(a);
        f.setText(face);
        f.setGravity(Gravity.CENTER);
        f.setTextColor(Color.WHITE);
        f.setIncludeFontPadding(false);
        f.setTextSize(letter ? 76 : 92);
        if (letter) f.setTypeface(Fonts.weight(a, 600));
        card.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (birthday || manage) {
            TextView badge = new TextView(a);
            badge.setText(birthday ? "🎂" : "✎");
            badge.setTextSize(birthday ? 30 : 24);
            badge.setTextColor(Color.WHITE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            blp.setMargins(0, GlassMenu.dp(a, 10), GlassMenu.dp(a, 14), 0);
            card.addView(badge, blp);
        }
        t.addView(card, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(GlassMenu.dp(a, 38));
        ring.setStroke(GlassMenu.dp(a, 4), Color.WHITE);
        OvershootInterpolator spring = new OvershootInterpolator(2.2f);
        ValueAnimator[] floatAnim = new ValueAnimator[1];
        card.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.16f : 0.94f).scaleY(has ? 1.16f : 0.94f)
                    .translationZ(has ? GlassMenu.dp(a, 18) : 0).setInterpolator(spring).setDuration(320).start();
            card.setForeground(has ? ring : null);
            if (floatAnim[0] != null) floatAnim[0].cancel();
            f.setTranslationY(0);
            if (has) {   // the picture floats gently while it's chosen
                ValueAnimator fa = ValueAnimator.ofFloat(0, -GlassMenu.dp(a, 6));
                fa.setDuration(1400);
                fa.setRepeatMode(ValueAnimator.REVERSE);
                fa.setRepeatCount(ValueAnimator.INFINITE);
                fa.addUpdateListener(an -> f.setTranslationY((float) an.getAnimatedValue()));
                fa.start();
                floatAnim[0] = fa;
            }
        });
        f.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {   // page closed: stop the float
                if (floatAnim[0] != null) floatAnim[0].cancel();
            }
        });
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setOnClickListener(v -> click.run());

        // Slide in one after another when the page opens.
        t.setAlpha(0f);
        t.setTranslationY(GlassMenu.dp(a, 40));
        t.animate().alpha(0.55f).translationY(0).setStartDelay(70L * index + 100).setDuration(360).start();
        return t;
    }

    private static String greeting(Activity a) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return a.getString(h < 5 ? R.string.good_night : h < 12 ? R.string.good_morning
                : h < 17 ? R.string.good_afternoon : h < 22 ? R.string.good_evening : R.string.good_night);
    }
}
