package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * First-run setup, done with the remote alone: no phone, computer or developer options.
 *
 *   1. Home button: switch on HomeKeyService in Accessibility. It makes the Home button and start-up
 *      open TV Home even on boxes whose firmware opens its own home screen (Airtel Smart Router).
 *   2. Updates: allow TV Home to install apps, so updates install from the TV.
 *   3. Default home (when the box offers a picker): optional, the service already covers it.
 *
 * Shown by itself when the Home button service is off (at most once a day after "Later"), and from
 * the TV Home menu (hold OK on Settings > Setup).
 */
final class SetupPanel {

    private static final String PREFS = "setup", KEY_LATER = "later_at";
    private static final long NAG_EVERY_MS = 24L * 60 * 60 * 1000;
    static final int REQUEST_HOME_ROLE = 4711, REQUEST_PHOTOS = 4712;

    private final Activity act;
    private final Updater updater;
    private final float density;
    private Dialog dialog;
    private LinearLayout rows;

    SetupPanel(Activity act, Updater updater) {
        this.act = act;
        this.updater = updater;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    // ---- checks ------------------------------------------------------------------------------------

    static boolean serviceOn(Context ctx) {
        String cur = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null) return false;
        String pkg = ctx.getPackageName();
        return cur.contains(pkg + "/" + HomeKeyService.class.getName()) || cur.contains(pkg + "/.HomeKeyService");
    }

    static boolean installsAllowed(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** True/false when the box has a home picker; null when it has none (the service covers Home). */
    private Boolean defaultHome() {
        RoleManager rm = act.getSystemService(RoleManager.class);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_HOME)) return null;
        if (rm.isRoleHeld(RoleManager.ROLE_HOME)) return true;
        // Some TVs (TCL among them) have the role but no screen to choose it: then skip the step,
        // the Home button service covers it.
        boolean canAsk = canOpen(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                || canOpen(new Intent(Settings.ACTION_HOME_SETTINGS));
        return canAsk ? false : null;
    }

