package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates the launcher from the TV. CI publishes every build as a GitHub release with two assets,
 * version.txt (the versionCode) and tvhome.apk; config.xml's update_url points at
 * https://github.com/OWNER/REPO/releases/latest/download/. Checked at most every 6 hours when Home
 * opens, and on demand from the TV Home menu (hold OK on the Settings button). Installing goes
 * through Android's own confirmation screen.
 */
public final class Updater {

    private static final String PREFS = "update", KEY_LAST_CHECK = "last_check", KEY_SKIPPED = "skipped";
    private static final long AUTO_EVERY_MS = 6L * 60 * 60 * 1000;
    static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private final Activity act;
    private final String base;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    Updater(Activity act) {
        this.act = act;
        this.base = folder(act.getString(R.string.update_url));
    }

    private static String folder(String url) {
        url = url.trim();
        return url.isEmpty() || url.endsWith("/") ? url : url + "/";
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private long installedVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Quiet check when Home opens, at most every few hours. */
    void autoCheck() {
        if (base.isEmpty() || busy) return;
        long now = System.currentTimeMillis();
        if (now - prefs().getLong(KEY_LAST_CHECK, 0) < AUTO_EVERY_MS) return;
        prefs().edit().putLong(KEY_LAST_CHECK, now).apply();
        check(false);
    }

    void check(boolean manual) {
        if (base.isEmpty()) {
            if (manual) toast(act.getString(R.string.update_off));
            return;
        }
        if (busy) return;
        busy = true;
        if (manual) toast(act.getString(R.string.update_checking));
        new Thread(() -> {
            long latest = -1;
            String notes = null;
            try {
                latest = Long.parseLong(fetchText(base + "version.txt").trim());
                if (latest > installedVersion()) notes = fetchText(base + "notes.txt").trim();
            } catch (Exception ignored) {
            }
            final long found = latest;
            final String whatsNew = notes;
            main.post(() -> {
                busy = false;
                onChecked(found, whatsNew, base, manual);
            });
        }, "update-check").start();
    }

    private void onChecked(long latest, String notes, String base, boolean manual) {
        if (act.isFinishing() || act.isDestroyed()) return;
        if (latest < 0) {
            if (manual) toast(act.getString(R.string.update_check_failed));
            return;
        }
        long mine = installedVersion();
        if (latest <= mine) {
            if (manual) toast(act.getString(R.string.update_none));
            return;
        }
        if (!manual && prefs().getLong(KEY_SKIPPED, -1) == latest) return;
        String message = act.getString(R.string.update_message, String.valueOf(mine), String.valueOf(latest));
        if (notes != null && !notes.isEmpty()) {
            message = act.getString(R.string.update_whats_new, notes) + "\n\n" + message;
        }
        new AlertDialog.Builder(act, DIALOG_THEME)
                .setTitle(R.string.update_available)
                .setMessage(message)
                .setPositiveButton(R.string.update_install, (d, w) -> install(base))
                .setNegativeButton(R.string.update_later, (d, w) -> prefs().edit().putLong(KEY_SKIPPED, latest).apply())
                .show();
    }

    private void install(String base) {
        installFrom(base + "tvhome-update.apk", base + "tvhome.apk", act.getPackageName(),
                act.getString(R.string.update_downloading));
    }

    /**
     * Downloads an APK and hands it to Android's installer, which asks the person to confirm. Used for
     * TV Home's own updates and for the optional apps (AppPack). {@code fallbackUrl} is tried when the
     * first link fails; {@code packageName} is the app being installed, or null when not known.
     */
    void installFrom(String url, String fallbackUrl, String packageName, String title) {
        PackageManager pm = act.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            toast(act.getString(R.string.update_allow_installs));
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        if (busy) return;
        busy = true;
        AlertDialog progress = new AlertDialog.Builder(act, DIALOG_THEME)
                .setTitle(title)
                .setMessage("0%")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            PackageInstaller pi = pm.getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (packageName != null) params.setAppPackageName(packageName);
                id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    HttpURLConnection c;
                    try {
                        c = open(url);
                    } catch (IOException e) {
                        if (fallbackUrl == null) throw e;
                        c = open(fallbackUrl);
                    }
                    long total = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("app.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int n, shown = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != shown) {
                                    shown = pct;
                                    main.post(() -> progress.setMessage(pct + "%"));
                                }
                            }
                        }
                        session.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent cb = new Intent(act, InstallResult.class);
                    PendingIntent pending = PendingIntent.getBroadcast(act, id, cb,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pending.getIntentSender());
                    id = -1;
                }
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
                if (id >= 0) {
                    try {
                        pi.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
            }
            final String err = error;
            main.post(() -> {
                busy = false;
                try {
                    progress.dismiss();
                } catch (Exception ignored) {
                }
                if (err != null) toast(act.getString(R.string.update_failed, err));
            });
        }, "update-download").start();
    }

    private void toast(String text) {
        Toast.makeText(act, text, Toast.LENGTH_LONG).show();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 2048) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Result of a PackageInstaller session: shows Android's confirm screen, or the error. */
    public static class InstallResult extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(confirm);
                    } catch (Exception ignored) {
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, ctx.getString(R.string.update_failed,
                        msg != null ? msg : String.valueOf(status)), Toast.LENGTH_LONG).show();
            }
        }
    }
}
