package me.zein.zeinac.config;

import me.zein.zeinac.model.Confidence;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable, validated snapshot of config.yml. A reload builds a new Settings and swaps it in atomically. */
public final class Settings {
    public final boolean debug;
    public final boolean safeMode;
    public final int level;
    public final String prefix;
    public final Alerts alerts;
    public final Exemptions exemptions;
    public final Freecam freecam;
    public final Xray xray;
    public final Hide hide;
    public final Storage storage;
    public final Escalation escalation;
    public final Webhook webhook;
    public final int maxLosChecksPerSecond;

    private Settings(FileConfiguration c, Logger log) {
        this.debug = c.getBoolean("general.debug", false);
        this.safeMode = c.getBoolean("general.safe-mode", true);
        this.level = (int) clamp(c.getInt("general.enforcement-level", 3), 1, 4);
        this.prefix = c.getString("general.prefix", "<dark_gray>[<red>ZeinAC<dark_gray>]<reset>");
        this.alerts = new Alerts(c);
        this.exemptions = new Exemptions(c);
        this.freecam = new Freecam(c);
        this.xray = new Xray(c, log);
        this.hide = new Hide(c, log);
        this.storage = new Storage(c);
        this.escalation = new Escalation(c);
        this.webhook = new Webhook(c);
        this.maxLosChecksPerSecond = (int) clamp(c.getInt("performance.max-los-checks-per-second", 40), 5, 400);
    }