    private boolean canOpen(Intent i) {
        try {
            return i.resolveActivity(act.getPackageManager()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the required steps are done (the optional ones don't count). */
    boolean allDone() {
        return serviceOn(act) && installsAllowed(act) && (!BackupStore.supported() || BackupStore.canRead(act));
    }

    // ---- showing -----------------------------------------------------------------------------------

    /** On resume: refresh an open panel, or open it when the Home button service is off. */
    void onResume() {
        if (dialog != null && dialog.isShowing()) {
            fill();
            return;
        }
        if (serviceOn(act)) return;
        long later = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LATER, 0);
        if (System.currentTimeMillis() - later < NAG_EVERY_MS) return;
        show();
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Sounds.attach(dialog);
        int pad = dp(32);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundResource(R.drawable.glass_panel);
        box.setMinimumWidth(dp(640));

        box.addView(text(act.getString(R.string.setup_kicker), 12, 0xCCFFFFFF, 0, 0.14f));
        box.addView(text(act.getString(R.string.setup_title), 28, 0xFFFFFFFF, dp(6), 0));
        box.addView(text(act.getString(R.string.setup_intro), 15, 0xB3FFFFFF, dp(6), 0));

        rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.END);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(26);
        TextView done = pill(act.getString(R.string.setup_done));
        done.setId(View.generateViewId());
        done.setOnClickListener(v -> {
            if (!serviceOn(act)) {
                act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_LATER, System.currentTimeMillis()).apply();
            }
            dialog.dismiss();
        });
        bar.addView(done);
        box.addView(bar, barLp);

        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        fill();
        dialog.setOnShowListener(d -> focusFirstAction(done));
        dialog.show();
    }

    private void fill() {
        rows.removeAllViews();
        int n = 1;
        boolean on = serviceOn(act);
        addRow(n++, on, R.string.setup_home_title, R.string.setup_home_desc,
                on ? 0 : R.string.setup_turn_on, this::openAccessibility);
        boolean installs = installsAllowed(act);
        addRow(n++, installs, R.string.setup_updates_title, R.string.setup_updates_desc,
                installs ? 0 : R.string.setup_allow, this::openInstallSettings);
        if (BackupStore.supported()) {
            boolean backup = BackupStore.canRead(act);
            addRow(n++, backup, R.string.setup_backup_title, R.string.setup_backup_desc,
                    backup ? 0 : R.string.setup_allow,
                    () -> act.requestPermissions(new String[]{BackupStore.readPermission()}, REQUEST_PHOTOS));
        }
        // Optional: SmartTube and licensed movie apps. Shows as done once SmartTube is on the box.
        boolean apps = AppPack.smartTubeInstalled(act);
        addRow(n++, apps, R.string.setup_apps_title, R.string.setup_apps_desc,
                apps ? 0 : R.string.setup_choose, () -> AppPack.show(act, updater));
        Boolean home = defaultHome();
        if (home != null) {
            addRow(n, home, R.string.setup_default_title, R.string.setup_default_desc,
                    home ? 0 : R.string.setup_choose, this::requestHomeRole);
        }
    }

    private void addRow(int number, boolean done, int titleRes, int descRes, int actionRes, Runnable action) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(22);

        TextView badge = new TextView(act);
        badge.setText(done ? "✓" : String.valueOf(number));
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(16);
        badge.setTextColor(done ? 0xFF0B0D14 : 0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (done) circle.setColor(0xFF5BD68A);
        else circle.setStroke(dp(1), 0x66FFFFFF);
        badge.setBackground(circle);
        row.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(18);
        colLp.rightMargin = dp(18);
        col.addView(text(act.getString(titleRes), 18, done ? 0xB3FFFFFF : 0xFFFFFFFF, 0, 0));
        col.addView(text(act.getString(descRes), 13, 0xCCFFFFFF, dp(3), 0));
        row.addView(col, colLp);

        if (actionRes != 0) {
            TextView button = pill(act.getString(actionRes));
            button.setTag("action");
            button.setOnClickListener(v -> action.run());
            row.addView(button);
        }
        rows.addView(row, rowLp);
    }

    private void focusFirstAction(View fallback) {
        View first = rows.findViewWithTag("action");
        (first != null ? first : fallback).requestFocus();
    }

    // ---- actions -----------------------------------------------------------------------------------

    private void openAccessibility() {
        Toast.makeText(act, R.string.setup_home_toast, Toast.LENGTH_LONG).show();
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(act, HomeKeyService.class).flattenToString());
        startFirst(R.string.setup_home_title, R.string.setup_manual_home,
                android.os.Build.VERSION.SDK_INT >= 33 ? details : null,
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openInstallSettings() {
        startFirst(R.string.setup_updates_title, R.string.setup_manual_updates,
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())),
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                new Intent(Settings.ACTION_SECURITY_SETTINGS));
    }

    /**
     * Opens the first of these settings pages this TV really has. If it has none, TV Home's own glass
     * message says where to find the setting with the remote, and that the setup script does it too:
     * never Android's "No application can handle this action".
     */
    private void startFirst(int titleRes, int manualRes, Intent... options) {
        for (Intent i : options) {
            if (i == null || !canOpen(i)) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        GlassMenu.message(act, act.getString(titleRes), act.getString(manualRes),
                new String[]{act.getString(android.R.string.ok)}, null);
    }

    private void requestHomeRole() {
        RoleManager rm = act.getSystemService(RoleManager.class);
        Intent ask = rm != null ? rm.createRequestRoleIntent(RoleManager.ROLE_HOME) : null;
        if (ask != null && canOpen(ask)) {
            try {
                act.startActivityForResult(ask, REQUEST_HOME_ROLE);
                return;
            } catch (Exception ignored) {
            }
        }
        startFirst(R.string.setup_default_title, R.string.setup_manual_default, new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- views -------------------------------------------------------------------------------------

    private TextView text(String s, int sp, int color, int topMargin, float letterSpacing) {
        TextView v = new TextView(act);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(act.getResources().getFont(R.font.outfit));
        v.setFontVariationSettings("'wght' " + (sp >= 24 ? 650 : sp >= 16 ? 580 : 540));
        if (letterSpacing > 0) {
            v.setLetterSpacing(letterSpacing);
            v.setAllCaps(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        v.setLayoutParams(lp);
        return v;
    }

    /** A frosted pill like the top bar's buttons. */
    private TextView pill(String s) {
        TextView v = new TextView(act, null, 0, R.style.TopButton);
        v.setText(s);
        v.setPadding(dp(22), 0, dp(22), 0);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
