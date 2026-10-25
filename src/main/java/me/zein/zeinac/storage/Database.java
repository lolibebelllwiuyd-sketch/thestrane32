package me.zein.zeinac.storage;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.model.Alert;
import me.zein.zeinac.model.Confidence;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQLite storage. Every JDBC call runs on one dedicated daemon thread, never on the server thread.
 * If the driver or file is unavailable the plugin keeps running (detection + alerts still work, history is just not persisted).
 */
public final class Database {
    /** A unit of work executed on the database thread. */
    private interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    private final ZeinAC plugin;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZeinAC-DB");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private Connection conn; // only touched on the DB thread
    private volatile boolean healthy;
    private volatile long lastErrorLogMs;

    public Database(ZeinAC plugin) {
        this.plugin = plugin;
    }

    public boolean healthy() {
        return healthy;
    }

    public int queueSize() {
        return pending.get();
    }

    public CompletableFuture<Boolean> init(String fileName) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    File dir = plugin.getDataFolder();
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new SQLException("cannot create plugin data folder");
                    }
                    Class.forName("org.sqlite.JDBC");
                    conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, fileName).getAbsolutePath());
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA journal_mode=WAL");
                        st.execute("PRAGMA synchronous=NORMAL");
                        st.execute("PRAGMA busy_timeout=3000");
                        st.execute("CREATE TABLE IF NOT EXISTS alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,"
                                + " uuid TEXT NOT NULL, name TEXT NOT NULL, module TEXT NOT NULL, type TEXT NOT NULL,"
                                + " confidence TEXT NOT NULL, score REAL NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER, detail TEXT)");
                        st.execute("CREATE INDEX IF NOT EXISTS idx_alerts_uuid_ts ON alerts(uuid, ts)");
                        st.execute("CREATE INDEX IF NOT EXISTS idx_alerts_name ON alerts(name COLLATE NOCASE, ts)");
                        st.execute("CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts)");
                        st.execute("CREATE TABLE IF NOT EXISTS exempt(uuid TEXT PRIMARY KEY, name TEXT, added_by TEXT, ts INTEGER)");
                        st.execute("CREATE TABLE IF NOT EXISTS mining_stats(uuid TEXT NOT NULL, grp TEXT NOT NULL,"
                                + " counted INTEGER, hidden INTEGER, exposed INTEGER, branches INTEGER, hits INTEGER,"
                                + " updated INTEGER, PRIMARY KEY(uuid, grp))");
                        st.execute("CREATE TABLE IF NOT EXISTS baseline(grp TEXT PRIMARY KEY, counted INTEGER, hidden INTEGER)");
                    }
                    healthy = true;
                    f.complete(true);
                } catch (Throwable t) {
                    healthy = false;
                    closeQuietly();
                    plugin.getLogger().warning("Database unavailable, running without persistence: " + t);
                    f.complete(false);
                }
            });
        } catch (RejectedExecutionException e) {
            f.complete(false);
        }
        return f;
    }

    private <T> CompletableFuture<T> submit(String what, SqlTask<T> task, T fallback) {
        CompletableFuture<T> f = new CompletableFuture<>();
        pending.incrementAndGet();
        try {
            exec.execute(() -> {
                try {
                    if (conn == null) {
                        f.complete(fallback);
                    } else {
                        f.complete(task.run(conn));
                    }
                } catch (Throwable t) {
                    logError(what, t);
                    f.complete(fallback);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            f.complete(fallback);
        }
        return f;
    }

    private void logError(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMs > 60_000L) {
            lastErrorLogMs = now;
            plugin.getLogger().warning("Database error during '" + what + "': " + t);
        }
    }

    // ------------------------------------------------------------------ alerts
    public void insertAlert(Alert a) {
        submit("insertAlert", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO alerts(ts,uuid,name,module,type,confidence,score,world,x,y,z,detail)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, a.ts());
                ps.setString(2, a.uuid().toString());
                ps.setString(3, a.name());
                ps.setString(4, a.module());
                ps.setString(5, a.type());
                ps.setString(6, a.confidence().name());
                ps.setDouble(7, a.score());
                ps.setString(8, a.world());
                ps.setInt(9, a.x());
                ps.setInt(10, a.y());
                ps.setInt(11, a.z());
                ps.setString(12, a.detail());
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    /** Most recent alerts for a player name (case-insensitive). Never contacts Mojang. */
    public CompletableFuture<List<Alert>> queryAlertsByName(String name, int limit) {
        return submit("queryAlerts", c -> {
            List<Alert> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts,uuid,name,module,type,confidence,score,world,x,y,z,detail"
                    + " FROM alerts WHERE name = ? COLLATE NOCASE ORDER BY ts DESC LIMIT ?")) {
                ps.setString(1, name);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Alert(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                                rs.getString(5), Confidence.parse(rs.getString(6), Confidence.LOW), rs.getDouble(7),
                                rs.getString(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getString(12)));
                    }
                }
            }
            return out;
        }, new ArrayList<>());
    }

    // ------------------------------------------------------------------ exempt
    public CompletableFuture<Map<UUID, String>> loadExempt() {
        return submit("loadExempt", c -> {
            Map<UUID, String> out = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid,name FROM exempt")) {
                while (rs.next()) {
                    try {
                        out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                    } catch (IllegalArgumentException ignored) {
                        // skip corrupt row
                    }
                }
            }
            return out;
        }, new HashMap<>());
    }

    public void addExempt(UUID uuid, String name, String by) {
        submit("addExempt", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO exempt(uuid,name,added_by,ts) VALUES(?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, by);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    public void removeExempt(UUID uuid) {
        submit("removeExempt", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM exempt WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    // ------------------------------------------------------------------ mining stats (aggregated counters only)
    public CompletableFuture<Map<String, long[]>> loadStats(UUID uuid) {
        return submit("loadStats", c -> {
            Map<String, long[]> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT grp,counted,hidden,exposed,branches,hits FROM mining_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)});
                    }
                }
            }
            return out;
        }, null);
    }

    public void saveStats(UUID uuid, Map<String, long[]> stats) {
        submit("saveStats", c -> {
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mining_stats(uuid,grp,counted,hidden,exposed,branches,hits,updated)"
                    + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uuid,grp) DO UPDATE SET counted=excluded.counted,"
                    + " hidden=excluded.hidden, exposed=excluded.exposed, branches=excluded.branches, hits=excluded.hits,"
                    + " updated=excluded.updated")) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, long[]> e : stats.entrySet()) {
                    long[] v = e.getValue();
                    ps.setString(1, uuid.toString());
                    ps.setString(2, e.getKey());
                    ps.setLong(3, v[0]);
                    ps.setLong(4, v[1]);
                    ps.setLong(5, v[2]);
                    ps.setLong(6, v[3]);
                    ps.setLong(7, v[4]);
                    ps.setLong(8, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(oldAuto);
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    // ------------------------------------------------------------------ baseline
    public CompletableFuture<Map<String, long[]>> loadBaseline() {
        return submit("loadBaseline", c -> {
            Map<String, long[]> out = new HashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT grp,counted,hidden FROM baseline")) {
                while (rs.next()) {
                    out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
                }
            }
            return out;
        }, new HashMap<>());
    }

    public void saveBaseline(Map<String, long[]> data) {
        submit("saveBaseline", c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO baseline(grp,counted,hidden) VALUES(?,?,?)")) {
                for (Map.Entry<String, long[]> e : data.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setLong(2, e.getValue()[0]);
                    ps.setLong(3, e.getValue()[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    // ------------------------------------------------------------------ maintenance
    public void purge(long alertsOlderThanMs, long statsOlderThanMs) {
        submit("purge", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM alerts WHERE ts < ?")) {
                ps.setLong(1, alertsOlderThanMs);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mining_stats WHERE updated < ?")) {
                ps.setLong(1, statsOlderThanMs);
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    /** Finishes queued writes (bounded wait), then closes the connection. */
    public void close() {
        exec.execute(this::closeQuietly);
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database queue did not drain within 5s; some data may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException ignored) {
            // nothing useful to do
        }
        conn = null;
    }
}
