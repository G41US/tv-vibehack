package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Outfit at a given weight, made once and reused. Setting the weight on every TextView made Android
 * build a new font each time (2.6 s for the music card alone while the box was starting, and a
 * stutter on every menu); now each weight is built once.
 */
final class Fonts {

    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {
    }

    static synchronized Typeface weight(Context c, int w) {
        Typeface t = CACHE.get(w);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(file(c)).setFontVariationSettings("'wght' " + w).setWeight(w).build();
        } catch (Exception e) {
            t = null;
        }
        if (t == null) {
            try {
                t = c.getResources().getFont(R.font.outfit);
            } catch (Exception e) {
                t = Typeface.DEFAULT;
            }
        }
        CACHE.put(w, t);
        return t;
    }

    /** The font as a file (Typeface.Builder can't take a font resource with a weight). */
    private static File file(Context c) throws Exception {
        File f = new File(c.getCodeCacheDir(), "outfit.ttf")   // emptied by Android on every update;
        if (f.length() > 0) return f;
        File tmp = new File(f.getPath() + ".tmp");
        try (InputStream in = c.getResources().openRawResource(R.font.outfit); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
        return f;
    }
}
