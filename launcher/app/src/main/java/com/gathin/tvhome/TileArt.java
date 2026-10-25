package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * "Dark tile" art for an app whose own banner doesn't sit well on the dark Home, such as a white or
 * light-grey one. Only for apps the person picked (hold OK on the tile), plus SportzX by default.
 *
 * The app gets a tile in the house style instead: a deep gradient in the app's own colour with its
 * logo lifted off the light background and centred. The logo comes from the banner (its background
 * colour is keyed out, edges un-blended so there's no pale fringe, and black lettering turns white), or,
 * failing that, from the foreground layer of the app's icon.
 */
final class TileArt {

    /** Output size; the tile's ImageView scales it to fit (same 16:9 shape). */
    static final int W = 480, H = 270;

    private TileArt() {
    }

    /**
     * Art for one app, or null to show its banner as it is.
     *
     * @param roomForLabel leave space under the logo for the name the grid prints on banner-less tiles
     */
    static Bitmap make(Drawable banner, Drawable icon, int ambient, boolean roomForLabel) {
        int[] logo = null;
        int lw = 0, lh = 0;
        boolean fromBanner = false;

        if (banner != null) {
            int[] px = render(banner, W, H);
            int[] keyed = keyOut(px, W, H);
            if (keyed != null) {
                int[] box = trim(keyed, W, H);
                if (box != null) {
                    logo = crop(keyed, W, box);
                    lw = box[2] - box[0];
                    lh = box[3] - box[1];
                    fromBanner = true;
                }
            }
        }
        if (logo == null && icon != null) {
            int[][] mark = iconMark(icon);
            if (mark != null) {
                logo = mark[0];
                lw = mark[1][0];
                lh = mark[1][1];
            }
        }
        if (logo == null) return null;

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        paintBackground(c, ambient);

        // Fit: a wordmark is limited by width, a square mark by height.
        float maxW = W * (fromBanner ? 0.70f : 0.50f);
        float maxH = H * (roomForLabel ? 0.42f : (fromBanner ? 0.56f : 0.52f));
        float k = Math.min(maxW / lw, maxH / lh);
        k = Math.min(k, fromBanner ? 2.4f : 3f);
        float dw = lw * k, dh = lh * k;
        float cx = W / 2f, cy = H / 2f - (roomForLabel ? H * 0.09f : 0f);
        Bitmap lb = Bitmap.createBitmap(logo, lw, lh, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(lb, null, new RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), p);
        lb.recycle();
        return out;
    }

    /** The icon's logo, trimmed: {pixels, {width, height}}, or null. */
    private static int[][] iconMark(Drawable icon) {
        int s = 216;
        int[] px = null;
        if (icon instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
            if (fg != null) {
                px = render(fg, s, s);
                float cover = coverage(px);
                if (cover < 0.01f || cover > 0.85f) px = null;   // empty, or a full-bleed picture
            }
        }
        if (px == null) px = render(icon, s, s);
        int[] box = trim(px, s, s);
        if (box == null) return null;
        return new int[][]{crop(px, s, box), {box[2] - box[0], box[3] - box[1]}};
    }

    /**
     * A banner like Spotify's for a dock app that has none, or a dark tile: near-black, the app's logo
     * and its name spelt out beside it in the app's colour. Null if the icon has nothing to draw.
     */
    static Bitmap wordmark(Drawable icon, String name, int ambient) {
        int[][] mark = icon != null ? iconMark(icon) : null;
        if (mark == null) return null;
        int lw = mark[1][0], lh = mark[1][1];

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float[] hsv = new float[3];
        Color.colorToHSV(ambient, hsv);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, W, H, Color.HSVToColor(new float[]{hsv[0], Math.min(hsv[1], 0.5f), 0.13f}),
                0xFF0B0B0D, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, bg);

        // The name in the app's own colour, like Spotify's green; white when the colour is too dull to read.
        int ink = hsv[1] < 0.3f ? 0xFFF5F5F7 : Color.HSVToColor(new float[]{hsv[0], Math.min(hsv[1], 0.85f), Math.max(hsv[2], 0.92f)});
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        text.setColor(ink);
        text.setTextSize(H * 0.25f);
        text.setLetterSpacing(-0.02f);

        float iconH = H * 0.36f, iconW = iconH * lw / lh;
        float gap = H * 0.06f;
        float textW = text.measureText(name);
        float total = iconW + gap + textW, room = W * 0.86f;
        if (total > room) {   // long name: shrink the text, then everything, to fit
            text.setTextSize(text.getTextSize() * Math.max(0.6f, (room - iconW - gap) / textW));
            textW = text.measureText(name);
            total = iconW + gap + textW;
            if (total > room) {
                float k = room / total;
                iconH *= k;
                iconW *= k;
                gap *= k;
                text.setTextSize(text.getTextSize() * k);
                textW = text.measureText(name);
                total = iconW + gap + textW;
            }
        }
        float x = (W - total) / 2f, cy = H / 2f;
        Bitmap lb = Bitmap.createBitmap(mark[0], lw, lh, Bitmap.Config.ARGB_8888);
        c.drawBitmap(lb, null, new RectF(x, cy - iconH / 2, x + iconW, cy + iconH / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        lb.recycle();
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(name, x + iconW + gap, cy - (fm.ascent + fm.descent) / 2, text);
        return out;
    }

    /** Deep gradient in the app's colour, a soft glow behind the logo and a faint top sheen. */
    private static void paintBackground(Canvas c, int ambient) {
        float[] hsv = new float[3];
        Color.colorToHSV(ambient, hsv);
        float s = Math.min(hsv[1], 0.65f);
        int top = Color.HSVToColor(new float[]{hsv[0], s, 0.27f});
        int bottom = Color.HSVToColor(new float[]{hsv[0], Math.min(1f, s + 0.05f), 0.09f});
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, W * 0.35f, H, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        int glow = Color.HSVToColor(new float[]{hsv[0], s, 0.85f});
        p.setShader(new RadialGradient(W / 2f, H / 2f, W * 0.42f,
                (glow & 0x00FFFFFF) | 0x48000000, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        p.setShader(new LinearGradient(0, 0, 0, H * 0.5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.5f, p);
    }

    private static int[] render(Drawable live, int w, int h) {
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        d.setBounds(saved);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    // ---- pixel work (plain ARGB arrays) ---------------------------------------------------------

    private static float lum(int p) {
        return (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
    }

    /** Most of the visible banner is light (white, pale grey, cream). */
    static boolean isLight(int[] px) {
        int seen = 0, light = 0;
        for (int i = 0; i < px.length; i += 3) {
            int p = px[i];
            if ((p >>> 24) < 128) continue;
            seen++;
            if (lum(p) > 0.70f) light++;
        }
        return seen > 0 && light > seen * 0.45f;
    }

    static float coverage(int[] px) {
        long a = 0;
        for (int p : px) a += p >>> 24;
        return a / (255f * px.length);
    }

    /**
     * The banner with its background colour (taken from the border) made transparent. Partly covered
     * edge pixels are un-blended from the background, and dark neutral lettering becomes white so it
     * reads on the dark tile. Null when the background isn't one even colour or nothing is left.
     */
    static int[] keyOut(int[] px, int w, int h) {
        int ring = Math.max(2, Math.round(h * 0.04f));
        double sr = 0, sg = 0, sb = 0, n = 0;
        double qr = 0, qg = 0, qb = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= ring && x < w - ring && y >= ring && y < h - ring) continue;
                int p = px[y * w + x];
                if ((p >>> 24) < 200) continue;   // the banner's own rounded corners
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                sr += r; sg += g; sb += b;
                qr += r * r; qg += g * g; qb += b * b;
                n++;
            }
        }
        if (n < 20) return null;
        double br = sr / n, bg = sg / n, bb = sb / n;
        double var = (qr / n - br * br) + (qg / n - bg * bg) + (qb / n - bb * bb);
        if (Math.sqrt(Math.max(0, var)) > 40) return null;   // a picture, not a plain background

        int[] out = new int[px.length];
        long kept = 0;
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a0 = p >>> 24;
            if (a0 == 0) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            double d = Math.sqrt((r - br) * (r - br) + (g - bg) * (g - bg) + (b - bb) * (b - bb)) / 441.7;
            double a = Math.max(0, Math.min(1, (d - 0.06) / 0.16));
            if (a <= 0.01) continue;
            if (a < 1) {   // un-blend: the pixel was logo mixed with background
                r = clamp((r - (1 - a) * br) / a);
                g = clamp((g - (1 - a) * bg) / a);
                b = clamp((b - (1 - a) * bb) / a);
            }
            Color.RGBToHSV(r, g, b, hsv);
            if (hsv[2] < 0.5f) {
                if (hsv[1] < 0.25f) {        // black or grey lettering → white
                    hsv[1] = 0f;
                    hsv[2] = 0.96f;
                } else {                     // dark colour → the same colour, bright enough to read
                    hsv[2] = 0.82f;
                }
            }
            int alpha = (int) Math.round(a * a0);
            out[i] = Color.HSVToColor(alpha, hsv);
            kept += alpha;
        }
        float cover = kept / (255f * px.length);
        if (cover < 0.008f || cover > 0.6f) return null;
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Bounding box {left, top, right, bottom} of what's visible, or null if nothing is. */
    static int[] trim(int[] px, int w, int h) {
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        if (r < l || b < t) return null;
        return new int[]{l, t, r + 1, b + 1};
    }

    static int[] crop(int[] px, int w, int[] box) {
        int cw = box[2] - box[0], ch = box[3] - box[1];
        int[] out = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(px, (box[1] + y) * w + box[0], out, y * cw, cw);
        return out;
    }
}
