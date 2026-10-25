package me.zein.zeinac;

import me.zein.zeinac.alert.AlertManager;
import me.zein.zeinac.alert.EscalationManager;
import me.zein.zeinac.command.ZeinCommand;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.enforce.Enforcement;
import me.zein.zeinac.exempt.ExemptManager;
import me.zein.zeinac.freecam.FreecamModule;
import me.zein.zeinac.hide.HideService;
import me.zein.zeinac.storage.Database;
import me.zein.zeinac.xray.XrayModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/** ZeinAC - evidence-driven anti-xray and anti-freecam for Paper. */
public final class ZeinAC extends JavaPlugin {
    private volatile Settings settings;
    private Database database;
    private ExemptManager exempt;
    private Enforcement enforcement;
    private AlertManager alerts;
    private EscalationManager escalation;
    private FreecamModule freecam;
    private XrayModule xray;
    private HideService hide;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = Settings.load(getConfig(), getLogger());
        } catch (RuntimeException e) {
            getLogger().severe("config.yml is invalid, disabling: " + e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        database = new Database(this);
        exempt = new ExemptManager(this);
        enforcement = new Enforcement(this);
        alerts = new AlertManager(this);
        escalation = new EscalationManager(this);
        freecam = new FreecamModule(this);
        xray = new XrayModule(this);

        database.init(settings.storage.file).thenAccept(ok -> {
            if (ok) {
                sync(this::loadPersisted);
            }
        });

        Bukkit.getPluginManager().registerEvents(freecam, this);
        Bukkit.getPluginManager().registerEvents(xray, this);
        PluginCommand pc = getCommand("zeinac");
        if (pc != null) {
            ZeinCommand zc = new ZeinCommand(this);
            pc.setExecutor(zc);
            pc.setTabCompleter(zc);
        }
        startHide();

        Bukkit.getScheduler().runTaskTimer(this, () -> xray.flushAll(), 20L * 60 * 5, 20L * 60 * 5);
        Bukkit.getScheduler().runTaskTimer(this, this::purge, 20L * 60, 20L * 60 * 60);

        if (settings.safeMode) {
            getLogger().warning("SAFE MODE is ON: violations are logged and alerted but nothing is cancelled. "
                    + "Set general.safe-mode: false in config.yml after you have reviewed the alerts.");
        }
        getLogger().info("ZeinAC enabled (level " + settings.level + ", freecam=" + settings.freecam.enabled
                + ", xray=" + settings.xray.enabled + ", hide=" + (hide != null) + ")");
    }

    @Override
    public void onDisable() {
        if (hide != null) {
            try {
                hide.stop();
            } catch (RuntimeException | LinkageError e) {
                getLogger().warning("Hide module did not stop cleanly: " + e);
            }
            hide = null;
        }
        if (xray != null) {
            xray.flushAll();
        }
        if (database != null) {
            database.close();
        }
    }

    private void loadPersisted() {
        database.loadExempt().thenAccept(m -> sync(() -> exempt.setAll(m)));
        database.loadBaseline().thenAccept(m -> sync(() -> xray.loadBaseline(m)));
    }

    private void purge() {
        long now = System.currentTimeMillis();
        long alertsCutoff = now - TimeUnit.DAYS.toMillis(settings.storage.retentionDays);
        long statsCutoff = now - TimeUnit.DAYS.toMillis(settings.storage.statsRetentionDays);
        database.purge(alertsCutoff, statsCutoff);
    }

    /** Loads PacketEvents-dependent code reflectively so the plugin runs fine without PacketEvents installed. */
    private void startHide() {
        if (hide != null || !settings.hide.enabled) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning("hide.enabled is true but PacketEvents is not installed/enabled; the hide module is off. "
                    + "Detection and interaction validation still work.");
            return;
        }
        try {
            Class<?> impl = Class.forName("me.zein.zeinac.hide.impl.PacketHideService");
            HideService svc = (HideService) impl.getConstructor(ZeinAC.class).newInstance(this);
            svc.start();
            hide = svc;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            getLogger().warning("Hide module could not start (" + e + "). Detection and interaction validation still work.");
            hide = null;
        }
    }

    /** Re-reads config.yml. Throws if it is invalid; the previous settings then stay active. */
    public void reload() {
        reloadConfig();
        Settings fresh = Settings.load(getConfig(), getLogger());
        xray.flushAll();
        settings = fresh;
        xray.onReload();
        freecam.onReload();
        if (hide != null && !fresh.hide.enabled) {
            hide.stop();
            hide = null;
        } else if (hide == null && fresh.hide.enabled) {
            startHide();
        } else if (hide != null) {
            hide.reload();
        }
    }

    /** Runs on the main thread; safe to call from the database thread. Ignored while the plugin is shutting down. */
    public void sync(Runnable r) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    public Settings settings() { return settings; }
    public Database database() { return database; }
    public ExemptManager exempt() { return exempt; }
    public Enforcement enforcement() { return enforcement; }
    public AlertManager alerts() { return alerts; }
    public EscalationManager escalation() { return escalation; }
    public FreecamModule freecam() { return freecam; }
    public XrayModule xray() { return xray; }
    public HideService hide() { return hide; }
}
