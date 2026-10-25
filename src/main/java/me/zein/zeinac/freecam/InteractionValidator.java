package me.zein.zeinac.freecam;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.core.Geo;
import me.zein.zeinac.core.PoseRing;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-authoritative interaction validation.
 *
 * <p>A block interaction is accepted when there is at least one recent server-known pose (live position plus a few
 * samples from the last ~350 ms, to absorb latency) from which the block is reachable, visible (outline ray cast, the same
 * shape vanilla clients target with) and roughly in front of the player. Freecam camera movement itself is never
 * evaluated: it is invisible to the server. Only the resulting impossible interaction is.</p>
 */
public final class InteractionValidator {
    public record Result(boolean ok, String type, String detail) {
        static final Result OK = new Result(true, "", "");
    }

    private static final class Candidate {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final boolean live;

        Candidate(double x, double y, double z, float yaw, float pitch, boolean live) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.live = live;
        }
    }

    private final ZeinAC plugin;

    public InteractionValidator(ZeinAC plugin) {
        this.plugin = plugin;
    }

    public Result check(Player p, FreecamState st, Block target, long now) {
        Settings s = plugin.settings();
        Settings.Freecam c = s.freecam;
        World w = target.getWorld();
        if (!w.equals(p.getWorld())) {
            return new Result(false, "REACH", "target in another world");
        }
        BoundingBox bb = boundsOf(target);
        double reach = (p.getGameMode() == GameMode.CREATIVE ? c.reachCreative : c.reachSurvival) + c.reachExtra;
        boolean useLos = c.lineOfSight && st.allowRayCheck(now, s.maxLosChecksPerSecond);
        boolean useLook = c.lookCheck && !st.bedrock;

        List<Candidate> candidates = new ArrayList<>(c.historySamples + 1);
        Location live = p.getLocation();
        candidates.add(new Candidate(live.getX(), live.getY(), live.getZ(), live.getYaw(), live.getPitch(), true));
        PoseRing ring = st.pose;
        for (int i = 0; i < ring.size() && candidates.size() <= c.historySamples; i++) {
            if (now - ring.time(i) > c.historyMs) {
                break;
            }
            candidates.add(new Candidate(ring.x(i), ring.y(i), ring.z(i), ring.yaw(i), ring.pitch(i), false));
        }

        double current = p.getEyeHeight();
        int bestStage = 0;
        double bestDist = Double.MAX_VALUE;
        double bestAngle = 0.0;
        List<double[]> tested = new ArrayList<>();

        for (Candidate cd : candidates) {
            double[] heights = cd.live ? new double[] {current, 1.62, 1.27} : new double[] {current, 1.62};
            for (double eyeH : heights) {
                double ex = cd.x;
                double ey = cd.y + eyeH;
                double ez = cd.z;
                if (alreadyTested(tested, ex, ey, ez, cd.yaw, cd.pitch)) {
                    continue;
                }
                tested.add(new double[] {ex, ey, ez, cd.yaw, cd.pitch});

                double dist = Geo.distanceToBox(ex, ey, ez, bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                        bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
                int stage = 0;
                double angle = 0.0;
                if (dist > reach) {
                    stage = 1;
                } else if (dist > 1e-6) {
                    if (useLos && !hasLineOfSight(w, ex, ey, ez, bb, target)) {
                        stage = 2;
                    } else if (useLook && dist > 0.3) {
                        double[] cp = Geo.closestPointOnBox(ex, ey, ez, bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                                bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
                        double[] look = Geo.lookVector(cd.yaw, cd.pitch);
                        angle = Geo.angleDeg(look[0], look[1], look[2], cp[0] - ex, cp[1] - ey, cp[2] - ez);
                        if (angle > c.lookMaxDegrees) {
                            stage = 3;
                        }
                    }
                }
                if (stage == 0) {
                    return Result.OK;
                }
                if (stage > bestStage || (stage == bestStage && dist < bestDist)) {
                    bestStage = stage;
                    bestDist = dist;
                    bestAngle = angle;
                }
            }
        }
        String type = bestStage == 1 ? "REACH" : bestStage == 2 ? "NO_LOS" : "LOOK";
        String detail = String.format(Locale.ROOT, "%s dist=%.2f max=%.2f angle=%.0f block=%s",
                type, bestDist, reach, bestAngle, target.getType().name());
        return new Result(false, type, detail);
    }

    private static boolean alreadyTested(List<double[]> tested, double x, double y, double z, float yaw, float pitch) {
        for (double[] t : tested) {
            double dx = t[0] - x;
            double dy = t[1] - y;
            double dz = t[2] - z;
            if (dx * dx + dy * dy + dz * dz < 0.0225 && Math.abs(t[3] - yaw) < 2.0 && Math.abs(t[4] - pitch) < 2.0) {
                return true;
            }
        }
        return false;
    }

    private static BoundingBox boundsOf(Block b) {
        BoundingBox bb = b.getBoundingBox();
        if (bb == null || bb.getVolume() <= 1e-6) {
            bb = BoundingBox.of(b);
        }
        return bb;
    }

    private static final double[] F = {0.08, 0.5, 0.92};

    /**
     * True if any of 15 sample points on the block (centre, 6 face centres, 8 corners; slightly inset) can be reached by a ray
     * from the eye without another block's outline in between. Outline mode (ignorePassableBlocks=false) mirrors client targeting.
     */
    private static boolean hasLineOfSight(World w, double ex, double ey, double ez, BoundingBox bb, Block target) {
        Location start = new Location(w, ex, ey, ez);
        double sx = bb.getWidthX();
        double sy = bb.getHeight();
        double sz = bb.getWidthZ();
        if (rayReaches(w, start, bb.getMinX() + sx * 0.5, bb.getMinY() + sy * 0.5, bb.getMinZ() + sz * 0.5, target)) {
            return true;
        }
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                double fx = axis == 0 ? (side == 0 ? F[0] : F[2]) : 0.5;
                double fy = axis == 1 ? (side == 0 ? F[0] : F[2]) : 0.5;
                double fz = axis == 2 ? (side == 0 ? F[0] : F[2]) : 0.5;
                if (rayReaches(w, start, bb.getMinX() + sx * fx, bb.getMinY() + sy * fy, bb.getMinZ() + sz * fz, target)) {
                    return true;
                }
            }
        }
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    if (rayReaches(w, start, bb.getMinX() + sx * F[cx * 2], bb.getMinY() + sy * F[cy * 2],
                            bb.getMinZ() + sz * F[cz * 2], target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean rayReaches(World w, Location start, double px, double py, double pz, Block target) {
        double dx = px - start.getX();
        double dy = py - start.getY();
        double dz = pz - start.getZ();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6) {
            return true;
        }
        Vector dir = new Vector(dx / d, dy / d, dz / d);
        RayTraceResult r = w.rayTraceBlocks(start, dir, d + 0.05, FluidCollisionMode.NEVER, false);
        if (r == null) {
            return true;
        }
        Block hit = r.getHitBlock();
        return hit == null || (hit.getX() == target.getX() && hit.getY() == target.getY() && hit.getZ() == target.getZ());
    }
}
