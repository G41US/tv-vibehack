package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recent apps, like a phone's app switcher: the apps used lately as big cards, newest first. OK goes
 * back to one; Close under a card shuts that app down; Clear all closes them all. Opened from the
 * Quick panel (hold Back anywhere; hold it again for this), or a remote's "recent apps" button. Android keeps live pictures of other apps to itself, so each card
 * shows the app's own banner. The list comes from Android's usage records (usage access, which
 * "Set up on this TV" grants).
 */
public class Recents extends Activity {

    private static final String PREFS = "recents", KEY_CLEARED = "cleared_";
    private static final int MAX = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout row;
    private TextView empty;
    private ImageView ambient;

    static void open(Context c) {
        c.startActivity(new Intent(c, Recents.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** An app opened lately, newest first. */
    static final class App {
        String pkg, label;
        long used;
        Drawable banner, icon;
    }

    /** The apps used in the last few days, newest first, without TV Home, home screens or closed ones. */
    static List<App> recent(Context c, int max) {
        List<App> out = new ArrayList<>();
        if (!DataByApp.allowed(c)) return out;
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        Map<String, Long> last = new LinkedHashMap<>();
        try {
            UsageEvents ev = usm.queryEvents(now - 3 * DateUtils.DAY_IN_MILLIS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
            }
        } catch (Exception ignored) {
            return out;
        }
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.add("android");
        skip.add("com.android.systemui");
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            skip.add(r.activityInfo.packageName);
        }
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        sorted.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Long> en : sorted) {
            String pkg = en.getKey();
            if (skip.contains(pkg) || en.getValue() <= p.getLong(KEY_CLEARED + pkg, 0)) continue;
            if (launch(pm, pkg) == null) continue;
            App a = new App();
            a.pkg = pkg;
            a.used = en.getValue();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                a.label = String.valueOf(pm.getApplicationLabel(ai));
                a.banner = pm.getApplicationBanner(ai);
                a.icon = pm.getApplicationIcon(ai);
            } catch (PackageManager.NameNotFoundException ex) {
                continue;
            }
            out.add(a);
            if (out.size() >= max) break;
        }
        return out;
    }

    static Intent launch(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    /** Back to an app where it was (its task comes to the front). */
    static void switchTo(Context c, String pkg) {
        Intent i = launch(c.getPackageManager(), pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Shuts an app down (as Settings' Force stop) and leaves it off the list until it's used again. */
    static void close(Context c, String pkg) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_CLEARED + pkg, System.currentTimeMillis()).apply();
        boolean done = false;
        if (AdbSelf.safeName(pkg) && AdbSelf.ready(c)) {
            try (AdbSelf adb = AdbSelf.connect(c, SelfSetup.PORT, 10_000, () -> {
            })) {
                adb.shell("am force-stop " + pkg);
                done = true;
            } catch (Exception ignored) {
            }
        }
        if (!done) {   // without the debugging connection: what Android lets any app do
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(TextScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sounds.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04060C);
        // The chosen app's own colours, hugely blurred, fill the screen behind its card.
        ambient = new ImageView(this);
        ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(ambient, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0040610, 0xA6040610, 0x8C040610}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView clock = GlassMenu.text(this, android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()), 22, 0xCCFFFFFF);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, GlassMenu.dp(this, 40), GlassMenu.dp(this, 64), 0);
        root.addView(clock, cl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = GlassMenu.dp(this, 64);
        col.setPadding(side, 0, side, 0);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = GlassMenu.text(this, getString(R.string.rc_title), 40, Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(GlassMenu.pill(this, getString(R.string.rc_clear_all), v -> clearAll()));
        col.addView(head);

        row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(GlassMenu.dp(this, 24), GlassMenu.dp(this, 36), GlassMenu.dp(this, 24), GlassMenu.dp(this, 24));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        col.setClipChildren(false);
        scroll.addView(row);
        col.addView(scroll);
        empty = GlassMenu.text(this, getString(R.string.rc_none), 18, 0xB3FFFFFF);
        empty.setVisibility(View.GONE);
        col.addView(empty);
        setContentView(root);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isChangingConfigurations()) finish();   // an app or Home took over: close
    }

    private void load() {
        new Thread(() -> {
            if (!DataByApp.allowed(this) && AdbSelf.ready(this)) {   // usage access, as Set up on this TV gives it
                try (AdbSelf adb = AdbSelf.connect(this, SelfSetup.PORT, 30_000, () -> {
                })) {
                    adb.shell("appops set " + getPackageName() + " GET_USAGE_STATS allow");
                } catch (Exception ignored) {
                }
            }
            boolean allowed = DataByApp.allowed(this);
            List<App> apps = recent(this, MAX);
            main.post(() -> {
                if (isFinishing()) return;
                if (!allowed) empty.setText(R.string.rc_need);
                for (App a : apps) row.addView(card(a, row.getChildCount()));
                empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                if (row.getChildCount() > 0) row.getChildAt(0).findViewById(R.id.card).requestFocus();
            });
        }, "recents").start();
    }

    private View card(App app, int index) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setClipChildren(false);
        t.setTag(app.pkg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = GlassMenu.dp(this, 28);
        t.setLayoutParams(tlp);

        FrameLayout card = new FrameLayout(this);
        card.setId(R.id.card);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(GlassMenu.dp(this, 22));
        bg.setColor(0xFF1C2238);
        card.setBackground(bg);
        card.setElevation(GlassMenu.dp(this, 6));
        ImageView pic = new ImageView(this);
        if (app.banner != null) {
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setImageDrawable(app.banner);
            card.addView(pic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            pic.setImageDrawable(app.icon);
            card.addView(pic, new FrameLayout.LayoutParams(GlassMenu.dp(this, 96), GlassMenu.dp(this, 96), Gravity.CENTER));
        }
        // A soft sheen across the top, like glass.
        View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x30FFFFFF, 0x00FFFFFF}));
        card.addView(sheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GlassMenu.dp(this, 90)));
        t.addView(card, new LinearLayout.LayoutParams(GlassMenu.dp(this, 368), GlassMenu.dp(this, 207)));

