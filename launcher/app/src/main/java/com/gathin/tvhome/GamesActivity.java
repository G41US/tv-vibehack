package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Games (a tile in the app rows): Snake and 2048, played with the remote's arrows. OK starts and
 * restarts; Back goes from a game to the list, and from the list to Home. Best scores are kept.
 */
public class GamesActivity extends Activity {

    private static final String PREFS = "games";
    private FrameLayout root;
    private View game;
    private Dialog menu;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(TextScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Sounds.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070A15);
        setContentView(root);
        showMenu();
    }

    private void showMenu() {
        List<GlassMenu.Row> rows = new ArrayList<>();
        rows.add(new GlassMenu.Row(R.drawable.ic_s_play, getString(R.string.games_snake), getString(R.string.games_snake_desc),
                () -> play(new SnakeView(this))));
        rows.add(new GlassMenu.Row(R.drawable.ic_m_apps, getString(R.string.games_2048), getString(R.string.games_2048_desc),
                () -> play(new Game2048View(this))));
        menu = GlassMenu.page(this, getString(R.string.menu_title), getString(R.string.games_title), null, rows, this::finish, 0);
    }

    private void play(View v) {
        game = v;
        v.setFocusable(true);
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (game != null) {
            root.removeView(game);
            game = null;
            showMenu();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (menu != null) menu.dismiss();
        super.onDestroy();
    }

    private int best(String key) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt(key, 0);
    }

    private void saveBest(String key, int score) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (score > p.getInt(key, 0)) p.edit().putInt(key, score).apply();
    }

    /** Shared look: navy background, score line at the top, a message in the middle. */
    private abstract class Board extends View {
        final float d = getResources().getDisplayMetrics().density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random rnd = new Random();

        Board(Context c) {
            super(c);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void background(Canvas c) {
            paint.setShader(new LinearGradient(0, 0, getWidth() * 0.4f, getHeight(), 0xFF1D2746, 0xFF070A15, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }

        void header(Canvas c, String title, int score, int best) {
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(30 * d);
            text.setColor(Color.WHITE);
            c.drawText(title, 48 * d, 64 * d, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(20 * d);
            text.setColor(0xCCFFFFFF);
            c.drawText(getString(R.string.games_score, score) + "     " + getString(R.string.games_best, best),
                    getWidth() - 48 * d, 60 * d, text);
        }

        void banner(Canvas c, String msg) {
            float w = getWidth(), h = getHeight();
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(24 * d);
            float tw = text.measureText(msg);
            paint.setColor(0xD9101830);
            RectF r = new RectF(w / 2 - tw / 2 - 28 * d, h / 2 - 34 * d, w / 2 + tw / 2 + 28 * d, h / 2 + 26 * d);
            c.drawRoundRect(r, 22 * d, 22 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(0x66FFFFFF);
            c.drawRoundRect(r, 22 * d, 22 * d, paint);
            paint.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE);
            c.drawText(msg, w / 2, h / 2 + 4 * d, text);
        }

        boolean ok(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }
    }

    // ---- Snake ----------------------------------------------------------------------------------

    private final class SnakeView extends Board {
        private static final int W = 32, H = 16;
        private SnakeGame g;
        private boolean playing;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                g.step();
                if (g.dead) {
                    playing = false;
                    saveBest("snake", g.score);
                } else {
                    postDelayed(this, Math.max(70, 150 - g.score * 3));   // faster as it grows
                }
                invalidate();
            }
        };

        SnakeView(Context c) {
            super(c);
            g = new SnakeGame(W, H, rnd);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (ok(code)) {
                if (g.dead) g = new SnakeGame(W, H, rnd);
                if (!playing) {
                    playing = true;
                    post(tick);
                }
                invalidate();
                return true;
            }
            int dir = code == KeyEvent.KEYCODE_DPAD_UP ? SnakeGame.UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? SnakeGame.DOWN
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? SnakeGame.LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? SnakeGame.RIGHT : -1;
            if (dir < 0) return super.onKeyDown(code, e);
            g.turn(dir);
            if (!playing && !g.dead) {   // an arrow starts it too
                playing = true;
                post(tick);
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            background(c);
            header(c, getString(R.string.games_snake), g.score, Math.max(best("snake"), g.score));
            float top = 96 * d, side = 48 * d;
            float cell = Math.min((getWidth() - 2 * side) / W, (getHeight() - top - 40 * d) / H);
            float ox = (getWidth() - cell * W) / 2, oy = top + (getHeight() - top - 40 * d - cell * H) / 2;
            paint.setColor(0x14FFFFFF);
            c.drawRoundRect(new RectF(ox, oy, ox + cell * W, oy + cell * H), 18 * d, 18 * d, paint);
            if (g.food >= 0) {
                paint.setColor(0xFFFF5A5F);
                c.drawCircle(ox + (g.food % W + 0.5f) * cell, oy + (g.food / W + 0.5f) * cell, cell * 0.36f, paint);
            }
            int i = 0, n = g.body.size();
            for (int sq : g.body) {
                float k = 1f - 0.45f * i / Math.max(1, n - 1);   // head brightest
                paint.setColor(Color.argb(255, (int) (60 * k), (int) (230 * k), (int) (170 * k)));
                float x = ox + (sq % W) * cell, y = oy + (sq / W) * cell, pad = cell * 0.08f;
                c.drawRoundRect(new RectF(x + pad, y + pad, x + cell - pad, y + cell - pad), cell * 0.3f, cell * 0.3f, paint);
                i++;
            }
            if (g.dead) banner(c, getString(R.string.games_over));
            else if (!playing) banner(c, getString(R.string.games_start));
        }

        @Override
        protected void onDetachedFromWindow() {
            playing = false;
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }
    }

    // ---- 2048 -----------------------------------------------------------------------------------

    private final class Game2048View extends Board {
        private int[] b = new int[16];
        private int score;

        Game2048View(Context c) {
            super(c);
            restart();
        }

        private void restart() {
            b = new int[16];
            score = 0;
            Game2048.addTile(b, rnd);
            Game2048.addTile(b, rnd);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (ok(code)) {
                if (Game2048.over(b)) restart();
                invalidate();
                return true;
            }
            int dir = code == KeyEvent.KEYCODE_DPAD_UP ? Game2048.UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? Game2048.DOWN
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? Game2048.LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? Game2048.RIGHT : -1;
            if (dir < 0) return super.onKeyDown(code, e);
            if (Game2048.over(b)) return true;
            int pts = Game2048.move(b, dir);
            if (pts >= 0) {
                score += pts;
                Game2048.addTile(b, rnd);
                if (Game2048.over(b)) saveBest("2048", score);
                invalidate();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            background(c);
            header(c, getString(R.string.games_2048), score, Math.max(best("2048"), score));
            float top = 96 * d;
            float size = Math.min(getWidth() * 0.5f, getHeight() - top - 48 * d);
            float ox = (getWidth() - size) / 2, oy = top + (getHeight() - top - 48 * d - size) / 2;
            paint.setColor(0x1FFFFFFF);
            c.drawRoundRect(new RectF(ox, oy, ox + size, oy + size), 20 * d, 20 * d, paint);
            float gap = size * 0.03f, cell = (size - gap * 5) / 4;
            text.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 16; i++) {
                float x = ox + gap + (i % 4) * (cell + gap), y = oy + gap + (i / 4) * (cell + gap);
                paint.setColor(colour(b[i]));
                c.drawRoundRect(new RectF(x, y, x + cell, y + cell), 12 * d, 12 * d, paint);
                if (b[i] == 0) continue;
                String s = String.valueOf(b[i]);
                text.setColor(b[i] <= 4 ? 0xFF2B2F45 : Color.WHITE);
                text.setTextSize(cell * (s.length() <= 2 ? 0.42f : s.length() == 3 ? 0.34f : 0.27f));
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(s, x + cell / 2, y + cell / 2 - (fm.ascent + fm.descent) / 2, text);
            }
            if (Game2048.over(b)) banner(c, getString(R.string.games_over));
        }

        private int colour(int v) {
            switch (v) {
                case 0: return 0x14FFFFFF;
                case 2: return 0xFFEDEFF7;
                case 4: return 0xFFD9DEF2;
                case 8: return 0xFF7AA2FF;
                case 16: return 0xFF5B7CFA;
                case 32: return 0xFF7B5CFA;
                case 64: return 0xFFA14CF0;
                case 128: return 0xFFE0559E;
                case 256: return 0xFFF06B5B;
                case 512: return 0xFFF59A3B;
                case 1024: return 0xFFF4C542;
                default: return 0xFF2ECC9A;   // 2048 and up
            }
        }
    }
}
