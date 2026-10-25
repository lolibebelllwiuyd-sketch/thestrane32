package me.zein.zeinac.alert;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.model.Alert;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static me.zein.zeinac.util.Msg.markup;
import static me.zein.zeinac.util.Msg.parse;
import static me.zein.zeinac.util.Msg.val;

/**
 * Single entry point for evidence. Main thread only.
 * Handles: cooldown / flood protection, persistence (async), staff chat, console, webhook.
 */
public final class AlertManager {
    private static final class Cool {
        long lastMs;
        int suppressed;
    }

    private final ZeinAC plugin;
    private final WebhookNotifier webhook;
    private final Map<String, Cool> cooldowns = new HashMap<>();
    private final Deque<Alert> recent = new ArrayDeque<>();
    private final Set<UUID> muted = new HashSet<>();
    private long dayStartMs;
    private int todayCount;

    public AlertManager(ZeinAC plugin) {
        this.plugin = plugin;
        this.webhook = new WebhookNotifier(plugin);
    }

    /**
     * Records the alert (subject to per-player/type cooldown) and optionally notifies staff.
     *
     * @return true when the alert was emitted, false when suppressed by the cooldown
     */
    public boolean report(Alert alert, boolean notifyStaff) {
        Settings s = plugin.settings();
        long now = alert.ts();
        String key = alert.uuid() + "|" + alert.module() + "|" + alert.type();
        Cool cool = cooldowns.computeIfAbsent(key, k -> new Cool());
        if (cool.lastMs != 0L && now - cool.lastMs < s.alerts.cooldownMs) {
            cool.suppressed++;
            return false;
        }
        cool.lastMs = now;
        String detail = alert.detail();
        if (cool.suppressed > 0) {
            detail = detail + " (+" + cool.suppressed + " similar suppressed)";
            cool.suppressed = 0;
        }
        Alert out = new Alert(alert.ts(), alert.uuid(), alert.name(), alert.module(), alert.type(), alert.confidence(),
                alert.score(), alert.world(), alert.x(), alert.y(), alert.z(), detail);

        if (cooldowns.size() > 1024) {
            cooldowns.values().removeIf(c -> now - c.lastMs > 600_000L);
        }

        plugin.database().insertAlert(out);
        remember(out, now, s.alerts.recentMemory);
        if (s.debug || s.alerts.console) {
            plugin.getLogger().info(String.format(Locale.ROOT, "[alert] %s %s/%s %s score=%.1f @%s %d %d %d | %s%s",
                    out.name(), out.module(), out.type(), out.confidence(), out.score(), out.world(), out.x(), out.y(), out.z(),
                    out.detail(), plugin.enforcement().safeMode() ? " [safe-mode]" : ""));
        }
        if (notifyStaff && plugin.enforcement().staffAlertsEnabled() && out.confidence().atLeast(s.alerts.staffMinConfidence)) {
            notifyStaff(out, s);
        }
        webhook.send(out);
        return true;
    }

    private void remember(Alert a, long now, int cap) {
        recent.addFirst(a);
        while (recent.size() > cap) {
            recent.removeLast();
        }
        if (now - dayStartMs > 86_400_000L) {
            dayStartMs = now;
            todayCount = 0;
        }
        todayCount++;
    }

    private void notifyStaff(Alert a, Settings s) {
        Component msg = parse(s.alerts.format,
                markup("prefix", s.prefix),
                val("player", a.name()),
                val("module", a.module()),
                val("type", a.type()),
                val("confidence", a.confidence().name()),
                val("score", String.format(Locale.ROOT, "%.1f", a.score())),
                val("world", a.world()),
                val("x", Integer.toString(a.x())),
                val("y", Integer.toString(a.y())),
                val("z", Integer.toString(a.z())),
                val("detail", a.detail()))
                .clickEvent(ClickEvent.suggestCommand("/zeinac inspect " + a.name()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to prepare /zeinac inspect " + a.name())));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("zeinac.alerts") && !muted.contains(p.getUniqueId())) {
                p.sendMessage(msg);
            }
        }
    }

    /** @return true when alerts are now ON for this staff member. */
    public boolean toggle(UUID staff) {
        if (muted.remove(staff)) {
            return true;
        }
        muted.add(staff);
        return false;
    }

    public boolean isMuted(UUID staff) {
        return muted.contains(staff);
    }

    public void forget(UUID player) {
        String prefix = player.toString();
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public List<Alert> recent(int n) {
        List<Alert> out = new ArrayList<>();
        for (Alert a : recent) {
            if (out.size() >= n) {
                break;
            }
            out.add(a);
        }
        return out;
    }

    public int todayCount() {
        return todayCount;
    }
}
