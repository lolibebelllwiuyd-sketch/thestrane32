package me.zein.zeinac.enforce;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;

/**
 * Separates "we found evidence" from "we act on it".
 * Level 1 monitor, 2 staff alert, 3 restrict (cancel provably invalid actions), 4 staff-reviewed escalation.
 * Safe mode downgrades everything to monitor/alert only.
 */
public final class Enforcement {
    private final ZeinAC plugin;

    public Enforcement(ZeinAC plugin) {
        this.plugin = plugin;
    }

    public boolean staffAlertsEnabled() {
        return plugin.settings().level >= 2;
    }

    /** May we cancel an action the server has proven invalid? Never for statistical suspicion. */
    public boolean canRestrict() {
        Settings s = plugin.settings();
        return !s.safeMode && s.level >= 3;
    }

    public boolean escalationEnabled() {
        Settings s = plugin.settings();
        return s.level >= 4 && s.escalation.enabled;
    }

    public boolean safeMode() {
        return plugin.settings().safeMode;
    }
}
