package me.zein.zeinac.hide;

/**
 * Immutable per-player view of the hide rules. Created on the main thread, read from netty threads.
 *
 * <p>Rule: blocks below {@code hideBelowY} are hidden in chunk (cx,cz) when the player is on the surface,
 * or when the chunk is further than {@code radius} chunks (Chebyshev) from the player's chunk.</p>
 */
public record HideSnapshot(String world, int env, int minY, int hideBelowY, boolean surface,
                           int pcx, int pcz, int radius) {

    public static final int ENV_NORMAL = 0;
    public static final int ENV_NETHER = 1;
    public static final int ENV_END = 2;

    public boolean hidden(int cx, int cz) {
        if (surface) {
            return true;
        }
        if (radius < 0) {
            return false;
        }
        return Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > radius;
    }

    /** True when chunk (cx,cz) has the same visible/hidden outcome under both snapshots (same world and Y limit). */
    public boolean sameOutcome(HideSnapshot other, int cx, int cz) {
        return other != null && world.equals(other.world) && hideBelowY == other.hideBelowY
                && hidden(cx, cz) == other.hidden(cx, cz);
    }
}
