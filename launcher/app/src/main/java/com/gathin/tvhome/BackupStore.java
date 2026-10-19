package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keeps TV Home's settings (dock, wallpaper choice and photo, weather city, guest Wi-Fi) safe across an uninstall. App storage is wiped on uninstall, so the backup lives in shared
 * storage as pictures in Pictures/TVHome: the settings ride inside a PNG's text chunk, encrypted with
 * AES-GCM under a key derived from ANDROID_ID. ANDROID_ID is per signing key and device, so only
 * TV Home on this same box can read it back, and it survives reinstalls (not a factory reset).
 *
 * Writing needs no permission (Android 10+). Reading a backup made by an earlier install needs the
 * photos permission, asked for on the setup screen.
 */
final class BackupStore {

    private static final String FOLDER = Environment.DIRECTORY_PICTURES + "/TVHome/";
    private static final String BACKUP = "tvhome-backup", WALL = "tvhome-wallpaper";
    private static final String PREFS = "backup", KEY_HASH = "hash", KEY_URI = "uri", KEY_WALL_URI = "wall_uri",
            KEY_WALL_STAMP = "wall_stamp", KEY_CHECKED = "restore_checked";
    private static final String CHUNK_KEYWORD = "tvhome";

    /** Settings files and the keys kept from each. */
    private static final String[][] KEPT = {
            {AppAdapter.PREFS, AppAdapter.KEY_ORDER},
            {"look", "wallpaper"},
            {"weather", "place"},
            {"guest_wifi", "ssid", "password", "security", "hidden"},
    };