    public static Settings load(FileConfiguration c, Logger log) {
        return new Settings(c, log);
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    static Set<Material> materials(List<String> names, Logger log, String ctx) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m == null) {
                log.warning("config: unknown material '" + n + "' in " + ctx + " (ignored)");
            } else {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ alerts
    public static final class Alerts {
        public final long cooldownMs;
        public final boolean console;
        public final int recentMemory;
        public final String format;
        public final Confidence staffMinConfidence;

        Alerts(FileConfiguration c) {
            this.cooldownMs = (long) clamp(c.getInt("alerts.cooldown-seconds", 30), 1, 3600) * 1000L;
            this.console = c.getBoolean("alerts.console", true);
            this.recentMemory = (int) clamp(c.getInt("alerts.recent-memory", 100), 10, 1000);
            this.staffMinConfidence = Confidence.parse(c.getString("alerts.staff-min-confidence", "MEDIUM"), Confidence.MEDIUM);
            this.format = c.getString("alerts.format",
                    "<prefix> <white><player> <gray>» <red><module>/<type> <yellow><confidence> <gray>score <white><score>"
                            + " <dark_gray>@ <world> <x> <y> <z><newline><dark_gray>  <detail>");
        }
    }

    // ------------------------------------------------------------------ exemptions
    public static final class Exemptions {
        public final Set<String> names;
        public final Set<String> uuids;

        Exemptions(FileConfiguration c) {
            Set<String> n = new HashSet<>();
            for (String s : c.getStringList("exemptions.usernames")) {
                n.add(s.toLowerCase(Locale.ROOT));
            }
            Set<String> u = new HashSet<>();
            for (String s : c.getStringList("exemptions.uuids")) {
                u.add(s.toLowerCase(Locale.ROOT));
            }
            this.names = Collections.unmodifiableSet(n);
            this.uuids = Collections.unmodifiableSet(u);
        }
    }

    // ------------------------------------------------------------------ freecam
    public static final class Freecam {
        public final boolean enabled;
        public final boolean checkCreative;
        public final boolean validateBreak;
        public final boolean validateInteract;
        public final boolean validatePlace;
        public final double reachSurvival;
        public final double reachCreative;
        public final double reachExtra;
        public final boolean lineOfSight;
        public final boolean lookCheck;
        public final double lookMaxDegrees;
        public final long historyMs;
        public final int historySamples;
        public final List<String> bedrockPrefixes;
        public final double weightReach;
        public final double weightLos;
        public final double weightLook;
        public final double weightMulti;
        public final long halfLifeMs;
        public final double recordVl;
        public final double alertVl;
        public final double escalateVl;
        public final boolean multiBreakEnabled;
        public final int multiBreakMax;

        Freecam(FileConfiguration c) {
            String p = "anti-freecam.";
            this.enabled = c.getBoolean(p + "enabled", true);
            this.checkCreative = c.getBoolean(p + "check-creative", false);
            this.validateBreak = c.getBoolean(p + "validate.break", true);
            this.validateInteract = c.getBoolean(p + "validate.interact", true);
            this.validatePlace = c.getBoolean(p + "validate.place", true);
            this.reachSurvival = clamp(c.getDouble(p + "reach.survival", 4.5), 2.0, 12.0);
            this.reachCreative = clamp(c.getDouble(p + "reach.creative", 5.0), 2.0, 12.0);
            this.reachExtra = clamp(c.getDouble(p + "reach.extra", 1.5), 0.0, 6.0);
            this.lineOfSight = c.getBoolean(p + "line-of-sight", true);
            this.lookCheck = c.getBoolean(p + "look-check", true);
            this.lookMaxDegrees = clamp(c.getDouble(p + "look-max-degrees", 60.0), 20.0, 180.0);
            this.historyMs = (long) clamp(c.getInt(p + "history-ms", 350), 0, 2000);
            this.historySamples = (int) clamp(c.getInt(p + "history-samples", 6), 1, 12);
            List<String> bp = new ArrayList<>(c.getStringList(p + "bedrock-prefixes"));
            bp.removeIf(String::isEmpty);
            this.bedrockPrefixes = Collections.unmodifiableList(bp);
            this.weightReach = c.getDouble(p + "weights.REACH", 2.0);
            this.weightLos = c.getDouble(p + "weights.NO_LOS", 1.0);
            this.weightLook = c.getDouble(p + "weights.LOOK", 1.0);
            this.weightMulti = c.getDouble(p + "weights.MULTI_BREAK", 1.5);
            this.halfLifeMs = (long) clamp(c.getInt(p + "vl.half-life-seconds", 90), 5, 3600) * 1000L;
            this.recordVl = c.getDouble(p + "vl.record", 1.0);
            this.alertVl = c.getDouble(p + "vl.alert", 3.0);
            this.escalateVl = c.getDouble(p + "vl.escalate", 10.0);
            this.multiBreakEnabled = c.getBoolean(p + "multi-break.enabled", false);
            this.multiBreakMax = (int) clamp(c.getInt(p + "multi-break.max-per-tick", 3), 2, 50);
        }

        public double weight(String type) {
            return switch (type) {
                case "REACH" -> weightReach;
                case "NO_LOS" -> weightLos;
                case "LOOK" -> weightLook;
                default -> weightMulti;
            };
        }
    }

    // ------------------------------------------------------------------ xray
    public record OreGroup(String id, World.Environment env, Set<Material> materials, int yMin, int yMax,
                           double expectedPer1000) {
        public boolean contains(Material m, World.Environment e, int y) {
            return e == env && y >= yMin && y <= yMax && materials.contains(m);
        }

        public boolean yInRange(int y) {
            return y >= yMin && y <= yMax;
        }
    }

    public static final class Xray {
        public final boolean enabled;
        public final Set<String> ignoredWorlds;
        public final int windowMinutes;
        public final int minCountedBlocks;
        public final int minHidden;
        public final double rateMinScore;
        public final double behaviorMinScore;
        public final long lifetimeMinBlocks;
        public final double lifetimePriorBlocks;
        public final double priorWeightBlocks;
        public final boolean learnBaseline;
        public final int veinLinkDistance;
        public final long veinLinkAgeMs;
        public final long revealAgeMs;
        public final double branchSampleDist;
        public final double branchTurnDot;
        public final double branchMinLength;
        public final double branchNullProb;
        public final int minBranches;
        public final int minHits;
        public final double recordThreshold;
        public final double alertThreshold;
        public final long suspicionHalfLifeMs;
        public final List<OreGroup> groups;
        public final Map<World.Environment, Set<Material>> counted;

        Xray(FileConfiguration c, Logger log) {
            String p = "anti-xray.";
            this.enabled = c.getBoolean(p + "enabled", true);
            Set<String> iw = new HashSet<>();
            for (String s : c.getStringList(p + "ignored-worlds")) {
                iw.add(s.toLowerCase(Locale.ROOT));
            }
            this.ignoredWorlds = Collections.unmodifiableSet(iw);
            this.windowMinutes = (int) clamp(c.getInt(p + "window-minutes", 45), 5, 240);
            this.minCountedBlocks = (int) clamp(c.getInt(p + "min-counted-blocks", 250), 20, 100000);
            this.minHidden = (int) clamp(c.getInt(p + "min-hidden-discoveries", 3), 1, 1000);
            this.rateMinScore = clamp(c.getDouble(p + "indicators.rate-min-score", 4.0), 1.0, 50.0);
            this.behaviorMinScore = clamp(c.getDouble(p + "indicators.behavior-min-score", 3.0), 1.0, 50.0);
            this.lifetimeMinBlocks = (long) clamp(c.getInt(p + "lifetime.min-blocks", 4000), 500, 10_000_000);
            this.lifetimePriorBlocks = clamp(c.getInt(p + "lifetime.prior-blocks", 3000), 100, 1_000_000);
            this.priorWeightBlocks = clamp(c.getInt(p + "baseline.prior-weight-blocks", 20000), 1000, 10_000_000);
            this.learnBaseline = c.getBoolean(p + "baseline.learn-from-server", true);
            this.veinLinkDistance = (int) clamp(c.getInt(p + "vein.link-distance", 3), 1, 8);
            this.veinLinkAgeMs = (long) clamp(c.getInt(p + "vein.link-seconds", 60), 5, 600) * 1000L;
            this.revealAgeMs = (long) clamp(c.getInt(p + "reveal-by-own-digging-seconds", 90), 10, 900) * 1000L;
            this.branchSampleDist = clamp(c.getDouble(p + "branch.sample-distance", 3.0), 2.0, 8.0);
            this.branchTurnDot = clamp(c.getDouble(p + "branch.turn-cosine", 0.6), -0.5, 0.95);
            this.branchMinLength = clamp(c.getDouble(p + "branch.min-length", 6.0), 3.0, 64.0);
            this.branchNullProb = clamp(c.getDouble(p + "branch.null-hit-probability", 0.08), 0.005, 0.9);
            this.minBranches = (int) clamp(c.getInt(p + "branch.min-branches", 6), 2, 1000);
            this.minHits = (int) clamp(c.getInt(p + "branch.min-hits", 3), 1, 1000);
            this.recordThreshold = c.getDouble(p + "suspicion.record-threshold", 4.0);
            this.alertThreshold = c.getDouble(p + "suspicion.alert-threshold", 12.0);
            this.suspicionHalfLifeMs = (long) clamp(c.getInt(p + "suspicion.half-life-minutes", 240), 5, 10080) * 60_000L;

            List<OreGroup> gs = new ArrayList<>();
            ConfigurationSection sec = c.getConfigurationSection(p + "groups");
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    String gp = p + "groups." + id + ".";
                    if (!c.getBoolean(gp + "enabled", true)) {
                        continue;
                    }
                    World.Environment env;
                    try {
                        env = World.Environment.valueOf(c.getString(gp + "environment", "NORMAL").toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        log.warning("config: bad environment for ore group '" + id + "' (group skipped)");
                        continue;
                    }
                    Set<Material> mats = materials(c.getStringList(gp + "materials"), log, "anti-xray.groups." + id);
                    if (mats.isEmpty()) {
                        log.warning("config: ore group '" + id + "' has no valid materials (group skipped)");
                        continue;
                    }
                    int yMin = c.getInt(gp + "y-min", -64);
                    int yMax = c.getInt(gp + "y-max", 320);
                    double exp = clamp(c.getDouble(gp + "expected-per-1000", 2.0), 0.01, 500.0);
                    gs.add(new OreGroup(id.toLowerCase(Locale.ROOT), env, mats, Math.min(yMin, yMax), Math.max(yMin, yMax), exp));
                }
            }
            this.groups = Collections.unmodifiableList(gs);

            Map<World.Environment, Set<Material>> cm = new EnumMap<>(World.Environment.class);
            for (World.Environment e : World.Environment.values()) {
                List<String> names = c.getStringList(p + "counted-blocks." + e.name());
                if (!names.isEmpty()) {
                    cm.put(e, materials(names, log, "anti-xray.counted-blocks." + e.name()));
                }
            }
            this.counted = Collections.unmodifiableMap(cm);
        }

        public boolean isCounted(World.Environment env, Material m) {
            Set<Material> s = counted.get(env);
            return s != null && s.contains(m);
        }
    }

