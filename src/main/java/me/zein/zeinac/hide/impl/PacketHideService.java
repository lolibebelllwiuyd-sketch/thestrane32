package me.zein.zeinac.hide.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.hide.HideService;
import me.zein.zeinac.hide.HideSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proximity-aware replacement for a pure Y-band hider.
 * <ul>
 *   <li>Surface players (above reveal-below-y) get no blocks below hide-below-y at all, so Freecam above ground sees nothing underground.</li>
 *   <li>Underground players only receive real blocks within N chunks; further away the band is hidden, which caps how far underground
 *       a Freecam / X-ray user can look even while mining.</li>
 *   <li>When the rules change for a chunk it is re-sent, a few per tick, so nothing lags.</li>
 * </ul>
 */
public final class PacketHideService implements HideService, Listener {
    private record ChunkRef(UUID world, int x, int z) {
    }

    private final ZeinAC plugin;
    private final ConcurrentHashMap<UUID, HideSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ArrayDeque<ChunkRef> queue = new ArrayDeque<>();
    private final Set<ChunkRef> queued = new HashSet<>();
    private PacketListenerCommon packetListener;
    private BukkitTask task;
    private Method refreshMethod;
    private long refreshed;

    /** Constructed reflectively by the plugin, only when PacketEvents is present. */
    public PacketHideService(ZeinAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        try {
            refreshMethod = World.class.getMethod("refreshChunk", int.class, int.class);
        } catch (NoSuchMethodException e) {
            refreshMethod = null;
            plugin.getLogger().warning("World#refreshChunk is not available on this server; hidden chunks will only update when re-sent naturally.");
        }
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(new HideListener(snapshots));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        int interval = plugin.settings().hide.taskIntervalTicks;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p, p.getLocation(), true);
        }
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }
        // re-send every chunk that was hidden so players are not left with fake stone
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<HideSnapshot> old = new ArrayList<>();
        for (Player p : online) {
            old.add(snapshots.get(p.getUniqueId()));
        }
        snapshots.clear();
        for (int i = 0; i < online.size(); i++) {
            if (old.get(i) != null) {
                enqueueAll(online.get(i), online.get(i).getWorld(), old.get(i), null);
            }
        }
        drain(Integer.MAX_VALUE);
        queue.clear();
        queued.clear();
    }

    @Override
    public void reload() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p, p.getLocation(), false);
        }
    }

    @Override
    public List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        HideSnapshot s = snapshots.get(p.getUniqueId());
        if (s == null) {
            out.add("hide: inactive for this player/world");
        } else {
            out.add(String.format(Locale.ROOT, "hide: world=%s surface=%s hide-below-y=%d radius=%d chunks chunk=(%d,%d)",
                    s.world(), s.surface(), s.hideBelowY(), s.radius(), s.pcx(), s.pcz()));
        }
        return out;
    }

    @Override
    public String status() {
        return "active, players=" + snapshots.size() + ", queued=" + queue.size() + ", refreshed=" + refreshed
                + (refreshMethod == null ? ", refresh=unavailable" : "");
    }

    // ------------------------------------------------------------------ events
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        update(e.getPlayer(), e.getPlayer().getLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        snapshots.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null) {
            update(e.getPlayer(), e.getTo(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        update(e.getPlayer(), e.getRespawnLocation(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        update(e.getPlayer(), e.getPlayer().getLocation(), false);
    }

    // ------------------------------------------------------------------ main-thread logic
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p, p.getLocation(), false);
        }
        drain(plugin.settings().hide.refreshChunksPerTick * Math.max(1, plugin.settings().hide.taskIntervalTicks));
    }

    private void update(Player p, Location loc, boolean joining) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Settings.Hide cfg = plugin.settings().hide;
        Settings.HideWorld hw = cfg.worlds.get(w.getName());
        UUID id = p.getUniqueId();
        HideSnapshot old = snapshots.get(id);
        if (hw == null || plugin.exempt().isExempt(p)) {
            if (old != null) {
                snapshots.remove(id);
                enqueueAll(p, w, old, null);
            }
            return;
        }
        double y = loc.getY();
        boolean surface;
        if (old == null || !old.world().equals(w.getName())) {
            surface = y >= hw.revealBelowY();
        } else if (old.surface()) {
            surface = y >= hw.revealBelowY();
        } else {
            surface = y >= hw.revealBelowY() + hw.hysteresis();
        }
        HideSnapshot now = new HideSnapshot(w.getName(), envOf(w), w.getMinHeight(), hw.hideBelowY(), surface,
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4, hw.radiusChunks());
        if (now.equals(old)) {
            return;
        }
        snapshots.put(id, now);
        if (!joining && (old == null || old.surface() != now.surface() || old.pcx() != now.pcx() || old.pcz() != now.pcz()
                || !old.world().equals(now.world()) || old.hideBelowY() != now.hideBelowY())) {
            enqueueAll(p, w, old, now);
        }
    }

    private static int envOf(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> HideSnapshot.ENV_NETHER;
            case THE_END -> HideSnapshot.ENV_END;
            default -> HideSnapshot.ENV_NORMAL;
        };
    }

    /** Queues every chunk in view whose hidden/visible outcome differs between the two snapshots. */
    private void enqueueAll(Player p, World w, HideSnapshot old, HideSnapshot now) {
        HideSnapshot ref = now != null ? now : old;
        if (ref == null) {
            return;
        }
        int r = Math.min(Math.max(2, w.getViewDistance()), plugin.settings().hide.refreshRadiusChunks);
        int cx0 = ref.pcx();
        int cz0 = ref.pcz();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                boolean was = old != null && old.world().equals(w.getName()) && old.hidden(cx, cz);
                boolean will = now != null && now.hidden(cx, cz);
                boolean bothPresent = old != null && now != null;
                boolean sameRules = bothPresent && old.world().equals(now.world()) && old.hideBelowY() == now.hideBelowY();
                if (sameRules) {
                    if (was == will) {
                        continue;
                    }
                } else if (!bothPresent && !was && !will) {
                    continue; // nothing was or will be hidden here
                }
                // world changed: chunks may have been encoded with a stale snapshot, so re-send everything in view
                if (queue.size() < 4096 && w.isChunkLoaded(cx, cz)) {
                    ChunkRef c = new ChunkRef(w.getUID(), cx, cz);
                    if (queued.add(c)) {
                        queue.add(c);
                    }
                }
            }
        }
    }

    private void drain(int max) {
        int n = 0;
        while (n < max && !queue.isEmpty()) {
            ChunkRef c = queue.poll();
            queued.remove(c);
            World w = Bukkit.getWorld(c.world());
            if (w != null && refreshMethod != null && w.isChunkLoaded(c.x(), c.z())) {
                try {
                    refreshMethod.invoke(w, c.x(), c.z());
                    refreshed++;
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    refreshMethod = null;
                    plugin.getLogger().warning("World#refreshChunk failed, disabling chunk refresh: " + ex);
                }
            }
            n++;
        }
    }
}
