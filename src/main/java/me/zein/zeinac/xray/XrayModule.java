package me.zein.zeinac.xray;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.config.Settings.OreGroup;
import me.zein.zeinac.core.Baseline;
import me.zein.zeinac.core.PathTracker;
import me.zein.zeinac.core.RecentBreaks;
import me.zein.zeinac.core.Stats;
import me.zein.zeinac.core.VeinTracker;
import me.zein.zeinac.core.ViolationMeter;
import me.zein.zeinac.core.WindowCounters;
import me.zein.zeinac.model.Alert;
import me.zein.zeinac.model.Confidence;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Statistical X-ray detection from legitimately mined blocks. Evidence only: nothing in here ever cancels an action or punishes.
 *
 * <p>For every ore vein a player breaks we ask: was it already visible (next to a cave / someone else's tunnel), or did the
 * player's own digging reveal it just now ("hidden discovery")? Two independent indicator families are then tested:</p>
 * <ul>
 *   <li><b>Rate family</b> - Poisson tail of hidden discoveries versus the expected number for the blocks mined (server baseline),
 *       and versus the player's own long-term history.</li>
 *   <li><b>Behaviour family</b> - binomial tail of "branches that ended at a hidden ore" (how often turning and digging a new
 *       branch ended exactly at a valuable ore that could not be seen).</li>
 * </ul>
 * <p>HIGH confidence needs both families. Luck alone cannot satisfy both for long.</p>
 */
public final class XrayModule implements Listener {
    private static final int F_COUNTED = 0;
    private static final int F_HIDDEN = 1;
    private static final int F_EXPOSED = 2;
    private static final int F_BRANCH = 3;
    private static final int F_HIT = 4;
    private static final int FIELDS = 5;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final class Profile {
        final UUID uuid;
        final String name;
        final int generation;
        final WindowCounters window;
        final long[][] life;
        final PathTracker path;
        final RecentBreaks recent = new RecentBreaks(64);
        final VeinTracker veins = new VeinTracker(8);
        final ViolationMeter suspicion;
        final Set<Long> chunks = new HashSet<>();
        boolean lifeLoaded;
        String lastSummary = "";

        Profile(Player p, Settings.Xray x, int generation) {
            this.uuid = p.getUniqueId();
            this.name = p.getName();
            this.generation = generation;
            this.window = new WindowCounters(x.groups.size(), FIELDS, x.windowMinutes);
            this.life = new long[x.groups.size()][FIELDS];
            this.path = new PathTracker(x.branchSampleDist, x.branchTurnDot, x.branchMinLength, 20_000L);
            this.suspicion = new ViolationMeter(x.suspicionHalfLifeMs);
        }
    }

    private final ZeinAC plugin;
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Baseline baseline = new Baseline();
    private int generation;

    public XrayModule(ZeinAC plugin) {
        this.plugin = plugin;
    }

    public int tracked() {
        return profiles.size();
    }

    public void loadBaseline(Map<String, long[]> stored) {
        baseline.load(stored);
    }

    public void onReload() {
        generation++;
        profiles.clear();
    }

    // ------------------------------------------------------------------ lifecycle
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (plugin.settings().xray.enabled) {
            profile(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Profile pr = profiles.remove(e.getPlayer().getUniqueId());
        if (pr != null) {
            flush(pr);
        }
    }

    private Profile profile(Player p) {
        Profile pr = profiles.get(p.getUniqueId());
        if (pr != null) {
            return pr;
        }
        Settings.Xray x = plugin.settings().xray;
        pr = new Profile(p, x, generation);
        profiles.put(p.getUniqueId(), pr);
        final UUID id = p.getUniqueId();
        final int gen = generation;
        plugin.database().loadStats(id).thenAccept(stored -> plugin.sync(() -> applyLifetime(id, gen, stored)));
        return pr;
    }

    private void applyLifetime(UUID id, int gen, Map<String, long[]> stored) {
        Profile pr = profiles.get(id);
        if (pr == null || pr.generation != gen || stored == null) {
            return; // database failed or profile replaced; lifetime comparison stays disabled for this session
        }
        List<OreGroup> groups = plugin.settings().xray.groups;
        for (int g = 0; g < groups.size() && g < pr.life.length; g++) {
            long[] v = stored.get(groups.get(g).id());
            if (v != null) {
                for (int f = 0; f < FIELDS; f++) {
                    pr.life[g][f] += v[f];
                }
            }
        }
        pr.lifeLoaded = true;
    }

    // ------------------------------------------------------------------ persistence
    private void flush(Profile pr) {
        if (!pr.lifeLoaded) {
            return; // never overwrite stored history with partial data
        }
        List<OreGroup> groups = plugin.settings().xray.groups;
        Map<String, long[]> out = new HashMap<>();
        for (int g = 0; g < groups.size() && g < pr.life.length; g++) {
            out.put(groups.get(g).id(), pr.life[g].clone());
        }
        plugin.database().saveStats(pr.uuid, out);
    }

    /** Hands snapshots to the database thread; performs no I/O itself. */
    public void flushAll() {
        for (Profile pr : profiles.values()) {
            flush(pr);
        }
        plugin.database().saveBaseline(baseline.snapshot());
    }

    // ------------------------------------------------------------------ analysis
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Settings.Xray x = plugin.settings().xray;
        if (!x.enabled || x.groups.isEmpty()) {
            return;
        }
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) {
            return;
        }
        Block b = e.getBlock();
        World w = b.getWorld();
        if (x.ignoredWorlds.contains(w.getName().toLowerCase(Locale.ROOT)) || plugin.exempt().isExempt(p)) {
            return;
        }
        Profile pr = profile(p);
        long now = System.currentTimeMillis();
        Material m = b.getType();
        World.Environment env = w.getEnvironment();
        int bx = b.getX();
        int by = b.getY();
        int bz = b.getZ();

        boolean newBranch = m.isSolid() && pr.path.onBreak(bx + 0.5, by + 0.5, bz + 0.5, now);
        boolean trusted = x.learnBaseline && pr.suspicion.get(now) < x.alertThreshold * 0.25;
        boolean counted = x.isCounted(env, m);
        int oreGroup = -1;

        for (int gi = 0; gi < x.groups.size(); gi++) {
            OreGroup g = x.groups.get(gi);
            if (g.env() != env || !g.yInRange(by)) {
                continue;
            }
            if (newBranch) {
                pr.window.add(now, gi, F_BRANCH, 1);
                pr.life[gi][F_BRANCH]++;
            }
            if (counted) {
                pr.window.add(now, gi, F_COUNTED, 1);
                pr.life[gi][F_COUNTED]++;
                if (trusted) {
                    baseline.addCounted(g.id(), 1);
                }
            }
            if (oreGroup < 0 && g.materials().contains(m)) {
                oreGroup = gi;
            }
        }
        if (counted) {
            if (pr.chunks.size() > 128) {
                pr.chunks.clear();
            }
            pr.chunks.add(((long) (bx >> 4) << 32) | ((bz >> 4) & 0xFFFFFFFFL));
        }
        if (oreGroup >= 0) {
            handleOre(p, pr, x, oreGroup, b, now, trusted);
        }
        pr.recent.add(bx, by, bz, now);
    }

    private void handleOre(Player p, Profile pr, Settings.Xray x, int gi, Block b, long now, boolean trusted) {
        int bx = b.getX();
        int by = b.getY();
        int bz = b.getZ();
        if (pr.veins.touchOrAdd(gi, bx, by, bz, now, x.veinLinkDistance, x.veinLinkAgeMs)) {
            return; // another block of a vein we already counted
        }
        boolean alreadyVisible = false;
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (isOpen(n.getType()) && !pr.recent.contains(n.getX(), n.getY(), n.getZ(), now, x.revealAgeMs)) {
                alreadyVisible = true; // open space we did not dig ourselves: cave, ravine, someone else's tunnel
                break;
            }
        }
        if (alreadyVisible) {
            pr.window.add(now, gi, F_EXPOSED, 1);
            pr.life[gi][F_EXPOSED]++;
            return;
        }
        pr.window.add(now, gi, F_HIDDEN, 1);
        pr.life[gi][F_HIDDEN]++;
        if (trusted) {
            baseline.addHidden(x.groups.get(gi).id(), 1);
        }
        if (pr.path.markHit()) {
            pr.window.add(now, gi, F_HIT, 1);
            pr.life[gi][F_HIT]++;
        }
        evaluate(p, pr, x, gi, b, now);
    }

    private static boolean isOpen(Material m) {
        return m.isAir() || !m.isOccluding();
    }

    private void evaluate(Player p, Profile pr, Settings.Xray x, int gi, Block ore, long now) {
        OreGroup g = x.groups.get(gi);
        int counted = pr.window.sum(now, gi, F_COUNTED);
        int hidden = pr.window.sum(now, gi, F_HIDDEN);
        if (counted < x.minCountedBlocks || hidden < x.minHidden) {
            return;
        }
        double baseRate = baseline.ratePerBlock(g.id(), g.expectedPer1000(), x.priorWeightBlocks);
        double expected = Math.max(1e-6, counted * baseRate);
        double p1 = Stats.poissonUpperTail(hidden, expected);
        double s1 = Stats.score(p1);

        double p3 = 1.0;
        double s3 = 0.0;
        if (pr.lifeLoaded) {
            long beforeBlocks = pr.life[gi][F_COUNTED] - counted;
            long beforeHidden = pr.life[gi][F_HIDDEN] - hidden;
            if (beforeBlocks >= x.lifetimeMinBlocks) {
                double priorHidden = baseRate * x.lifetimePriorBlocks;
                double ownRate = (beforeHidden + priorHidden) / (beforeBlocks + x.lifetimePriorBlocks);
                ownRate = Math.max(ownRate, baseRate * 0.5); // do not become hypersensitive for naturally unlucky players
                p3 = Stats.poissonUpperTail(hidden, Math.max(1e-6, counted * ownRate));
                s3 = Stats.score(p3);
            }
        }

        int branches = pr.window.sum(now, gi, F_BRANCH);
        int hits = pr.window.sum(now, gi, F_HIT);
        double p2 = 1.0;
        double s2 = 0.0;
        if (branches >= x.minBranches && hits >= x.minHits) {
            p2 = Stats.binomialUpperTail(hits, branches, x.branchNullProb);
            s2 = Stats.score(p2);
        }

        double rateScore = Math.max(s1, s3);
        boolean rateTrig = rateScore >= x.rateMinScore;
        boolean behaviourTrig = s2 >= x.behaviorMinScore;
        pr.lastSummary = String.format(Locale.ROOT, "%s: hidden=%d/%d blocks exp=%.2f p=%.1e | own p=%.1e | branches=%d hits=%d p=%.1e",
                g.id(), hidden, counted, expected, p1, p3, branches, hits, p2);
        if (!rateTrig && !behaviourTrig) {
            return;
        }
        double total = pr.suspicion.add((rateTrig ? rateScore : 0.0) + (behaviourTrig ? s2 : 0.0), now);
        Confidence conf = rateTrig && behaviourTrig ? Confidence.HIGH
                : rateTrig && rateScore >= 2.0 * x.rateMinScore ? Confidence.MEDIUM : Confidence.LOW;
        if (total < x.recordThreshold) {
            return;
        }
        int exposed = pr.window.sum(now, gi, F_EXPOSED);
        String detail = pr.lastSummary + String.format(Locale.ROOT, " | exposed=%d chunks=%d families=%s%s",
                exposed, pr.chunks.size(), rateTrig ? "rate" : "", behaviourTrig ? (rateTrig ? "+behaviour" : "behaviour") : "");
        boolean staff = total >= x.alertThreshold;
        plugin.alerts().report(new Alert(now, p.getUniqueId(), p.getName(), "xray", "ORE_STATS_" + g.id().toUpperCase(Locale.ROOT),
                conf, total, ore.getWorld().getName(), ore.getX(), ore.getY(), ore.getZ(), detail), staff);
    }

    // ------------------------------------------------------------------ inspection
    public List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        Profile pr = profiles.get(p.getUniqueId());
        Settings.Xray x = plugin.settings().xray;
        if (pr == null) {
            out.add("xray: no data yet");
            return out;
        }
        long now = System.currentTimeMillis();
        out.add(String.format(Locale.ROOT, "xray: suspicion=%.2f (alert at %.1f) lifetime-loaded=%s",
                pr.suspicion.get(now), x.alertThreshold, pr.lifeLoaded));
        for (int gi = 0; gi < x.groups.size(); gi++) {
            OreGroup g = x.groups.get(gi);
            out.add(String.format(Locale.ROOT, "  %s: window blocks=%d hidden=%d exposed=%d branches=%d hits=%d (expected rate %.2f/1000)",
                    g.id(), pr.window.sum(now, gi, F_COUNTED), pr.window.sum(now, gi, F_HIDDEN), pr.window.sum(now, gi, F_EXPOSED),
                    pr.window.sum(now, gi, F_BRANCH), pr.window.sum(now, gi, F_HIT),
                    baseline.ratePerBlock(g.id(), g.expectedPer1000(), x.priorWeightBlocks) * 1000.0));
        }
        if (!pr.lastSummary.isEmpty()) {
            out.add("  last evaluation: " + pr.lastSummary);
        }
        return out;
    }
}
