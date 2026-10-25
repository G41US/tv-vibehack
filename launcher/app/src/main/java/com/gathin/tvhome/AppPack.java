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
 *  - SmartTube: open-source YouTube app without ads, from its developer's own GitHub release.
 *  - Movie and TV apps: opens the Play Store on the service.
 *  - SportzX Live: third-party community download link; APK is not independently verified.
 *  - Voltra TV: TV APK from Voltra's official download site.
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

    /** Community mirror redirect for SportzX Live v3.2; not independently verified. */
    private static final String SPORTZX_LIVE_URL = "https://simturax.com/2970";
    /** Official Voltra TV APK download endpoint. */
    private static final String VOLTRA_TV_URL = "https://voltra.tv/voltratvversion.apk";

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
        labels.add("SportzX Live  ·  third-party APK");
        actions.add(() -> confirmThirdPartyInstall(act, installer, "SportzX Live",
                SPORTZX_LIVE_URL,
                "This downloads SportzX Live from a community mirror. The APK has not been independently verified. Continue only if you accept that risk."));
        labels.add("Voltra TV  ·  official TV APK");
        actions.add(() -> confirmThirdPartyInstall(act, installer, "Voltra TV",
                VOLTRA_TV_URL,
                "This downloads the Android TV APK from Voltra's official website. Android may ask you to allow installs from TV Home."));
        labels.add(act.getString(R.string.apps_movies));
        actions.add(() -> showStoreApps(act));
        GlassMenu.list(act, act.getString(R.string.menu_title), act.getString(R.string.apps_title),
                labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    private static void confirmThirdPartyInstall(Activity act, Updater installer,
                                                  String name, String url, String message) {
        GlassMenu.message(act, name, message,
                new String[]{"Download and install", "Cancel"}, which -> {
                    if (which == 0) {
                        installer.installFrom(url, null, null,
                                act.getString(R.string.apps_downloading, name));
                    }
                });
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
        GlassMenu.list(act, act.getString(R.string.apps_title), act.getString(R.string.apps_movies_title),
                labels, which -> openStore(act, STORE_APPS[which]));
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