    // ------------------------------------------------------------------ hide (PacketEvents module)
    public record HideWorld(String name, int hideBelowY, int revealBelowY, int hysteresis, int radiusChunks) {
    }

    public static final class Hide {
        public final boolean enabled;
        public final Map<String, HideWorld> worlds;
        public final int taskIntervalTicks;
        public final int refreshRadiusChunks;
        public final int refreshChunksPerTick;

        Hide(FileConfiguration c, Logger log) {
            String p = "hide.";
            this.enabled = c.getBoolean(p + "enabled", true);
            this.taskIntervalTicks = (int) clamp(c.getInt(p + "task-interval-ticks", 5), 1, 40);
            this.refreshRadiusChunks = (int) clamp(c.getInt(p + "refresh-radius-chunks", 10), 2, 32);
            this.refreshChunksPerTick = (int) clamp(c.getInt(p + "refresh-chunks-per-tick", 4), 1, 32);
            Map<String, HideWorld> m = new HashMap<>();
            ConfigurationSection sec = c.getConfigurationSection(p + "worlds");
            if (sec != null) {
                for (String w : sec.getKeys(false)) {
                    String wp = p + "worlds." + w + ".";
                    int hide = c.getInt(wp + "hide-below-y", 16);
                    int reveal = c.getInt(wp + "reveal-below-y", hide + 18);
                    if (reveal <= hide) {
                        log.warning("config: hide.worlds." + w + ": reveal-below-y must be above hide-below-y; using hide+18");
                        reveal = hide + 18;
                    }
                    int hyst = (int) clamp(c.getInt(wp + "hysteresis", 4), 0, 32);
                    int radius = (int) clamp(c.getInt(wp + "underground-radius-chunks", 3), -1, 32);
                    m.put(w, new HideWorld(w, hide, reveal, hyst, radius));
                }
            }
            this.worlds = Collections.unmodifiableMap(m);
        }
    }

