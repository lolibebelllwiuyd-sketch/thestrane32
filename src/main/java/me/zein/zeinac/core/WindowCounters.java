package me.zein.zeinac.core;

import java.util.Arrays;

/**
 * Rolling per-minute counters for the last N minutes. Memory is fixed: (windowMinutes + 2) x groups x fields ints.
 * Old minutes are lazily recycled, so there is no cleanup task.
 */
public final class WindowCounters {
    private final int fields;
    private final int windowMinutes;
    private final int size;
    private final long[] minute;
    private final int[][] data;

    public WindowCounters(int groups, int fields, int windowMinutes) {
        this.fields = fields;
        this.windowMinutes = Math.max(1, windowMinutes);
        this.size = this.windowMinutes + 2;
        this.minute = new long[size];
        Arrays.fill(minute, -1L);
        this.data = new int[size][Math.max(1, groups) * fields];
    }

    private int slot(long nowMs) {
        long m = nowMs / 60000L;
        int s = (int) (m % size);
        if (minute[s] != m) {
            minute[s] = m;
            Arrays.fill(data[s], 0);
        }
        return s;
    }

    public void add(long nowMs, int group, int field, int amount) {
        data[slot(nowMs)][group * fields + field] += amount;
    }

    public int sum(long nowMs, int group, int field) {
        long m = nowMs / 60000L;
        int total = 0;
        for (int s = 0; s < size; s++) {
            if (minute[s] > m - windowMinutes && minute[s] <= m) {
                total += data[s][group * fields + field];
            }
        }
        return total;
    }
}
