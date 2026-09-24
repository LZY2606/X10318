package com.femledger.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection holder. Handles schema creation, schema migrations and
 * crash recovery. Write protocol: a version row is inserted with
 * status='building' and only flipped to 'complete' inside the same
 * transaction that moves the current pointer; on open, any leftover
 * non-complete version is discarded, so after an interrupted write a reader
 * sees either the old version or a complete new one.
 */
public final class Db implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            Db db = new Db(c);
            db.migrate();
            db.recover();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open " + path, e);
        }
    }

    public Connection conn() { return conn; }

    private int schemaVersion() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            var rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'");
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void migrate() throws SQLException {
        int v = schemaVersion();
        if (v == SCHEMA_VERSION) return;
        if (v == 0) {
            try (Statement s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS versions(
                      id TEXT PRIMARY KEY, parent TEXT, label TEXT,
                      status TEXT NOT NULL, created INTEGER NOT NULL,
                      input_json TEXT)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS events(
                      seq INTEGER PRIMARY KEY AUTOINCREMENT,
                      version_id TEXT, type TEXT NOT NULL,
                      payload TEXT NOT NULL, created INTEGER NOT NULL)""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS derived(
                      version_id TEXT NOT NULL, key TEXT NOT NULL,
                      deps TEXT NOT NULL, reason TEXT NOT NULL,
                      payload TEXT NOT NULL,
                      PRIMARY KEY(version_id, key))""");
            }
            setMeta("schema_version", "1");
            v = 1;
        }
        if (v == 1) {
            // v1 -> v2: events gain a monotonic 'created' column and the
            // derived-artifact dependency table appears.
            try (Statement s = conn.createStatement()) {
                s.execute("""
                    CREATE TABLE IF NOT EXISTS derived(
                      version_id TEXT NOT NULL, key TEXT NOT NULL,
                      deps TEXT NOT NULL, reason TEXT NOT NULL,
                      payload TEXT NOT NULL,
                      PRIMARY KEY(version_id, key))""");
                var rs = s.executeQuery("PRAGMA table_info(events)");
                boolean hasCreated = false;
                while (rs.next()) if ("created".equals(rs.getString("name"))) hasCreated = true;
                if (!hasCreated) {
                    s.execute("ALTER TABLE events ADD COLUMN created INTEGER NOT NULL DEFAULT 0");
                }
            }
            setMeta("schema_version", "2");
            v = 2;
        }
        if (v != SCHEMA_VERSION) throw new IllegalStateException("unsupported schema " + v);
    }

    /** Discard versions interrupted mid-write, plus their events. */
    private void recover() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM events WHERE version_id IN (SELECT id FROM versions WHERE status <> 'complete')");
            s.execute("DELETE FROM versions WHERE status <> 'complete'");
        }
    }

    public String meta(String key) {
        try (var ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void setMeta(String key, String value) {
        try (var ps = conn.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
