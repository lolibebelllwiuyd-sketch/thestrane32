package me.zein.zeinac.core;

/**
 * Tracks the shape of a player's digging path and counts "branches".
 *
 * <p>A branch is a straight-ish segment that starts with a clear change of direction and reaches a
 * minimum length. Legitimate strip/branch mining creates many branches, and only a small fraction
 * of them end at a valuable hidden ore. A player who digs straight to ores they cannot see will
 * have a much higher "branch ends at hidden ore" ratio. Comparing that ratio against a null
 * probability (binomial test) is independent of the raw ore-find-rate statistic.</p>
 *
 * <p>Pure logic, main-thread only.</p>
 */
public final class PathTracker {
    private final double sampleDist;
    private final double turnDot;
    private final double minLen;
    private final long resetGapMs;

    private boolean hasAnchor;
    private double ax;
    private double ay;
    private double az;
    private long lastMs;

    private boolean hasHeading;
    private double hx;
    private double hy;
    private double hz;
    private double segLen;
    private boolean segTurn;
    private boolean segCounted;
    private boolean segHit;

    public PathTracker(double sampleDist, double turnDot, double minLen, long resetGapMs) {
        this.sampleDist = sampleDist;
        this.turnDot = turnDot;
        this.minLen = minLen;
        this.resetGapMs = resetGapMs;
    }

    /** @return true when this break caused a new turn-segment to reach the minimum branch length. */
    public boolean onBreak(double x, double y, double z, long nowMs) {
        if (hasAnchor && nowMs - lastMs > resetGapMs) {
            hasAnchor = false;
            hasHeading = false;
            segTurn = false;
            segCounted = false;
            segHit = false;
            segLen = 0.0;
        }
        lastMs = nowMs;
        if (!hasAnchor) {
            ax = x;
            ay = y;
            az = z;
            hasAnchor = true;
            return false;
        }
        double dx = x - ax;
        double dy = y - ay;
        double dz = z - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < sampleDist) {
            return false;
        }
        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;
        if (!hasHeading) {
            hx = ux;
            hy = uy;
            hz = uz;
            hasHeading = true;
            segLen = len;
            segTurn = false;
            segCounted = false;
            segHit = false;
        } else {
            double dot = hx * ux + hy * uy + hz * uz;
            if (dot >= turnDot) {
                segLen += len;
                double nx = hx + ux * 0.5;
                double ny = hy + uy * 0.5;
                double nz = hz + uz * 0.5;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-9) {
                    hx = nx / nl;
                    hy = ny / nl;
                    hz = nz / nl;
                }
            } else {
                hx = ux;
                hy = uy;
                hz = uz;
                segLen = len;
                segTurn = true;
                segCounted = false;
                segHit = false;
            }
        }
        ax = x;
        ay = y;
        az = z;
        if (segTurn && !segCounted && segLen >= minLen) {
            segCounted = true;
            return true;
        }
        return false;
    }

    /** Marks that the current branch ended at a hidden valuable ore. @return true once per branch. */
    public boolean markHit() {
        if (segTurn && segCounted && !segHit) {
            segHit = true;
            return true;
        }
        return false;
    }
}
