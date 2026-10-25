package me.zein.zeinac.core;

/** Pure geometry helpers so they can be unit-tested without a server. */
public final class Geo {
    private Geo() {}

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Distance from point (ex,ey,ez) to the closest point of an axis-aligned box (0 if inside). */
    public static double distanceToBox(double ex, double ey, double ez,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - ex, 0.0), ex - maxX);
        double dy = Math.max(Math.max(minY - ey, 0.0), ey - maxY);
        double dz = Math.max(Math.max(minZ - ez, 0.0), ez - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Closest point of the box to (ex,ey,ez). */
    public static double[] closestPointOnBox(double ex, double ey, double ez,
                                             double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ) {
        return new double[] {clamp(ex, minX, maxX), clamp(ey, minY, maxY), clamp(ez, minZ, maxZ)};
    }

    public static boolean insideBox(double ex, double ey, double ez,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        return ex >= minX && ex <= maxX && ey >= minY && ey <= maxY && ez >= minZ && ez <= maxZ;
    }

    /** Angle in degrees between two vectors (0 when either is ~zero length). */
    public static double angleDeg(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-9 || lb < 1e-9) {
            return 0.0;
        }
        double c = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(clamp(c, -1.0, 1.0)));
    }

    /** Minecraft look direction for yaw/pitch in degrees (same convention as Bukkit's Location#getDirection). */
    public static double[] lookVector(float yaw, float pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double xz = Math.cos(pr);
        return new double[] {-Math.sin(yr) * xz, -Math.sin(pr), Math.cos(yr) * xz};
    }
}
