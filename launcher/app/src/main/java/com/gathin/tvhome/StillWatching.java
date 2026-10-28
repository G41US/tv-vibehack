package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Still watching?": when no remote button has been pressed for a few hours (HomeKeyService sees every
 * key), the TV asks, with a countdown. Nobody answers, and it turns off: the box goes to sleep (most TVs
 * follow over HDMI and switch off too) or, if sleeping isn't wanted or doesn't work, the screen goes
 * black, which pauses whatever was playing. Saves power when someone falls asleep in front of the TV.
 */
final class StillWatching {

    static final String PREFS = "still", KEY_HOURS = "hours", KEY_ACTION = "action";
    static final String SLEEP = "sleep", BLACK = "black";
    private static final String[] HOURS = {"0", "2", "3", "4"};

    private StillWatching() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Hours without a button press before asking; 0 = off. */
    static int hours(Context ctx) {
        try {
            return Integer.parseInt(prefs(ctx).getString(KEY_HOURS, "3"));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /**
     * Sleep by default, except on a box that is also the home router (Airtel Smart Router): there the
     * screen only goes black unless the person picks sleep, so the Wi-Fi is never at stake.
     */
    static String action(Context ctx) {
        String def = isRouterBox(ctx) ? BLACK : SLEEP;
        return prefs(ctx).getString(KEY_ACTION, def);
    }

    static boolean isRouterBox(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("com.nes.hometv", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void showSettings(Activity act) {
        int h = hours(act);
        String a = action(act);
        String[] labels = {
                (h == 0 ? "✓  " : "     ") + act.getString(R.string.still_off),
                (h == 2 ? "✓  " : "     ") + act.getString(R.string.still_after, 2),
                (h == 3 ? "✓  " : "     ") + act.getString(R.string.still_after, 3),
                (h == 4 ? "✓  " : "     ") + act.getString(R.string.still_after, 4),
                (SLEEP.equals(a) ? "✓  " : "     ") + act.getString(R.string.still_action_sleep),
                (BLACK.equals(a) ? "✓  " : "     ") + act.getString(R.string.still_action_black),
                act.getString(R.string.still_try),
        };
        GlassMenu.list(act, act.getString(R.string.menu_title), act.getString(R.string.menu_still), labels, which -> {
            if (which == 6) {
                ask(act, 10);
                return;
            }
            if (which <= 3) prefs(act).edit().putString(KEY_HOURS, HOURS[which]).apply();
            else if (which == 4) prefs(act).edit().putString(KEY_ACTION, SLEEP).apply();
            else prefs(act).edit().putString(KEY_ACTION, BLACK).apply();
            showSettings(act);   // show the new choice ticked
        });
    }

    static void ask(Context ctx, int seconds) {
        ctx.startActivity(new Intent(ctx, Prompt.class).putExtra("seconds", seconds)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    /** Nobody answered: sleep (and fall back to black if the box stays awake), or black. */
    static void turnOff(Context ctx) {
        Context app = ctx.getApplicationContext();
        HomeKeyService s = HomeKeyService.live;
        if (SLEEP.equals(action(app)) && s != null && android.os.Build.VERSION.SDK_INT >= 28
                && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) black(app);   // the box didn't sleep
            }, 3000);
            return;
        }
        black(app);
    }

    private static void black(Context ctx) {
        ctx.startActivity(new Intent(ctx, Black.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The "Still watching?" question with its countdown. Any key answers yes. */
    public static class Prompt extends Activity {
        static volatile boolean showing;

        @Override
        protected void attachBaseContext(Context base) {
            super.attachBaseContext(TextScale.wrap(base));
        }
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int left;
        private TextView count;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                left--;
                if (left <= 0) {
                    finish();
                    turnOff(Prompt.this);
                    return;
                }
                count.setText(getString(R.string.still_countdown, left));
                handler.postDelayed(this, 1000);
            }
        };

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            float d = getResources().getDisplayMetrics().density;
            left = getIntent().getIntExtra("seconds", 60);

            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(0xCC000000);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            TextView title = new TextView(this);
            title.setText(R.string.still_title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(44);
            title.setTypeface(getResources().getFont(R.font.outfit));
            box.addView(title);

            count = new TextView(this);
            count.setText(getString(R.string.still_countdown, left));
            count.setTextColor(0xB3FFFFFF);
            count.setTextSize(18);
            count.setTypeface(getResources().getFont(R.font.outfit));
            count.setPadding(0, Math.round(10 * d), 0, Math.round(28 * d));
            box.addView(count);

            TextView yes = new TextView(this, null, 0, R.style.TopButton);
            yes.setText(R.string.still_yes);
            yes.setPadding(Math.round(26 * d), 0, Math.round(26 * d), 0);
            yes.setOnClickListener(v -> finish());
            box.addView(yes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(46 * d)));

            setContentView(root);
            yes.requestFocus();
            showing = true;
            handler.postDelayed(tick, 1000);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            // Any button means someone's there. OK/Enter also works through the focused button.
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER) {
                finish();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override
        protected void onDestroy() {
            handler.removeCallbacks(tick);
            showing = false;
            super.onDestroy();
        }
    }

    /** A black screen (when the box can't or shouldn't sleep). Any button brings the TV back. */
    public static class Black extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            setContentView(v);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0f;   // dims the panel where the box controls it
            getWindow().setAttributes(lp);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
    }
}
