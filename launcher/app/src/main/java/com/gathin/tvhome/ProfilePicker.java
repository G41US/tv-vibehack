package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Who's watching?": the profiles as big tiles, full screen. The focused person's colours and
 * picture fill the screen, hugely blurred, and follow focus (as on Recent apps); the
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

    private static final int ADD_COLOUR = 0xFF8090B0;

    private ProfilePicker() {
    }

    static Dialog show(Activity a, boolean manage, Host host, Runnable dismissed) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = frame(a, d);
        ImageView ambient = (ImageView) root.getChildAt(0);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView kicker = GlassMenu.text(a, greeting(a), 16, 0xB3FFFFFF);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.22f);
        kicker.setGravity(Gravity.CENTER_HORIZONTAL);   // centred: TVs crop the screen's edges
        col.addView(kicker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            Drawable pic = Profiles.picture(a, id);
            String emoji = Profiles.emoji(a, id);
            String face = emoji != null ? emoji : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            boolean bday = today.contains(name);
            String note = bday ? "🎂  " + a.getString(R.string.pp_birthday) : manage ? "✎  " + a.getString(R.string.pp_manage)
                    : id.equals(current) ? a.getString(R.string.pp_current) : " ";
            int[] colours = Profiles.colours(id);
            View tile = tile(a, pic, face, emoji == null, colours, bday, manage, i++, () -> {
                self[0].setOnDismissListener(null);
                self[0].dismiss();
                if (manage) host.edit(id);
                else host.pick(id);
            });
            row.addView(tile);
            View card = tile.findViewById(R.id.card);
            cards.add(card);
            card.setTag(new Object[]{name, note, art(colours, pic)});
            if (focus == null || id.equals(current)) focus = card;
        }
        if (!manage) {
            View add = tile(a, null, "+", true, null, false, false, i, () -> {
                self[0].setOnDismissListener(null);
                self[0].dismiss();
                host.add();
            });
            row.addView(add);
            View card = add.findViewById(R.id.card);
            cards.add(card);
            card.setTag(new Object[]{a.getString(R.string.profile_add), " ", art(new int[]{ADD_COLOUR, 0xFF404860}, null)});
        }

        // Focus: this tile springs up, the rest step back; the person's colours fill the screen.
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
                Ambient.show(ambient, (Bitmap) info[2]);
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

        final View first = focus;
        d.setOnShowListener(x -> {
            if (first != null) first.requestFocus();
        });
        if (dismissed != null) d.setOnDismissListener(x -> dismissed.run());
        d.show();
        return d;
    }

    /** A person: the coloured square with their picture or initial (no name: it shows large below). */
    private static View tile(Activity a, Drawable pic, String face, boolean letter, int[] colours, boolean birthday,
                             boolean manage, int index, Runnable click) {
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
        card.setClipToOutline(true);
        View f;
        if (pic != null) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(pic);
            f = iv;
        } else {
            TextView tv = new TextView(a);
            tv.setText(face);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.WHITE);
            tv.setIncludeFontPadding(false);
            tv.setTextSize(letter ? 76 : 92);
            if (letter) tv.setTypeface(Fonts.weight(a, 600));
            f = tv;
        }
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

    /**
     * A full-screen page: the chosen thing's colours, hugely blurred, fill the screen (child 0, an
     * ImageView), darker towards the bottom, with the clock at the top.
     */
    private static FrameLayout frame(Activity a, Dialog d) {
        GlassMenu.track(d);
        Sounds.attach(d);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF04060C);
        ImageView ambient = new ImageView(a);
        ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(ambient, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0040610, 0xA6040610, 0x8C040610}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView clock = GlassMenu.text(a, DateFormat.getTimeFormat(a).format(new Date()), 22, 0xCCFFFFFF);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, GlassMenu.dp(a, 40), GlassMenu.dp(a, 64), 0);
        root.addView(clock, cl);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setWindowAnimations(R.style.GlassDialogAnim);
        }
        return root;
    }

    /** The blurred background for a person: their colours with their picture over them. */
    private static Bitmap art(int[] colours, Drawable pic) {
        Drawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colours);
        return Ambient.tiny(pic == null ? bg : new LayerDrawable(new Drawable[]{bg, pic}));
    }

    /**
     * "Choose a picture", full screen like Netflix's: the person large on the left as it will look,
     * and on the right rows to pick from (a photo from a phone, people, robots, characters, emoji).
     * {@code picked} gets the new value (null: no picture); {@code fromPhone} starts the phone upload.
     */
    static void pickPicture(Activity a, String id, String name, Consumer<String> picked, Runnable fromPhone, Runnable back) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = frame(a, d);
        ImageView ambient = (ImageView) root.getChildAt(0);
        int[] colours = Profiles.colours(id);
        String now = Profiles.avatar(a, id);

        LinearLayout page = new LinearLayout(a);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setClipChildren(false);
        int side = GlassMenu.dp(a, 72);
        page.setPadding(side, GlassMenu.dp(a, 48), GlassMenu.dp(a, 32), GlassMenu.dp(a, 40));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left: how it will look.
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(left, new LinearLayout.LayoutParams(GlassMenu.dp(a, 240), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView kicker = GlassMenu.text(a, a.getString(R.string.pp_picture), 15, 0xB3FFFFFF);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.22f);
        left.addView(kicker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout preview = new FrameLayout(a);
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colours);
        pbg.setCornerRadius(GlassMenu.dp(a, 44));
        preview.setBackground(pbg);
        preview.setClipToOutline(true);
        preview.setElevation(GlassMenu.dp(a, 16));
        ImageView pImg = new ImageView(a);
        pImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(pImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView pText = new TextView(a);
        pText.setGravity(Gravity.CENTER);
        pText.setTextColor(Color.WHITE);
        pText.setIncludeFontPadding(false);
        preview.addView(pText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 208), GlassMenu.dp(a, 208));
        plp.topMargin = GlassMenu.dp(a, 18);
        left.addView(preview, plp);
        TextView who = GlassMenu.text(a, name, 30, Color.WHITE);
        who.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = GlassMenu.dp(a, 22);
        left.addView(who, wlp);

        // Shows a choice on the left and behind everything (value: picture, emoji or letter).
        Consumer<Object[]> showChoice = c -> {
            Drawable pic = (Drawable) c[0];
            pImg.setImageDrawable(pic);
            pText.setText(pic == null ? (String) c[1] : "");
            pText.setTextSize(c[1] != null && ((String) c[1]).codePointCount(0, ((String) c[1]).length()) <= 2 ? 96 : 40);
            Ambient.show(ambient, art(colours, pic));
        };

        // Right: rows to pick from.
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(GlassMenu.dp(a, 24), GlassMenu.dp(a, 12), 0, GlassMenu.dp(a, 24));
        scroll.addView(rows);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        slp.leftMargin = GlassMenu.dp(a, 40);
        page.addView(scroll, slp);

        final View[] focus = {null};
        Consumer<String> choose = v -> {
            d.setOnDismissListener(null);
            d.dismiss();
            picked.accept(v);
        };
        // A row: a heading, then a sideways strip of choices.
        java.util.function.Function<Integer, LinearLayout> section = label -> {
            TextView h = GlassMenu.text(a, a.getString(label), 18, 0xE6FFFFFF);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = GlassMenu.dp(a, rows.getChildCount() == 0 ? 0 : 22);
            rows.addView(h, hlp);
            LinearLayout strip = new LinearLayout(a);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(GlassMenu.dp(a, 10), GlassMenu.dp(a, 14), GlassMenu.dp(a, 40), GlassMenu.dp(a, 14));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.addView(strip);
            rows.addView(hs);
            return strip;
        };
        // One choice: a rounded square on the person's colours.
        class Choice {
            void add(LinearLayout strip, Drawable pic, String text, String value, Runnable click) {
                FrameLayout t = new FrameLayout(a);
                t.setFocusable(true);
                t.setClickable(true);
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, value == null || value.equals(Profiles.PHOTO) && pic == null
                        ? new int[]{0x33FFFFFF, 0x1AFFFFFF} : colours);
                bg.setCornerRadius(GlassMenu.dp(a, 26));
                t.setBackground(bg);
                t.setClipToOutline(true);
                t.setElevation(GlassMenu.dp(a, 4));
                if (pic != null) {
                    ImageView iv = new ImageView(a);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageDrawable(pic);
                    t.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                } else {
                    TextView tv = GlassMenu.text(a, text, text.codePointCount(0, text.length()) <= 2 ? 50 : 15, Color.WHITE);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(GlassMenu.dp(a, 8), 0, GlassMenu.dp(a, 8), 0);
                    t.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(GlassMenu.dp(a, 26));
                ring.setStroke(GlassMenu.dp(a, 4), Color.WHITE);
                OvershootInterpolator spring = new OvershootInterpolator(2f);
                Drawable showPic = pic;
                String showText = pic != null ? null : value == null || text.codePointCount(0, text.length()) > 2
                        ? (name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase()) : text;
                t.setOnFocusChangeListener((v, has) -> {
                    t.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f).translationZ(has ? GlassMenu.dp(a, 12) : 0)
                            .setInterpolator(spring).setDuration(260).start();
                    t.setForeground(has ? ring : null);
                    if (has && value != null && !(value.equals(Profiles.PHOTO) && pic == null)) showChoice.accept(new Object[]{showPic, showText});
                    if (has && value == null) showChoice.accept(new Object[]{null, showText});
                });
                t.setOnClickListener(v -> click.run());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GlassMenu.dp(a, 116), GlassMenu.dp(a, 116));
                lp.rightMargin = GlassMenu.dp(a, 18);
                strip.addView(t, lp);
                if (focus[0] == null || value != null && value.equals(now)) focus[0] = t;
            }
        }
        Choice c = new Choice();

        LinearLayout yours = section.apply(R.string.pp_yours);
        c.add(yours, null, "📱  " + a.getString(R.string.pp_from_phone), Profiles.PHOTO, () -> {
            d.setOnDismissListener(null);
            d.dismiss();
            fromPhone.run();
        });
        if (Profiles.PHOTO.equals(now)) {
            Drawable photo = Profiles.picture(a, id);
            if (photo != null) c.add(yours, photo, null, Profiles.PHOTO, () -> choose.accept(Profiles.PHOTO));
        }
        c.add(yours, null, "✕  " + a.getString(R.string.pp_no_picture), null, () -> choose.accept(null));

        int[][] groups = {{R.string.pp_people, Profiles.PEOPLE, Profiles.ROBOTS}, {R.string.pp_robots, Profiles.ROBOTS, Profiles.CHARACTERS},
                {R.string.pp_characters, Profiles.CHARACTERS, Profiles.PICS.length}};
        for (int[] g : groups) {
            LinearLayout strip = section.apply(g[0]);
            for (int n = g[1]; n < g[2]; n++) {
                String v = "pic:" + n;
                c.add(strip, a.getDrawable(Profiles.PICS[n]), null, v, () -> choose.accept(v));
            }
        }
        LinearLayout fun = section.apply(R.string.pp_fun);
        for (String e : Profiles.AVATARS) c.add(fun, null, e, e, () -> choose.accept(e));

        // Start on the current picture (or the first choice), already shown on the left.
        Drawable cur = Profiles.picture(a, id);
        String curEmoji = Profiles.emoji(a, id);
        showChoice.accept(new Object[]{cur, curEmoji != null ? curEmoji : name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase()});
        final View first = focus[0];
        d.setOnShowListener(x -> {
            if (first != null) first.requestFocus();
        });
        d.setOnDismissListener(x -> back.run());
        d.show();
    }

    private static String greeting(Activity a) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return a.getString(h < 5 ? R.string.good_night : h < 12 ? R.string.good_morning
                : h < 17 ? R.string.good_afternoon : h < 22 ? R.string.good_evening : R.string.good_night);
    }
}
