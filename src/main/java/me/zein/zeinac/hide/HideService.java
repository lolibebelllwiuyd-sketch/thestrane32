package me.zein.zeinac.hide;

import org.bukkit.entity.Player;

import java.util.List;

/** Contract between the core plugin and the optional PacketEvents module (kept free of PacketEvents types). */
public interface HideService {
    void start();

    void stop();

    /** Called after config reload: re-evaluates every player and re-sends chunks whose visibility changed. */
    void reload();

    List<String> describe(Player p);

    String status();
}
