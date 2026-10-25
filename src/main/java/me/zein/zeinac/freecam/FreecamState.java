package me.zein.zeinac.freecam;

import me.zein.zeinac.core.BlockKey;
import me.zein.zeinac.core.PoseRing;
import me.zein.zeinac.core.ViolationMeter;

/** Per-player anti-freecam state. Bounded: one small ring buffer plus a few counters. Main thread only. */
public final class FreecamState {
    public final PoseRing pose;
    public final ViolationMeter meter;
    public final boolean bedrock;

    public int reach;
    public int los;
    public int look;
    public int multi;
    public String lastType = "";
    public String lastDetail = "";
    public long lastViolationMs;

    // cache of the last accepted interaction (avoids repeated ray casts while digging one block)
    private long okKey = Long.MIN_VALUE;
    private long okMs;
    private double okX;
    private double okY;
    private double okZ;
    private float okYaw;
    private float okPitch;

    // per-second ray-cast budget
    private long budgetWindowMs;
    private int budgetUsed;

    // multi-break (same tick) detection
    public int multiTick = -1;
    public int multiCount;

    public FreecamState(int historySamples, double halfLifeMs, boolean bedrock) {
        this.pose = new PoseRing(historySamples + 1);
        this.meter = new ViolationMeter(halfLifeMs);
        this.bedrock = bedrock;
    }

    public void count(String type) {
        switch (type) {
            case "REACH" -> reach++;
            case "NO_LOS" -> los++;
            case "LOOK" -> look++;
            default -> multi++;
        }
    }

    public boolean cacheHit(int bx, int by, int bz, long now, double x, double y, double z, float yaw, float pitch) {
        if (okKey != BlockKey.pack(bx, by, bz) || now - okMs > 250L) {
            return false;
        }
        double dx = x - okX;
        double dy = y - okY;
        double dz = z - okZ;
        return dx * dx + dy * dy + dz * dz < 0.16
                && Math.abs(yaw - okYaw) < 8f && Math.abs(pitch - okPitch) < 8f;
    }

    public void remember(int bx, int by, int bz, long now, double x, double y, double z, float yaw, float pitch) {
        okKey = BlockKey.pack(bx, by, bz);
        okMs = now;
        okX = x;
        okY = y;
        okZ = z;
        okYaw = yaw;
        okPitch = pitch;
    }

    /** Fail-open ray-cast budget so a spammer can never turn validation into a CPU problem. */
    public boolean allowRayCheck(long now, int maxPerSecond) {
        if (now - budgetWindowMs >= 1000L) {
            budgetWindowMs = now;
            budgetUsed = 0;
        }
        if (budgetUsed >= maxPerSecond) {
            return false;
        }
        budgetUsed++;
        return true;
    }
}
