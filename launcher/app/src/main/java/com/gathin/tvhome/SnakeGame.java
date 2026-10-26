package com.gathin.tvhome;

import java.util.ArrayDeque;
import java.util.Random;

/** Snake's rules on a w×h grid: the head moves one square per step; eat to grow; hitting yourself ends it. */
final class SnakeGame {

    static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;

    final int w, h;
    /** Body squares as y * w + x, head first. */
    final ArrayDeque<Integer> body = new ArrayDeque<>();
    int dir = RIGHT, food = -1, score;
    private int nextDir = RIGHT;
    boolean dead;
    private final Random rnd;

    SnakeGame(int w, int h, Random rnd) {
        this.w = w;
        this.h = h;
        this.rnd = rnd;
        int y = h / 2;
        for (int x = 4; x >= 2; x--) body.addLast(y * w + x);
        placeFood();
    }

    /** Turns at the next step; a U-turn into yourself is ignored. */
    void turn(int d) {
        if ((d + 2) % 4 != dir) nextDir = d;
    }

    /** One step. The edges wrap round, so only your own tail can end the game. */
    void step() {
        if (dead) return;
        dir = nextDir;
        int head = body.peekFirst(), x = head % w, y = head / w;
        if (dir == UP) y = (y - 1 + h) % h;
        else if (dir == DOWN) y = (y + 1) % h;
        else if (dir == LEFT) x = (x - 1 + w) % w;
        else x = (x + 1) % w;
        int next = y * w + x;
        boolean eat = next == food;
        if (!eat) body.removeLast();   // the tail moves out of the way first
        if (body.contains(next)) {
            dead = true;
            if (!eat) body.addLast(body.peekLast());   // shape doesn't matter once dead
            return;
        }
        body.addFirst(next);
        if (eat) {
            score++;
            placeFood();
        }
    }

    private void placeFood() {
        if (body.size() >= w * h) {
            food = -1;
            return;
        }
        int f;
        do {
            f = rnd.nextInt(w * h);
        } while (body.contains(f));
        food = f;
    }
}
