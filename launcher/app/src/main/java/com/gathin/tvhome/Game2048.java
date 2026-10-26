package com.gathin.tvhome;

import java.util.Random;

/** 2048's rules on a 4×4 board (0 = empty), kept free of Android so they can be checked alone. */
final class Game2048 {

    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    private Game2048() {
    }

    /** Slides and merges the board in place. Returns the points won, or -1 if nothing moved. */
    static int move(int[] b, int dir) {
        boolean moved = false;
        int points = 0;
        for (int line = 0; line < 4; line++) {
            int[] idx = new int[4];
            for (int k = 0; k < 4; k++) {
                // k = 0 is the edge the tiles slide towards
                switch (dir) {
                    case LEFT: idx[k] = line * 4 + k; break;
                    case RIGHT: idx[k] = line * 4 + 3 - k; break;
                    case UP: idx[k] = k * 4 + line; break;
                    default: idx[k] = (3 - k) * 4 + line; break;
                }
            }
            int[] out = new int[4];
            int n = 0;
            boolean mergedLast = false;
            for (int k = 0; k < 4; k++) {
                int v = b[idx[k]];
                if (v == 0) continue;
                if (n > 0 && out[n - 1] == v && !mergedLast) {
                    out[n - 1] = v * 2;
                    points += v * 2;
                    mergedLast = true;
                } else {
                    out[n++] = v;
                    mergedLast = false;
                }
            }
            for (int k = 0; k < 4; k++) {
                if (b[idx[k]] != out[k]) moved = true;
                b[idx[k]] = out[k];
            }
        }
        return moved ? points : -1;
    }

    /** Puts a 2 (or now and then a 4) on a random empty square. */
    static void addTile(int[] b, Random rnd) {
        int empty = 0;
        for (int v : b) if (v == 0) empty++;
        if (empty == 0) return;
        int pick = rnd.nextInt(empty);
        for (int i = 0; i < 16; i++) {
            if (b[i] == 0 && pick-- == 0) {
                b[i] = rnd.nextInt(10) == 0 ? 4 : 2;
                return;
            }
        }
    }

    /** True when no move is possible. */
    static boolean over(int[] b) {
        for (int i = 0; i < 16; i++) {
            if (b[i] == 0) return false;
            if (i % 4 < 3 && b[i] == b[i + 1]) return false;
            if (i < 12 && b[i] == b[i + 4]) return false;
        }
        return true;
    }
}
