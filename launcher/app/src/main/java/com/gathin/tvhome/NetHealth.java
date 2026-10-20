package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Is the internet actually working, and how well? Once a minute (while Home is open) this fetches a
 * tiny "no content" page and times it, so the network pill can show a dot: green when fine, amber when
 * slow or patchy, red when the box is connected but the internet isn't getting through. That tells you
 * the problem is the connection, not the app. Each check is a few hundred bytes.
 */
final class NetHealth {

    enum State { UNKNOWN, GOOD, SLOW, DOWN }

    interface Listener {
        void onState(State s);
    }

    private static final String[] PROBES = {
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
    };
    private static final long SLOW_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private int failures;
    private State state = State.UNKNOWN;

    State state() {
        return state;
    }

    void check(Listener l) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            long ms = -1;
            for (String url : PROBES) {
                ms = probe(url);
                if (ms >= 0) break;
            }
            final long took = ms;
            main.post(() -> {
                busy = false;
                if (took < 0) failures++;
                else failures = 0;
                // One failed check makes it amber; two in a row, red.
                State s = took < 0 ? (failures >= 2 ? State.DOWN : State.SLOW)
                        : took > SLOW_MS ? State.SLOW : State.GOOD;
                state = s;
                l.onState(s);
            });
        }, "net-health").start();
    }

    private static long probe(String url) {
        long t = SystemClock.elapsedRealtime();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) return SystemClock.elapsedRealtime() - t;
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    static int color(State s) {
        switch (s) {
            case GOOD: return 0xFF34C759;
            case SLOW: return 0xFFFFB020;
            case DOWN: return 0xFFFF453A;
            default: return 0;
        }
    }

    /** The coloured dot drawn after the network name in the pill. */
    static final class Dot extends ReplacementSpan {
        private final int color;
        private final float density;

        Dot(int color, float density) {
            this.color = color;
            this.density = density;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(14 * density);
        }

        @Override
        public void draw(@NonNull Canvas c, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, @NonNull Paint paint) {
            int saved = paint.getColor();
            paint.setColor(color);
            paint.setAntiAlias(true);
            float r = 4 * density;
            c.drawCircle(x + 7 * density, (top + bottom) / 2f, r, paint);
            paint.setColor(saved);
        }
    }
}
