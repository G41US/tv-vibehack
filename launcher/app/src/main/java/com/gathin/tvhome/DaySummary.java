package com.gathin.tvhome;

/**
 * The one line on top of a day's hourly forecast: when it rains (or storms), else the best chance of
 * a shower, else dry; and the hottest hour. Plain Java with no Android parts, so it can be checked alone.
 */
final class DaySummary {

    static final int DRY = 0, CHANCE = 1, RAIN = 2, STORMS = 3;

    private DaySummary() {
    }

    /**
     * @return {kind, first wet hour, last wet hour, hottest hour} as indexes into the arrays; for
     *     CHANCE the first and last are the likeliest hour, for DRY they're -1
     */
    static int[] of(int[] rain, int[] code, int[] temp) {
        int n = rain.length, start = -1, end = -1;
        for (int i = 0; i < n; i++) {
            if (wet(rain[i], code[i])) {
                start = i;
                end = i;
                while (end + 1 < n && wet(rain[end + 1], code[end + 1])) end++;
                break;
            }
        }
        int kind = DRY;
        if (start >= 0) {
            kind = RAIN;
            for (int i = start; i <= end; i++) if (code[i] >= 95) kind = STORMS;
        } else {
            int m = 0;
            for (int i = 1; i < n; i++) if (rain[i] > rain[m]) m = i;
            if (n > 0 && rain[m] >= 20) {
                kind = CHANCE;
                start = end = m;
            }
        }
        int hot = n > 0 ? 0 : -1;
        for (int i = 1; i < n; i++) if (temp[i] > temp[hot]) hot = i;
        return new int[]{kind, start, end, hot};
    }

    private static boolean wet(int rain, int code) {
        return rain >= 40 || (code >= 51 && rain >= 25);
    }
}
