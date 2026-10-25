package me.zein.zeinac.command;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.model.Alert;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static me.zein.zeinac.util.Msg.markup;
import static me.zein.zeinac.util.Msg.send;
import static me.zein.zeinac.util.Msg.val;

/** /zeinac &lt;status|alerts|inspect|evidence|reload|exempt|unexempt|escalate&gt; */
public final class ZeinCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("status", "alerts", "inspect", "evidence", "reload", "exempt", "unexempt", "escalate");
    private final ZeinAC plugin;

    public ZeinCommand(ZeinAC plugin) {
        this.plugin = plugin;
    }

    private void say(CommandSender to, String template, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        send(to, "<prefix> " + template, prepend(markup("prefix", plugin.settings().prefix), r));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] prepend(
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver first,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] rest) {
        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] out =
                new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    private static boolean allowed(CommandSender s, String sub) {
        return switch (sub) {
            case "status", "reload", "escalate" -> s.hasPermission("zeinac.admin");
            case "alerts" -> s.hasPermission("zeinac.alerts");
            case "inspect", "evidence" -> s.hasPermission("zeinac.inspect");
            case "exempt", "unexempt" -> s.hasPermission("zeinac.exempt");
            default -> false;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (sender.hasPermission("zeinac.alerts") || sender.hasPermission("zeinac.inspect") || sender.hasPermission("zeinac.admin")) {
                say(sender, "<gray>Usage: <white>/zeinac <status|alerts|inspect|evidence|reload|exempt|unexempt|escalate>");
            } else {
                say(sender, "<red>You do not have permission.");
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBS.contains(sub)) {
            say(sender, "<red>Unknown subcommand.");
            return true;
        }
        if (!allowed(sender, sub)) {
            say(sender, "<red>You do not have permission.");
            return true;
        }
        switch (sub) {
            case "status" -> status(sender);
            case "alerts" -> alerts(sender, args);
            case "inspect" -> inspect(sender, args);
            case "evidence" -> evidence(sender, args);
            case "reload" -> reload(sender);
            case "exempt" -> exempt(sender, args, true);
            case "unexempt" -> exempt(sender, args, false);
            default -> escalate(sender, args);
        }
        return true;
    }

    // ------------------------------------------------------------------ status
    private void status(CommandSender s) {
        Settings c = plugin.settings();
        String[] levels = {"", "monitor", "staff alert", "restrict", "escalation"};
        say(s, "<gray>ZeinAC <white><v> <gray>| level <white><level> <gray>| safe-mode <safe>",
                val("v", plugin.getPluginMeta().getVersion()), val("level", c.level + " (" + levels[c.level] + ")"),
                markup("safe", c.safeMode ? "<yellow>ON (nothing is cancelled)" : "<green>OFF (invalid actions are cancelled)"));
        say(s, "<gray>Anti-freecam: <white><a> <gray>(tracked <n>) | Anti-xray: <white><b> <gray>(tracked <m>, groups <g>)",
                val("a", String.valueOf(c.freecam.enabled)), val("n", String.valueOf(plugin.freecam().tracked())),
                val("b", String.valueOf(c.xray.enabled)), val("m", String.valueOf(plugin.xray().tracked())),
                val("g", String.valueOf(c.xray.groups.size())));
        say(s, "<gray>Hide module: <white><h>", val("h", plugin.hide() == null
                ? (c.hide.enabled ? "not running (PacketEvents missing or failed)" : "disabled") : plugin.hide().status()));
        say(s, "<gray>Database: <white><d> <gray>| queue <q> | alerts today <t> | runtime exemptions <e>",
                val("d", plugin.database().healthy() ? "ok" : "UNAVAILABLE (memory only)"),
                val("q", String.valueOf(plugin.database().queueSize())), val("t", String.valueOf(plugin.alerts().todayCount())),
                val("e", String.valueOf(plugin.exempt().count())));
    }

    // ------------------------------------------------------------------ alerts
    private void alerts(CommandSender s, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (s instanceof Player ? "toggle" : "recent");
        if (mode.equals("toggle")) {
            if (!(s instanceof Player p)) {
                say(s, "<red>Only players can toggle alerts.");
                return;
            }
            boolean on = plugin.alerts().toggle(p.getUniqueId());
            say(s, on ? "<green>Staff alerts enabled." : "<yellow>Staff alerts muted.");
            return;
        }
        int n = 10;
        if (args.length > 2) {
            try {
                n = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        List<Alert> list = plugin.alerts().recent(n);
        if (list.isEmpty()) {
            say(s, "<gray>No alerts since the last restart.");
            return;
        }
        for (Alert a : list) {
            s.sendMessage(line(a));
        }
    }

    private static Component line(Alert a) {
        return Component.text(me.zein.zeinac.util.Msg.time(a.ts()) + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(a.name() + " ", NamedTextColor.WHITE))
                .append(Component.text(a.module() + "/" + a.type() + " ", NamedTextColor.RED))
                .append(Component.text(a.confidence() + " ", NamedTextColor.YELLOW))
                .append(Component.text(String.format(Locale.ROOT, "%.1f @%s %d %d %d - %s", a.score(), a.world(), a.x(), a.y(), a.z(),
                        a.detail()), NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------ inspect / evidence
    private void inspect(CommandSender s, String[] args) {
        if (args.length < 2) {
            say(s, "<gray>Usage: <white>/zeinac inspect <player>");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            say(s, "<red>Player must be online. Use <white>/zeinac evidence <red>for stored history.");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(t.getName() + " exempt=" + plugin.exempt().isExempt(t) + " mode=" + t.getGameMode());
        lines.addAll(plugin.freecam().describe(t));
        lines.addAll(plugin.xray().describe(t));
        if (plugin.hide() != null) {
            lines.addAll(plugin.hide().describe(t));
        }
        for (String l : lines) {
            s.sendMessage(Component.text(l, NamedTextColor.GRAY));
        }
    }

    private void evidence(CommandSender s, String[] args) {
        if (args.length < 2) {
            say(s, "<gray>Usage: <white>/zeinac evidence <player> [count]");
            return;
        }
        String name = args[1];
        int n = 10;
        if (args.length > 2) {
            try {
                n = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        if (!plugin.database().healthy()) {
            say(s, "<red>Database unavailable. Showing in-memory alerts only.");
            for (Alert a : plugin.alerts().recent(200)) {
                if (a.name().equalsIgnoreCase(name)) {
                    s.sendMessage(line(a));
                }
            }
            return;
        }
        final int limit = n;
        plugin.database().queryAlertsByName(name, limit).thenAccept(list -> plugin.sync(() -> {
            if (list.isEmpty()) {
                say(s, "<gray>No stored evidence for <white><p>", val("p", name));
                return;
            }
            say(s, "<gray>Last <n> alert(s) for <white><p><gray>:", val("n", String.valueOf(list.size())), val("p", name));
            for (Alert a : list) {
                s.sendMessage(line(a));
            }
        }));
    }

    // ------------------------------------------------------------------ admin
    private void reload(CommandSender s) {
        try {
            plugin.reload();
            say(s, "<green>Configuration reloaded. Per-player windows were reset.");
        } catch (RuntimeException e) {
            say(s, "<red>Reload failed, previous settings are still active: <e>", val("e", String.valueOf(e.getMessage())));
        }
    }

    private void exempt(CommandSender s, String[] args, boolean add) {
        if (args.length < 2) {
            say(s, "<gray>Usage: <white>/zeinac " + (add ? "exempt" : "unexempt") + " <player>");
            return;
        }
        UUID id;
        String name;
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (off == null) {
                say(s, "<red>Unknown player (they must have joined this server before).");
                return;
            }
            id = off.getUniqueId();
            name = off.getName() == null ? args[1] : off.getName();
        }
        if (add) {
            boolean changed = plugin.exempt().add(id, name, s.getName());
            say(s, changed ? "<green><p> is now exempt from ZeinAC checks." : "<yellow><p> was already exempt.", val("p", name));
        } else {
            boolean changed = plugin.exempt().remove(id);
            say(s, changed ? "<green><p> is no longer exempt." : "<yellow><p> was not exempt (config/permission exemptions are not affected).",
                    val("p", name));
        }
    }

    private void escalate(CommandSender s, String[] args) {
        if (args.length < 2) {
            say(s, "<gray>Usage: <white>/zeinac escalate <player>");
            return;
        }
        if (!plugin.enforcement().escalationEnabled()) {
            say(s, "<red>Escalation is disabled (needs enforcement-level 4 and escalation.enabled: true).");
            return;
        }
        int n = plugin.escalation().run(args[1], "ZeinAC reviewed by " + s.getName(), s);
        say(s, n > 0 ? "<green>Ran <n> configured command(s) for <p>." : "<red>Nothing executed (invalid name or no commands configured).",
                val("n", String.valueOf(n)), val("p", args[1]));
    }

    // ------------------------------------------------------------------ tab completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBS) {
                if (allowed(sender, sub) && sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (!SUBS.contains(sub) || !allowed(sender, sub)) {
                return out;
            }
            if (sub.equals("alerts")) {
                for (String o : List.of("toggle", "recent")) {
                    if (o.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(o);
                    }
                }
            } else if (!sub.equals("status") && !sub.equals("reload")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(p.getName());
                    }
                }
            }
        }
        return out;
    }
}
