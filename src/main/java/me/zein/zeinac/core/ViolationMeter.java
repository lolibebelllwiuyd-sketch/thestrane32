package me.zein.zeinac.core;

/** A violation level that decays exponentially with a configurable half-life. */
public final class ViolationMeter {
    private final double halfLifeMs;
    private double value;
    private long lastMs;

    public ViolationMeter(double halfLifeMs) {
        this.halfLifeMs = Math.max(1.0, halfLifeMs);
    }

    public double add(double amount, long nowMs) {
        decay(nowMs);
        value += amount;
        return value;
    }

    public double get(long nowMs) {
        decay(nowMs);
        return value;
    }

    public void reset() {
        value = 0.0;
        lastMs = 0L;
    }

    private void decay(long nowMs) {
        if (lastMs != 0L && nowMs > lastMs && value > 0.0) {
            value *= Math.pow(0.5, (nowMs - lastMs) / halfLifeMs);
            if (value < 1e-4) {
                value = 0.0;
            }
        }
        if (nowMs > lastMs) {
            lastMs = nowMs;
        }
    }
}