    private BackupStore() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    static String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean canRead(Context ctx) {
        return ctx.checkSelfPermission(readPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean restoreChecked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CHECKED, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- saving ------------------------------------------------------------------------------------

    static void saveAsync(Context ctx) {
        if (!supported()) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                save(app);
            } catch (Exception ignored) {
                // A backup must never disturb the home screen.
            }
        }, "backup").start();
    }

    private static synchronized void save(Context ctx) throws Exception {
        SharedPreferences b = prefs(ctx);
        String json = snapshot(ctx).toString();
        String hash = sha256Hex(json.getBytes(StandardCharsets.UTF_8));
        if (!hash.equals(b.getString(KEY_HASH, ""))) {
            byte[] png = withTextChunk(card(ctx), CHUNK_KEYWORD, encrypt(ctx, json));
            Uri uri = write(ctx, b.getString(KEY_URI, null), BACKUP + ".png", "image/png", png);
            if (uri != null) b.edit().putString(KEY_URI, uri.toString()).putString(KEY_HASH, hash).apply();
        }
        File wall = new File(ctx.getFilesDir(), "custom_wallpaper.jpg");
        if (wall.isFile()) {
            String stamp = wall.length() + ":" + wall.lastModified();
            if (!stamp.equals(b.getString(KEY_WALL_STAMP, ""))) {
                Uri uri = write(ctx, b.getString(KEY_WALL_URI, null), WALL + ".jpg", "image/jpeg", readAll(new FileInputStream(wall)));
                if (uri != null) b.edit().putString(KEY_WALL_URI, uri.toString()).putString(KEY_WALL_STAMP, stamp).apply();
            }
        }
    }

    private static JSONObject snapshot(Context ctx) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", 1);
        for (String[] kept : KEPT) {
            Map<String, ?> all = ctx.getSharedPreferences(kept[0], Context.MODE_PRIVATE).getAll();
            JSONObject o = new JSONObject();
            for (int i = 1; i < kept.length; i++) {
                Object v = all.get(kept[i]);
                if (v instanceof String || v instanceof Boolean || v instanceof Number) o.put(kept[i], v);
            }
            root.put(kept[0], o);
        }
        return root;
    }

    /** Overwrites our own earlier file, or adds a new one to Pictures/TVHome. */
    private static Uri write(Context ctx, String own, String name, String mime, byte[] data) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        if (own != null) {
            try (OutputStream out = cr.openOutputStream(Uri.parse(own), "wt")) {
                if (out != null) {
                    out.write(data);
                    return Uri.parse(own);
                }
            } catch (Exception gone) {
                // Deleted or from an earlier install: add a new one.
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri, "w")) {
            if (out == null) return null;
            out.write(data);
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    /** The picture a person sees if they open the backup in a gallery. */
    private static Bitmap card(Context ctx) {
        Bitmap bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF0B0D14);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        try {
            p.setTypeface(ctx.getResources().getFont(R.font.outfit));
        } catch (Exception ignored) {
        }
        p.setColor(0xFFFFFFFF);
        p.setTextSize(40);
        c.drawText(ctx.getString(R.string.backup_card_title), 40, 170, p);
        p.setColor(0x99FFFFFF);
        p.setTextSize(22);
        c.drawText(ctx.getString(R.string.backup_card_body), 40, 215, p);
        return bmp;
    }

    // ---- restoring ---------------------------------------------------------------------------------

    /**
     * Restores the newest backup an earlier install left on this box. Runs once per install (and only
     * with the photos permission). Returns true when settings were restored.
     */
    static synchronized boolean restore(Context ctx) {
        if (!supported() || !canRead(ctx) || restoreChecked(ctx)) return false;
        boolean restored = false;
        try {
            String own = prefs(ctx).getString(KEY_URI, null);
            for (Uri uri : newestFirst(ctx, BACKUP)) {
                if (uri.toString().equals(own)) continue;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    String text = textChunk(readAll(in), CHUNK_KEYWORD);
                    if (text == null) continue;
                    apply(ctx, new JSONObject(decrypt(ctx, text)));
                    restored = true;
                    break;
                } catch (Exception notOurs) {
                    // Another box's backup, or damaged: try the next one.
                }
            }
            String ownWall = prefs(ctx).getString(KEY_WALL_URI, null);
            File wall = new File(ctx.getFilesDir(), "custom_wallpaper.jpg");
            if (restored && !wall.isFile()) {
                for (Uri uri : newestFirst(ctx, WALL)) {
                    if (uri.toString().equals(ownWall)) continue;
                    try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(wall)) {
                        out.write(readAll(in));
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putBoolean(KEY_CHECKED, true).remove(KEY_HASH).apply();
        return restored;
    }

    private static java.util.List<Uri> newestFirst(Context ctx, String prefix) {
        java.util.List<Uri> out = new java.util.ArrayList<>();
        Uri coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        try (Cursor c = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{prefix + "%"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            while (c != null && c.moveToNext()) out.add(ContentUris.withAppendedId(coll, c.getLong(0)));
        }
        return out;
    }

    private static void apply(Context ctx, JSONObject root) throws Exception {
        for (String[] kept : KEPT) {
            JSONObject o = root.optJSONObject(kept[0]);
            if (o == null) continue;
            SharedPreferences.Editor e = ctx.getSharedPreferences(kept[0], Context.MODE_PRIVATE).edit();
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = o.get(k);
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else e.putString(k, String.valueOf(v));
            }
            e.commit();
        }
    }

    // ---- crypto ------------------------------------------------------------------------------------

    private static SecretKeySpec key(Context ctx) throws Exception {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        byte[] k = MessageDigest.getInstance("SHA-256")
                .digest(("tvhome-backup-v1:" + id).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(k, "AES");
    }

    private static String encrypt(Context ctx, String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(ctx), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array(), Base64.NO_WRAP);
    }

    private static String decrypt(Context ctx, String text) throws Exception {
        byte[] all = Base64.decode(text, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(ctx), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    // ---- PNG text chunks ---------------------------------------------------------------------------

    private static byte[] withTextChunk(Bitmap bmp, String keyword, String text) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
        byte[] in = png.toByteArray();
        byte[] data = (keyword + "\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        int iend = in.length - 12;   // the IEND chunk is always the last 12 bytes
        ByteBuffer out = ByteBuffer.allocate(in.length + 12 + data.length);
        out.put(in, 0, iend).putInt(data.length).put(type).put(data).putInt((int) crc.getValue()).put(in, iend, 12);
        return out.array();
    }

    private static String textChunk(byte[] png, String keyword) {
        int i = 8;   // after the PNG signature
        while (i + 12 <= png.length) {
            int len = ByteBuffer.wrap(png, i, 4).getInt();
            String type = new String(png, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (len < 0 || i + 12 + len > png.length) return null;
            if (type.equals("tEXt")) {
                String s = new String(png, i + 8, len, StandardCharsets.ISO_8859_1);
                int z = s.indexOf('\0');
                if (z > 0 && s.substring(0, z).equals(keyword)) return s.substring(z + 1);
            }
            if (type.equals("IEND")) return null;
            i += 12 + len;
        }
        return null;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new java.io.IOException("too big");
            }
            return out.toByteArray();
        }
    }

    private static String sha256Hex(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }
}
