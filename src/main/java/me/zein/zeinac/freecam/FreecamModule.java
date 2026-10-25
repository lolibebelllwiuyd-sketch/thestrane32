package me.zein.zeinac.freecam;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.model.Alert;
import me.zein.zeinac.model.Confidence;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-Freecam. Prevents what the server can prove is impossible (breaking, opening, or placing against blocks the player
 * cannot reach or see from where the server knows they are) and records evidence of repeated attempts.
 * Everything here runs on the main thread inside normal Bukkit events.
 */
public final class FreecamModule implements Listener {
    private final ZeinAC plugin;
    private final InteractionValidator validator;
    private final Map<UUID, FreecamState> states = new HashMap<>();

    public FreecamModule(ZeinAC plugin) {
        this.plugin = plugin;
        this.validator = new InteractionValidator(plugin);
    }

    public void onReload() {
        states.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            seed(p, p.getLocation());
        }
    }

    public int tracked() {
        return states.size();
    }

    // ------------------------------------------------------------------ state handling
    private FreecamState state(Player p) {
        FreecamState st = states.get(p.getUniqueId());
        if (st == null) {
            Settings.Freecam c = plugin.settings().freecam;
            st = new FreecamState(c.historySamples, c.halfLifeMs, isBedrock(p, c));
            states.put(p.getUniqueId(), st);
            Location l = p.getLocation();
            st.pose.record(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), System.currentTimeMillis());
        }
        return st;
    }

    private static boolean isBedrock(Player p, Settings.Freecam c) {
        for (String prefix : c.bedrockPrefixes) {
            if (p.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void seed(Player p, Location l) {
        if (l == null) {
            return;
        }
        FreecamState st = state(p);
        st.pose.clear();
        st.pose.record(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        seed(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        states.remove(id);
        plugin.alerts().forget(id);
        plugin.escalation().forget(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        seed(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        seed(e.getPlayer(), e.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || !plugin.settings().freecam.enabled) {
            return;
        }
        state(e.getPlayer()).pose.record(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ interaction events
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Settings.Freecam c = plugin.settings().freecam;
        if (!c.enabled || !applies(e.getPlayer(), c)) {
            return;
        }
        boolean cancel = false;
        if (c.multiBreakEnabled) {
            cancel = multiBreak(e.getPlayer(), e.getBlock(), c);
        }
        if (c.validateBreak && validate(e.getPlayer(), e.getBlock(), "break", c)) {
            cancel = true;
        }
        if (cancel) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Settings.Freecam c = plugin.settings().freecam;
        Action a = e.getAction();
        if (!c.enabled || !c.validateInteract || (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || e.useInteractedBlock() == Event.Result.DENY || !applies(e.getPlayer(), c)) {
            return;
        }
        if (validate(e.getPlayer(), b, a == Action.RIGHT_CLICK_BLOCK ? "use" : "hit", c)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Settings.Freecam c = plugin.settings().freecam;
        if (!c.enabled || !c.validatePlace || !applies(e.getPlayer(), c)) {
            return;
        }
        if (validate(e.getPlayer(), e.getBlockAgainst(), "place", c)) {
            e.setCancelled(true);
        }
    }

    private boolean applies(Player p, Settings.Freecam c) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR || (gm == GameMode.CREATIVE && !c.checkCreative)) {
            return false;
        }
        if (p.isDead() || p.hasMetadata("NPC")) {
            return false;
        }
        return !plugin.exempt().isExempt(p);
    }

    /** @return true when the action must be cancelled. */
    private boolean validate(Player p, Block b, String action, Settings.Freecam c) {
        long now = System.currentTimeMillis();
        FreecamState st = state(p);
        Location l = p.getLocation();
        if (st.cacheHit(b.getX(), b.getY(), b.getZ(), now, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch())) {
            return false;
        }
        InteractionValidator.Result r = validator.check(p, st, b, now);
        if (r.ok()) {
            st.remember(b.getX(), b.getY(), b.getZ(), now, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
            return false;
        }
        violation(p, st, b, r.type(), r.detail() + " action=" + action, now, c);
        return plugin.enforcement().canRestrict();
    }

    private boolean multiBreak(Player p, Block b, Settings.Freecam c) {
        FreecamState st = state(p);
        int tick = Bukkit.getCurrentTick();
        if (st.multiTick == tick) {
            st.multiCount++;
        } else {
            st.multiTick = tick;
            st.multiCount = 1;
        }
        if (st.multiCount > c.multiBreakMax) {
            violation(p, st, b, "MULTI_BREAK", "breaks_in_one_tick=" + st.multiCount + " max=" + c.multiBreakMax,
                    System.currentTimeMillis(), c);
            return plugin.enforcement().canRestrict();
        }
        return false;
    }

    private void violation(Player p, FreecamState st, Block b, String type, String detail, long now, Settings.Freecam c) {
        double vl = st.meter.add(c.weight(type), now);
        st.count(type);
        st.lastType = type;
        st.lastDetail = detail;
        st.lastViolationMs = now;
        Confidence conf = vl >= c.escalateVl ? Confidence.HIGH : vl >= c.alertVl ? Confidence.MEDIUM : Confidence.LOW;
        if (vl >= c.recordVl) {
            plugin.alerts().report(new Alert(now, p.getUniqueId(), p.getName(), "freecam", type, conf, vl,
                    b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), detail), vl >= c.alertVl);
        }
        if (vl >= c.escalateVl) {
            plugin.escalation().proposeVerified(p, "freecam " + type.toLowerCase(Locale.ROOT) + " vl "
                    + String.format(Locale.ROOT, "%.1f", vl));
        }
        if (plugin.settings().debug) {
            plugin.getLogger().info("[freecam] " + p.getName() + " " + type + " vl=" + String.format(Locale.ROOT, "%.2f", vl)
                    + " " + detail);
        }
    }

    // ------------------------------------------------------------------ inspection
    public List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        FreecamState st = states.get(p.getUniqueId());
        if (st == null) {
            out.add("freecam: no data yet");
            return out;
        }
        long now = System.currentTimeMillis();
        out.add(String.format(Locale.ROOT, "freecam: vl=%.2f reach=%d no_los=%d look=%d multi=%d bedrock=%s",
                st.meter.get(now), st.reach, st.los, st.look, st.multi, st.bedrock));
        if (st.lastViolationMs > 0) {
            out.add("freecam last: " + st.lastType + " " + ((now - st.lastViolationMs) / 1000) + "s ago: " + st.lastDetail);
        }
        return out;
    }
}
