package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional apps offered on first setup and from the TV Home menu (hold OK on Settings > Get apps).
 * Nothing installs without the person choosing it.
 *
 *  - SmartTube: open-source YouTube app without ads, from its developer's own GitHub release. Once
 *    it's installed it takes YouTube's place in the dock (YouTube stays in the app list).
 *  - Movie and TV apps: opens the Play Store on the service, which handles availability and sign-in.
 *    Only licensed services are listed.
 */
final class AppPack {

    /** SmartTube's official stable download (github.com/yuliskov/SmartTube). */
    static final String SMARTTUBE_URL =
            "https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk";
    /** Package names SmartTube has used: current stable and beta, F-Droid, and the older one. */
    static final String[] SMARTTUBE_PACKAGES = {
            "org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid", "com.teamsmart.videomanager.tv"};
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String KEY_SWAP_YOUTUBE = "swap_youtube";

    /** Licensed movie and TV services, searched for in the Play Store by name. */
    private static final String[] STORE_APPS = {"Netflix", "Prime Video", "Showmax", "Plex"};
    private static final int[] STORE_APP_DESC = {
            R.string.apps_netflix, R.string.apps_prime, R.string.apps_showmax, R.string.apps_plex};

    private AppPack() {
    }

    static boolean isSmartTube(String pkg) {
        for (String p : SMARTTUBE_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    static boolean smartTubeInstalled(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : SMARTTUBE_PACKAGES) {
            try {
                pm.getPackageInfo(p, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    static void show(Activity act, Updater installer) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean st = smartTubeInstalled(act);
        labels.add(act.getString(st ? R.string.apps_smarttube_installed : R.string.apps_smarttube));
        actions.add(() -> {
            if (st) {
                swapYouTubeInDock(act);
                return;
            }
            // Remember the choice: when SmartTube shows up, the dock swaps YouTube for it.
            act.getSharedPreferences(AppAdapter.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_SWAP_YOUTUBE, true).apply();
            installer.installFrom(SMARTTUBE_URL, null, null, act.getString(R.string.apps_downloading, "SmartTube"));
        });
        labels.add(act.getString(R.string.apps_movies));
        actions.add(() -> showStoreApps(act));
        new AlertDialog.Builder(act, Updater.DIALOG_THEME)
                .setTitle(R.string.apps_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private static void swapYouTubeInDock(Activity act) {
        act.getSharedPreferences(AppAdapter.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SWAP_YOUTUBE, true).apply();
        Toast.makeText(act, R.string.apps_smarttube_docked, Toast.LENGTH_LONG).show();
        act.recreate();
    }

    private static void showStoreApps(Activity act) {
        String[] labels = new String[STORE_APPS.length];
        for (int i = 0; i < STORE_APPS.length; i++) {
            labels[i] = STORE_APPS[i] + "  ·  " + act.getString(STORE_APP_DESC[i]);
        }
        new AlertDialog.Builder(act, Updater.DIALOG_THEME)
                .setTitle(R.string.apps_movies_title)
                .setItems(labels, (d, which) -> openStore(act, STORE_APPS[which]))
                .show();
    }

    private static void openStore(Activity act, String name) {
        String q = Uri.encode(name);
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + q + "&c=apps"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(market);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/search?q=" + q + "&c=apps"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Toast.makeText(act, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
