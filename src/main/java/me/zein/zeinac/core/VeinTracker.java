package me.zein.zeinac.core;

/** Groups adjacent ore blocks into one "vein" so a 4-block diamond vein counts as one discovery. */
public final class VeinTracker {
    private final int[] group;
    private final int[] vx;
    private final int[] vy;
    private final int[] vz;
    private final long[] time;
    private int next = 0;
    private int size = 0;

    public VeinTracker(int capacity) {
        int cap = Math.max(2, capacity);
        group = new int[cap];
        vx = new int[cap];
        vy = new int[cap];
        vz = new int[cap];
        time = new long[cap];
    }

    /**
     * @return true if this block belongs to a vein we already know (and refreshes it);
     *         false if it is a new vein (which is then remembered).
     */
    public boolean touchOrAdd(int g, int x, int y, int z, long nowMs, int linkDist, long maxAgeMs) {
        for (int i = 0; i < size; i++) {
            if (group[i] == g && nowMs - time[i] <= maxAgeMs
                    && Math.abs(vx[i] - x) <= linkDist
                    && Math.abs(vy[i] - y) <= linkDist
                    && Math.abs(vz[i] - z) <= linkDist) {
                vx[i] = x;
                vy[i] = y;
                vz[i] = z;
                time[i] = nowMs;
                return true;
            }
        }
        group[next] = g;
        vx[next] = x;
        vy[next] = y;
        vz[next] = z;
        time[next] = nowMs;
        next = (next + 1) % group.length;
        if (size < group.length) {
            size++;
        }
        return false;
    }
}
