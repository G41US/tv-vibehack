package com.gathin.tvhome;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a direct APK link published on the public SportzX APK Telegram channel.
 * It intentionally fails closed: Telegram post pages and unrelated links are never passed
 * to PackageInstaller as though they were APK files.
 *
 * Note: Telegram does not guarantee a stable direct-attachment URL in its public HTML.
 * If its page does not expose a direct .apk URL, this resolver reports that limitation
 * rather than silently falling back to a stale third-party mirror.
 */
final class SportzXResolver {
    private static final String CHANNEL_URL = "https://t.me/s/dllsportzx";
    private static final Pattern APK_URL = Pattern.compile(
            "https?://[^\\s\\\"'<>]+?\\.apk(?:\\?[^\\s\\\"'<>]*)?",
            Pattern.CASE_INSENSITIVE);

    private SportzXResolver() {}

    static void installLatest(Activity activity, Updater installer) {
        Toast.makeText(activity, "Checking SportzX Telegram for the latest APK…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String apkUrl = null;
            String error = null;
            try {
                String html = fetch(CHANNEL_URL);
                Matcher matcher = APK_URL.matcher(html);
                while (matcher.find()) {
                    String candidate = matcher.group().replace("&amp;", "&");
                    String lower = candidate.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("sportzx") || lower.contains("dllsportzx")) {
                        apkUrl = candidate;
                    }
                }
                if (apkUrl == null) {
                    error = "Telegram did not expose a direct SportzX APK link. No file was installed. Check the channel's latest post or official download instructions.";
                }
            } catch (Exception e) {
                error = "Could not check the SportzX Telegram channel: " + e.getMessage();
            }
            final String resolved = apkUrl;
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) {
                    new android.app.AlertDialog.Builder(activity)
                            .setTitle("SportzX update unavailable")
                            .setMessage(failure)
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("SportzX APK found")
                        .setMessage("A direct APK link was found on the Telegram channel. Android will verify package compatibility during installation. Continue?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download and install", (dialog, which) ->
                                installer.installFrom(resolved, null, null, "Downloading latest SportzX"))
                        .show();
            });
        }, "sportzx-latest-resolver").start();
    }

    private static String fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1 && output.size() < 2_000_000) {
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}