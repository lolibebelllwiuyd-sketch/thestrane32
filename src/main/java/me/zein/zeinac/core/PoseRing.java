package me.zein.zeinac.core;

/**
 * Fixed-size ring of the most recent server-known positions/rotations of a player.
 * Index 0 is always the newest sample. No allocation after construction.
 */
public final class PoseRing {
    private final int cap;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final float[] yaws;
    private final float[] pitches;
    private final long[] times;
    private int head = -1;
    private int size = 0;

    public PoseRing(int capacity) {
        this.cap = Math.max(1, capacity);
        this.xs = new double[cap];
        this.ys = new double[cap];
        this.zs = new double[cap];
        this.yaws = new float[cap];
        this.pitches = new float[cap];
        this.times = new long[cap];
    }

    public void record(double x, double y, double z, float yaw, float pitch, long nowMs) {
        if (size > 0) {
            int h = head;
            if (xs[h] == x && ys[h] == y && zs[h] == z && yaws[h] == yaw && pitches[h] == pitch) {
                times[h] = nowMs;
                return;
            }
        }
        head = (head + 1) % cap;
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        yaws[head] = yaw;
        pitches[head] = pitch;
        times[head] = nowMs;
        if (size < cap) {
            size++;
        }
    }

    public void clear() {
        head = -1;
        size = 0;
    }

    public int size() {
        return size;
    }

    private int idx(int i) {
        return ((head - i) % cap + cap) % cap;
    }

    public double x(int i) { return xs[idx(i)]; }
    public double y(int i) { return ys[idx(i)]; }
    public double z(int i) { return zs[idx(i)]; }
    public float yaw(int i) { return yaws[idx(i)]; }
    public float pitch(int i) { return pitches[idx(i)]; }
    public long time(int i) { return times[idx(i)]; }
}
