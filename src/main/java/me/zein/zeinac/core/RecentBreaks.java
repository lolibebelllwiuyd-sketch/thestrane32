package me.zein.zeinac.core;

/** Bounded ring of blocks a player recently broke. Used to tell "revealed by my own digging" from "was already visible". */
public final class RecentBreaks {
    private final long[] keys;
    private final long[] times;
    private int head = -1;
    private int size = 0;

    public RecentBreaks(int capacity) {
        int cap = Math.max(4, capacity);
        this.keys = new long[cap];
        this.times = new long[cap];
    }

    public void add(int x, int y, int z, long nowMs) {
        head = (head + 1) % keys.length;
        keys[head] = BlockKey.pack(x, y, z);
        times[head] = nowMs;
        if (size < keys.length) {
            size++;
        }
    }

    public boolean contains(int x, int y, int z, long nowMs, long maxAgeMs) {
        long k = BlockKey.pack(x, y, z);
        for (int i = 0; i < size; i++) {
            int idx = ((head - i) % keys.length + keys.length) % keys.length;
            if (nowMs - times[idx] > maxAgeMs) {
                return false; // older entries are even older
            }
            if (keys[idx] == k) {
                return true;
            }
        }
        return false;
    }
}
