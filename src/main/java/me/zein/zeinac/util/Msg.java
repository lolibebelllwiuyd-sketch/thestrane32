package me.zein.zeinac.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** MiniMessage helpers. Dynamic values are always inserted as "unparsed" so player names can never inject formatting. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Msg() {}

    public static Component parse(String template, TagResolver... resolvers) {
        return MM.deserialize(template, TagResolver.resolver(resolvers));
    }

    public static void send(CommandSender to, String template, TagResolver... resolvers) {
        to.sendMessage(parse(template, resolvers));
    }

    public static TagResolver val(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver markup(String key, String value) {
        return Placeholder.parsed(key, value == null ? "" : value);
    }

    public static String time(long epochMs) {
        return TIME.format(Instant.ofEpochMilli(epochMs));
    }
}
