package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

/**
 * Soft backgrounds for the full-screen pages: a picture shrunk to a few dozen pixels and stretched
 * back up with smoothing, which reads as a heavy blur and costs almost nothing (no live blur effect,
 * which stutters on TV boxes).
 */
final class Ambient {

    private Ambient() {
    }

    /** A picture (an app's banner or icon) as a tiny bitmap to stretch full screen. */
    static Bitmap tiny(Drawable d) {
        if (d == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(-4, -4, 36, 22);   // a little beyond the edges: no hard borders after stretching
            copy.draw(c);
            return Bitmap.createScaledBitmap(b, 96, 54, true);   // a second, smoothing step
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** What a view shows now, as a tiny bitmap (Home behind the profiles page). */
    static Bitmap tiny(View v) {
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0) return null;
        try {
            Bitmap b = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(48f / v.getWidth(), 27f / v.getHeight());
            v.draw(c);
            return Bitmap.createScaledBitmap(b, 144, 81, true);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Shows a tiny bitmap full screen, softly, fading from what was there. */
    static void show(ImageView into, Bitmap tiny) {
        if (tiny == null) return;
        BitmapDrawable d = new BitmapDrawable(into.getResources(), tiny);
        d.setFilterBitmap(true);
        into.animate().cancel();
        into.animate().alpha(0.35f).setDuration(120).withEndAction(() -> {
            into.setImageDrawable(d);
            into.animate().alpha(1f).setDuration(260).start();
        }).start();
    }
}