        TextView name = GlassMenu.text(this, app.label, 20, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(GlassMenu.dp(this, 368), ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = GlassMenu.dp(this, 14);
        t.addView(name, nlp);
        TextView when = GlassMenu.text(this, DateUtils.getRelativeTimeSpanString(app.used, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS), 14, 0x99FFFFFF);
        when.setGravity(Gravity.CENTER_HORIZONTAL);
        t.addView(when, new LinearLayout.LayoutParams(GlassMenu.dp(this, 368), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView close = GlassMenu.pill(this, "✕  " + getString(R.string.rc_close), v -> closeCard(t, app));
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) close.getLayoutParams();
        clp.leftMargin = 0;
        clp.topMargin = GlassMenu.dp(this, 12);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        t.addView(close, clp);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(GlassMenu.dp(this, 22));
        ring.setStroke(GlassMenu.dp(this, 4), Color.WHITE);
        android.view.animation.OvershootInterpolator spring = new android.view.animation.OvershootInterpolator(2f);
        android.graphics.Bitmap glow = Ambient.tiny(app.banner != null ? app.banner : app.icon);
        card.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.1f : 0.96f).scaleY(has ? 1.1f : 0.96f).translationZ(has ? GlassMenu.dp(this, 16) : 0)
                    .setInterpolator(spring).setDuration(300).start();
            card.setForeground(has ? ring : null);
            t.animate().alpha(has ? 1f : 0.6f).setStartDelay(0).setDuration(200).start();
            if (has) Ambient.show(ambient, glow);
        });
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.setOnClickListener(v -> {
            switchTo(this, app.pkg);
            finish();
        });
        card.setOnKeyListener((v, code, e) -> {
            // Menu or the remote's "info" key on a card closes that app, like swiping it away on a phone.
            if (e.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO)) {
                closeCard(t, app);
                return true;
            }
            return false;
        });
        t.setAlpha(0f);
        t.setTranslationX(GlassMenu.dp(this, 40));
        t.animate().alpha(0.6f).translationX(0).setStartDelay(50L * index).setDuration(260).start();
        return t;
    }

    private void closeCard(View t, App app) {
        new Thread(() -> close(this, app.pkg), "recents-close").start();
        int at = row.indexOfChild(t);
        t.animate().alpha(0f).translationY(-GlassMenu.dp(this, 60)).setDuration(220).withEndAction(() -> {
            row.removeView(t);
            if (row.getChildCount() == 0) {
                empty.setVisibility(View.VISIBLE);
                return;
            }
            View next = row.getChildAt(Math.min(at, row.getChildCount() - 1));
            next.findViewById(R.id.card).requestFocus();
        }).start();
    }

    private void clearAll() {
        List<String> pkgs = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            Object tag = c.getTag();
            if (tag instanceof String) pkgs.add((String) tag);
        }
        new Thread(() -> {
            for (String p : pkgs) close(this, p);
        }, "recents-clear").start();
        row.animate().alpha(0f).translationY(-GlassMenu.dp(this, 60)).setDuration(260).withEndAction(this::finish).start();
    }
}
