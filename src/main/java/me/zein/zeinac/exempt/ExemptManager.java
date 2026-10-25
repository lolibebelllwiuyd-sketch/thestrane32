package me.zein.zeinac.exempt;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Players that are never analysed: bypass permission, config lists, or runtime exemptions stored in the database. */
public final class ExemptManager {
    private final ZeinAC plugin;
    private final Set<UUID> runtime = ConcurrentHashMap.newKeySet();

    public ExemptManager(ZeinAC plugin) {
        this.plugin = plugin;
    }

    /** Main thread only (permission lookup). */
    public boolean isExempt(Player p) {
        if (runtime.contains(p.getUniqueId()) || p.hasPermission("zeinac.bypass")) {
            return true;
        }
        Settings.Exemptions e = plugin.settings().exemptions;
        return e.names.contains(p.getName().toLowerCase(Locale.ROOT))
                || e.uuids.contains(p.getUniqueId().toString().toLowerCase(Locale.ROOT));
    }

    public boolean isRuntimeExempt(UUID id) {
        return runtime.contains(id);
    }

    public void setAll(Map<UUID, String> loaded) {
        runtime.addAll(loaded.keySet());
    }

    public boolean add(UUID id, String name, String by) {
        boolean added = runtime.add(id);
        plugin.database().addExempt(id, name, by);
        return added;
    }

    public boolean remove(UUID id) {
        boolean removed = runtime.remove(id);
        plugin.database().removeExempt(id);
        return removed;
    }

    public int count() {
        return runtime.size();
    }
}