    // ------------------------------------------------------------------ storage
    public static final class Storage {
        public final String file;
        public final int retentionDays;
        public final int statsRetentionDays;

        Storage(FileConfiguration c) {
            this.file = c.getString("storage.file", "zeinac.db");
            this.retentionDays = (int) clamp(c.getInt("storage.retention-days", 30), 1, 3650);
            this.statsRetentionDays = (int) clamp(c.getInt("storage.stats-retention-days", 180), 7, 3650);
        }
    }

    // ------------------------------------------------------------------ escalation / webhook
    public static final class Escalation {
        public final boolean enabled;
        public final boolean auto;
        public final List<String> commands;
        public final long cooldownMs;

        Escalation(FileConfiguration c) {
            this.enabled = c.getBoolean("escalation.enabled", false);
            this.auto = c.getBoolean("escalation.auto-for-verified-freecam", false);
            this.commands = Collections.unmodifiableList(new ArrayList<>(c.getStringList("escalation.commands")));
            this.cooldownMs = (long) clamp(c.getInt("escalation.cooldown-minutes", 10), 1, 1440) * 60_000L;
        }
    }

    public static final class Webhook {
        public final boolean enabled;
        public final String url;
        public final Confidence minConfidence;

        Webhook(FileConfiguration c) {
            this.enabled = c.getBoolean("integrations.discord-webhook.enabled", false);
            this.url = c.getString("integrations.discord-webhook.url", "");
            this.minConfidence = Confidence.parse(c.getString("integrations.discord-webhook.min-confidence", "MEDIUM"), Confidence.MEDIUM);
        }
    }
}
