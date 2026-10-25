package me.zein.zeinac.alert;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Level 4: configurable punishment hook. Statistical suspicion (X-ray) can NEVER trigger it automatically.
 * It is only proposed for sustained, server-verified Freecam violations and, by default, needs a staff member to run
 * {@code /zeinac escalate <player>}.
 */
public final class EscalationManager {
    private final ZeinAC plugin;
    private final Map<UUID, Long> lastProposal = new HashMap<>();

    public EscalationManager(ZeinAC plugin) {
        this.plugin = plugin;
    }

    /** Called from the freecam module when a player's VL crossed the escalation threshold. */
    public void proposeVerified(Player p, String reason) {
        Settings s = plugin.settings();
        if (!plugin.enforcement().escalationEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastProposal.get(p.getUniqueId());
        if (last != null && now - last < s.escalation.cooldownMs) {
            return;
        }
        lastProposal.put(p.getUniqueId(), now);
        if (s.escalation.auto && !plugin.enforcement().safeMode()) {
            run(p.getName(), reason, plugin.getServer().getConsoleSender());
            return;
        }
        Component msg = Component.text("[ZeinAC] ", NamedTextColor.RED)
                .append(Component.text(p.getName() + " reached the escalation threshold (" + reason + "). ", NamedTextColor.GRAY))
                .append(Component.text("[Prepare escalation]", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.suggestCommand("/zeinac escalate " + p.getName())));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("zeinac.admin")) {
                staff.sendMessage(msg);
            }
        }
    }

    /** Runs the configured commands as console. Player names are validated; the reason is sanitised. */
    public int run(String playerName, String reason, CommandSender by) {
        Settings s = plugin.settings();
        if (!playerName.matches("[A-Za-z0-9_.]{1,32}")) {
            return 0;
        }
        String safeReason = reason.replaceAll("[^A-Za-z0-9 _:/.,()-]", "").trim();
        int n = 0;
        for (String cmd : s.escalation.commands) {
            String line = cmd.replace("%player%", playerName).replace("%reason%", safeReason);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            n++;
        }
        plugin.getLogger().info(String.format(Locale.ROOT, "Escalation for %s executed by %s (%d command(s)): %s",
                playerName, by.getName(), n, safeReason));
        return n;
    }

    public void forget(UUID id) {
        lastProposal.remove(id);
    }
}
