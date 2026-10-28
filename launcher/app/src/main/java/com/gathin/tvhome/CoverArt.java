package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * The song's cover from a music app's session. Apps hand it over in one of two ways: as a picture, or
 * as a link to it (SoundCloud and many others send only a link). Pictures are used straight away;
 * links are fetched once in the background, kept for the last few songs, and the card is redrawn when
 * the picture arrives. The app's own icon in the "display icon" slot is only the last resort, since
 * some apps put their logo there.
 */
final class CoverArt {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8);
    private static final Set<String> LOADING = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_BYTES = 6 * 1024 * 1024, MAX_SIDE = 640;

    private CoverArt() {
    }

    /** The cover now, or null (while a linked one loads, then {@code loaded} runs on the main thread). */
    static Bitmap of(Context c, MediaMetadata md, Runnable loaded) {
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b != null) return b;
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI}) {
            Bitmap linked = linked(c, md.getString(key), loaded);
            if (linked != null) return linked;
        }
        b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null) return b;
        return linked(c, md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI), loaded);
    }

    private static Bitmap linked(Context c, String uri, Runnable loaded) {
        if (TextUtils.isEmpty(uri)) return null;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(uri);
            if (hit != null || LOADING.contains(uri)) return hit;
            LOADING.add(uri);
        }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Bitmap b = fetch(app, uri);
            synchronized (CACHE) {
                LOADING.remove(uri);
                if (b != null) CACHE.put(uri, b);
            }
            if (b != null && loaded != null) MAIN.post(loaded);
        }, "cover-art").start();
        return null;
    }

    /** The picture behind a link: on the web, or shared by the app (content://), made screen-friendly. */
    private static Bitmap fetch(Context c, String uri) {
        try {
            byte[] data;
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                con.setInstanceFollowRedirects(true);
                try (InputStream in = con.getInputStream()) {
                    data = read(in);
                } finally {
                    con.disconnect();
                }
            } else {
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                    if (in == null) return null;
                    data = read(in);
                }
            }
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= MAX_SIDE && o.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;   // no picture: the app's icon stays
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;   // not a cover
        }
        return out.toByteArray();
    }
}
