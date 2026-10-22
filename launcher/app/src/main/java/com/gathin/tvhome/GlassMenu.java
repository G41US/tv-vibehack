package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * TV Home's own menus and pop-ups, in the frosted-glass style of the Network panel: a glass card,
 * rounded rows that turn into a white pill when focused, a check for the current choice, and the top
 * bar's pill buttons. Replaces the stock Android dialogs everywhere.
 *
 * List labels may start with the "✓  " / "     " markers MainActivity.mark() adds; they become a
 * check on the right.
 */
final class GlassMenu {

    interface OnPick {
        void onPick(int which);
    }

    interface OnText {
        void onText(String text);
    }

    /** A tile in the TV Home menu grid. */
    static final class Tile {
        final int icon;
        final String title, value;
        final Runnable action;

        Tile(int icon, String title, String value, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.value = value;
            this.action = action;
        }
    }

    /** A progress pop-up whose line can change ("Downloading… 42%"). */
    static final class Progress {
        final Dialog dialog;
        final TextView line;

        Progress(Dialog dialog, TextView line) {
            this.dialog = dialog;
            this.line = line;
        }

        void set(CharSequence text) {
            line.setText(text);
        }

        void dismiss() {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    /** Changes a value with Left/Right (volume); returns the new value to show. */
    interface Stepper {
        CharSequence step(int direction);
    }

    /**
     * A row on a settings page: an icon (a drawable resource, tinted, or an app's own Drawable), a name,
     * the current value on the right, and what OK does. external() marks a row that opens one of
     * Android's own settings pages.
     */
    static final class Row {
        final Object icon;
        final CharSequence title;
        final CharSequence value;
        final Runnable action;
        boolean external;
        Stepper stepper;

        Row(Object icon, CharSequence title, CharSequence value, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.value = value;
            this.action = action;
        }

        Row external() {
            external = true;
            return this;
        }

        Row stepper(Stepper s) {
            stepper = s;
            return this;
        }
    }

    private static final String CHECK = "✓  ", BLANK = "     ";

    /** Open glass menus, so the Home button can close them all. */
    private static final java.util.Set<Dialog> OPEN =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static void track(Dialog d) {
        OPEN.add(d);
    }

    static void closeAll() {
        for (Dialog d : new java.util.ArrayList<>(OPEN)) {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {
            }
        }
        OPEN.clear();
    }

    private GlassMenu() {
    }

    // ---- building blocks ---------------------------------------------------------------------------

    static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static Typeface font(Activity a) {
        try {
            return a.getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static TextView text(Activity a, CharSequence s, float sp, int color) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(font(a));
        // Heavier than the font's regular weight so it reads well from the sofa.
        v.setFontVariationSettings("'wght' " + (sp >= 24 ? 650 : sp >= 17 ? 580 : 540));
        return v;
    }

    static TextView text(Activity a, CharSequence s, float sp, ColorStateList colors) {
        TextView v = text(a, s, sp, Color.WHITE);
        v.setTextColor(colors);
        v.setDuplicateParentStateEnabled(true);
        return v;
    }

    /** The glass card and the dialog around it. */
    private static Dialog dialog(Activity a, View content, int widthDp) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel);
        int pad = dp(a, 30);
        card.setPadding(pad, dp(a, 26), pad, dp(a, 26));
        card.addView(content);
        d.setContentView(card, new ViewGroup.LayoutParams(dp(a, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        Sounds.attach(d);
        OPEN.add(d);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(R.style.GlassDialogAnim);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        return d;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private static void header(Activity a, LinearLayout into, CharSequence kicker, CharSequence title) {
        if (kicker != null) {
            TextView k = text(a, kicker, 13, 0xCCFFFFFF);
            k.setAllCaps(true);
            k.setLetterSpacing(0.14f);
            into.addView(k);
        }
        if (title != null) {
            TextView t = text(a, title, 26, Color.WHITE);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kicker != null ? dp(a, 4) : 0;
            lp.bottomMargin = dp(a, 16);
            into.addView(t, lp);
        }
    }

    /** A frosted pill button, as in the top bar. */
    static TextView pill(Activity a, CharSequence label, View.OnClickListener click) {
        TextView v = new TextView(a, null, 0, R.style.TopButton);
        v.setText(label);
        v.setFontVariationSettings("'wght' 600");
        v.setPadding(dp(a, 22), 0, dp(a, 22), 0);
        v.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 42));
        lp.leftMargin = dp(a, 10);
        v.setLayoutParams(lp);
        return v;
    }

    private static LinearLayout buttonRow(Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 24);
        bar.setLayoutParams(lp);
        return bar;
    }

    // ---- list --------------------------------------------------------------------------------------

    static Dialog list(Activity a, CharSequence title, CharSequence[] labels, OnPick pick) {
        return list(a, null, title, labels, pick);
    }

    /** A list of choices; the checked one (or the first) gets focus. Long lists scroll. */
    static Dialog list(Activity a, CharSequence kicker, CharSequence title, CharSequence[] labels, OnPick pick) {
        LinearLayout content = column(a);
        header(a, content, kicker, title);
        LinearLayout rows = column(a);
        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        View focus = null;
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            String s = String.valueOf(labels[i]);
            boolean checked = s.startsWith(CHECK);
            if (checked) s = s.substring(CHECK.length());
            else if (s.startsWith(BLANK)) s = s.substring(BLANK.length());

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.menu_row_bg);
            row.setPadding(dp(a, 18), 0, dp(a, 18), 0);
            TextView label = text(a, s, 17, ink);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (checked) {
                TextView tick = text(a, "✓", 18, ink);
                tick.setPadding(dp(a, 12), 0, 0, 0);
                row.addView(tick);
            }
            final int which = i;
            row.setOnClickListener(v -> {
                holder[0].dismiss();
                pick.onPick(which);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 50));
            lp.topMargin = i == 0 ? 0 : dp(a, 4);
            rows.addView(row, lp);
            if (checked && focus == null) focus = row;
        }
        if (focus == null && rows.getChildCount() > 0) focus = rows.getChildAt(0);

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        int full = labels.length * dp(a, 54);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(full, dp(a, 380))));

        Dialog d = dialog(a, content, 560);
        holder[0] = d;
        final View first = focus;
        d.setOnShowListener(x -> {
            if (first != null) first.requestFocus();
        });
        d.show();
        return d;
    }

    // ---- grid --------------------------------------------------------------------------------------

    /** Tiles with an icon, a name and the current value; the TV Home menu. */
    static Dialog grid(Activity a, CharSequence kicker, CharSequence title, Tile[] tiles, int columns) {
        LinearLayout content = column(a);
        header(a, content, kicker, title);
        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        ColorStateList dim = a.getColorStateList(R.color.menu_ink_dim);
        final Dialog[] holder = new Dialog[1];
        LinearLayout line = null;
        View first = null;
        int tileDp = columns >= 4 ? 190 : 214;
        int tileW = dp(a, tileDp), tileH = dp(a, 100 * Math.max(1f, a.getResources().getConfiguration().fontScale * 0.95f)), gap = dp(a, 12);
        for (int i = 0; i < tiles.length; i++) {
            if (i % columns == 0) {
                line = new LinearLayout(a);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = i == 0 ? 0 : gap;
                content.addView(line, lp);
            }
            Tile t = tiles[i];
            LinearLayout tile = column(a);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setBackgroundResource(R.drawable.menu_tile_bg);
            tile.setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 12));
            ImageView icon = new ImageView(a);
            icon.setImageResource(t.icon);
            icon.setImageTintList(ink);
            icon.setDuplicateParentStateEnabled(true);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)));
            TextView name = text(a, t.title, 17, ink);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(a, 10), 0, 0);
            tile.addView(name);
            if (t.value != null && !t.value.isEmpty()) {
                TextView value = text(a, t.value, 13, dim);
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
                tile.addView(value);
            }
            tile.setOnClickListener(v -> {
                holder[0].dismiss();
                t.action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.leftMargin = i % columns == 0 ? 0 : gap;
            line.addView(tile, lp);
            if (first == null) first = tile;
        }
        int width = columns * tileDp + (columns - 1) * 12 + 60;
        Dialog d = dialog(a, content, width);
        holder[0] = d;
        final View f = first;
        d.setOnShowListener(x -> {
            if (f != null) f.requestFocus();
        });
        d.show();
        return d;
    }

    // ---- settings page -----------------------------------------------------------------------------

    /** A summary block at the top of a page: one large line and smaller ones under it. */
    static LinearLayout hero(Activity a, CharSequence big, CharSequence... lines) {
        LinearLayout h = column(a);
        TextView b = text(a, big, 30, Color.WHITE);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        h.addView(b);
        for (CharSequence l : lines) {
            if (l == null || l.length() == 0) continue;
            TextView t = text(a, l, 16, 0xE0FFFFFF);
            t.setPadding(0, dp(a, 3), 0, 0);
            h.addView(t);
        }
        return h;
    }

    /** A rounded bar filled to fraction (storage used). */
    static View meter(Activity a, float fraction) {
        final float f = Math.max(0f, Math.min(1f, fraction));
        View v = new View(a) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            @Override
            protected void onDraw(Canvas c) {
                float h = getHeight(), w = getWidth();
                r.set(0, 0, w, h);
                p.setColor(0x26FFFFFF);
                c.drawRoundRect(r, h / 2, h / 2, p);
                r.set(0, 0, Math.max(h, w * f), h);
                p.setColor(0xE6FFFFFF);
                c.drawRoundRect(r, h / 2, h / 2, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 10));
        lp.topMargin = dp(a, 12);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * A settings page: header, an optional summary, then rows. Back returns through {@code back}
     * (the page it came from); focus starts on row {@code focusIndex}.
     */
    static Dialog page(Activity a, CharSequence kicker, CharSequence title, View hero, List<Row> rows,
                       Runnable back, int focusIndex) {
        LinearLayout content = column(a);
        header(a, content, kicker, title);
        if (hero != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(a, 18);
            content.addView(hero, lp);
        }
        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        ColorStateList dim = a.getColorStateList(R.color.menu_ink_dim);
        LinearLayout list = column(a);
        final Dialog[] holder = new Dialog[1];
        View focus = null;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.menu_row_bg);
            row.setPadding(dp(a, 18), 0, dp(a, 18), 0);
            if (r.icon != null) {
                ImageView icon = new ImageView(a);
                int size;
                if (r.icon instanceof Integer) {
                    icon.setImageResource((Integer) r.icon);
                    icon.setImageTintList(ink);
                    icon.setDuplicateParentStateEnabled(true);
                    size = 22;
                } else {
                    icon.setImageDrawable((Drawable) r.icon);
                    size = 30;
                }
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(a, size), dp(a, size));
                ilp.rightMargin = dp(a, 16);
                row.addView(icon, ilp);
            }
            TextView name = text(a, r.title, 17, ink);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = null;
            if (r.value != null || r.stepper != null) {
                value = text(a, r.stepper != null ? "‹   " + r.value + "   ›" : r.value, 15, dim);
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
                value.setMaxWidth(dp(a, 280));
                value.setPadding(dp(a, 12), 0, 0, 0);
                row.addView(value);
            }
            if (r.external) {
                ImageView out = new ImageView(a);
                out.setImageResource(R.drawable.ic_s_open);
                out.setImageTintList(dim);
                out.setDuplicateParentStateEnabled(true);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(a, 16), dp(a, 16));
                olp.leftMargin = dp(a, 12);
                row.addView(out, olp);
            }
            if (r.stepper != null) {
                final TextView v = value;
                row.setOnKeyListener((x, keyCode, e) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        v.setText("‹   " + r.stepper.step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + "   ›");
                    }
                    return true;
                });
            }
            if (r.action != null) {
                row.setOnClickListener(v -> {
                    holder[0].dismiss();
                    r.action.run();
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 56));
            lp.topMargin = i == 0 ? 0 : dp(a, 4);
            list.addView(row, lp);
            if (i == focusIndex || focus == null) focus = row;
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        int full = rows.size() * dp(a, 60);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(full, dp(a, hero != null ? 330 : 420))));
        Dialog d = dialog(a, content, 660);
        holder[0] = d;
        if (back != null) d.setOnCancelListener(x -> back.run());
        final View f = focus;
        d.setOnShowListener(x -> {
            if (f != null) f.requestFocus();
        });
        d.show();
        return d;
    }

    /** A glass card with a header and any content (the forecast); Back goes to {@code back}. */
    static Dialog panel(Activity a, CharSequence kicker, CharSequence title, View body, int widthDp, Runnable back) {
        LinearLayout content = column(a);
        header(a, content, kicker, title);
        content.addView(body);
        Dialog d = dialog(a, content, widthDp);
        if (back != null) d.setOnCancelListener(x -> back.run());
        d.setOnShowListener(x -> {
            View f = body.findFocus();
            if (f == null) {
                java.util.ArrayList<View> all = body.getFocusables(View.FOCUS_FORWARD);
                if (!all.isEmpty()) all.get(0).requestFocus();
            }
        });
        d.show();
        return d;
    }

    // ---- dropdown ----------------------------------------------------------------------------------

    /** A small glass menu hanging under {@code anchor}, right-aligned with it: icon and name per row. */
    static Dialog dropdown(Activity a, View anchor, int[] icons, CharSequence[] labels, OnPick pick) {
        LinearLayout content = column(a);
        ColorStateList ink = a.getColorStateList(R.color.menu_ink);
        final Dialog[] holder = new Dialog[1];
        View first = null;
        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.menu_row_bg);
            row.setPadding(dp(a, 16), 0, dp(a, 18), 0);
            ImageView icon = new ImageView(a);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ink);
            icon.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22));
            ilp.rightMargin = dp(a, 14);
            row.addView(icon, ilp);
            row.addView(text(a, labels[i], 17, ink));
            final int which = i;
            row.setOnClickListener(v -> {
                holder[0].dismiss();
                pick.onPick(which);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 50));
            lp.topMargin = i == 0 ? 0 : dp(a, 4);
            content.addView(row, lp);
            if (first == null) first = row;
        }
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel);
        card.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));
        card.addView(content);
        d.setContentView(card, new ViewGroup.LayoutParams(dp(a, 250), ViewGroup.LayoutParams.WRAP_CONTENT));
        Sounds.attach(d);
        OPEN.add(d);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(R.style.GlassDialogAnim);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            int[] at = new int[2];
            anchor.getLocationOnScreen(at);
            int screenW = a.getResources().getDisplayMetrics().widthPixels;
            w.setGravity(Gravity.TOP | Gravity.END);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = Math.max(0, screenW - (at[0] + anchor.getWidth()));
            lp.y = at[1] + anchor.getHeight() + dp(a, 10);
            w.setAttributes(lp);
        }
        holder[0] = d;
        final View f = first;
        d.setOnShowListener(x -> {
            if (f != null) f.requestFocus();
        });
        d.show();
        return d;
    }

    // ---- message -----------------------------------------------------------------------------------

    /**
     * A title, a message and pill buttons (left to right as given). The first button has focus; Back
     * closes without a choice.
     */
    static Dialog message(Activity a, CharSequence title, CharSequence message, CharSequence[] buttons, OnPick pick) {
        LinearLayout content = column(a);
        header(a, content, null, title);
        TextView body = text(a, message, 17, 0xF0FFFFFF);
        body.setLineSpacing(0, 1.2f);
        content.addView(body);
        LinearLayout bar = buttonRow(a);
        final Dialog[] holder = new Dialog[1];
        View first = null;
        for (int i = 0; i < buttons.length; i++) {
            final int which = i;
            TextView b = pill(a, buttons[i], v -> {
                holder[0].dismiss();
                if (pick != null) pick.onPick(which);
            });
            bar.addView(b);
            if (first == null) first = b;
        }
        content.addView(bar);
        Dialog d = dialog(a, content, 600);
        holder[0] = d;
        final View f = first;
        d.setOnShowListener(x -> {
            if (f != null) f.requestFocus();
        });
        d.show();
        return d;
    }

    static Progress progress(Activity a, CharSequence title) {
        LinearLayout content = column(a);
        header(a, content, null, title);
        TextView line = text(a, "0%", 20, 0xF0FFFFFF);
        content.addView(line);
        Dialog d = dialog(a, content, 480);
        d.setCancelable(false);
        d.show();
        return new Progress(d, line);
    }

    /** A text field with OK and Cancel; the TV keyboard opens on it. */
    static Dialog input(Activity a, CharSequence title, String initial, OnText ok) {
        LinearLayout content = column(a);
        header(a, content, null, title);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setText(initial);
        field.setSelection(initial.length());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTextColor(Color.WHITE);
        field.setTextSize(19);
        field.setTypeface(font(a));
        field.setBackgroundResource(R.drawable.menu_input_bg);
        field.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        content.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final Dialog[] holder = new Dialog[1];
        Runnable done = () -> {
            String s = field.getText().toString().trim();
            holder[0].dismiss();
            if (!s.isEmpty()) ok.onText(s);
        };
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_UP)) {
                done.run();
                return true;
            }
            return false;
        });
        LinearLayout bar = buttonRow(a);
        bar.addView(pill(a, a.getString(android.R.string.cancel), v -> holder[0].dismiss()));
        bar.addView(pill(a, a.getString(android.R.string.ok), v -> done.run()));
        content.addView(bar);
        Dialog d = dialog(a, content, 560);
        holder[0] = d;
        d.setOnShowListener(x -> field.requestFocus());
        d.show();
        return d;
    }
}
