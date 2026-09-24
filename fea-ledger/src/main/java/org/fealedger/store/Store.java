package org.fealedger.store;

import org.fealedger.model.ImportExport;
import org.fealedger.model.Json;
import org.fealedger.model.Raw;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SQLite-backed, versioned store implementing:
 *  - append-only, immutable versions (raw payload + derived artifacts + diagnostics)
 *  - atomic current-pointer switch ("all-or-nothing" across process crashes)
 *  - append-only machine-readable event log with branch linkage
 *  - optimistic concurrency: a save based on a stale parent is refused
 *    (VersionConflictException) and must be rebased/merged
 *  - full export/import round trip that preserves identity and ordering
 *
 * Crash injection: {@link #setCrashHook(Consumer)} is invoked in the middle of
 * commitVersion while a transaction is open; if it kills the JVM, on restart
 * the reader observes either the old current version or a complete new one.
 */
public final class Store implements AutoCloseable {

    public static final String SCHEMA_VERSION = "3";

    private final String url;
    private Connection conn;

    /** Called after pending rows are written but before commit/pointer switch. */
    private volatile Consumer<String> crashHook;

    public Store(String path) {
        try {
            this.url = "jdbc:sqlite:" + path;
            this.conn = DriverManager.getConnection(url);
            this.conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            conn.commit();
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("cannot open store: " + path, e);
        }
    }

    public void setCrashHook(Consumer<String> hook) { this.crashHook = hook; }

    private void migrate() throws SQLException {
        int userVersion;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            userVersion = rs.getInt(1);
        }
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS versions(
                  vid TEXT PRIMARY KEY,
                  parent_vid TEXT,
                  branch TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  author TEXT NOT NULL,
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  payload TEXT NOT NULL,
                  payload_hash TEXT NOT NULL,
                  message TEXT NOT NULL,
                  complete INTEGER NOT NULL DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS current_pointer(
                  branch TEXT PRIMARY KEY,
                  vid TEXT NOT NULL)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  eid TEXT PRIMARY KEY,
                  branch TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  version_id TEXT,
                  parent_event TEXT,
                  type TEXT NOT NULL,
                  actor TEXT NOT NULL,
                  at TEXT NOT NULL DEFAULT (datetime('now')),
                  payload TEXT NOT NULL,
                  accepted INTEGER NOT NULL,
                  superseded_by TEXT)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS artifacts(
                  version_id TEXT NOT NULL,
                  key TEXT NOT NULL,
                  json TEXT NOT NULL,
                  hash TEXT NOT NULL,
                  deps TEXT NOT NULL,
                  PRIMARY KEY(version_id, key))""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS diagnostics(
                  version_id TEXT NOT NULL,
                  code TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  status TEXT NOT NULL,
                  object_id TEXT,
                  message TEXT NOT NULL,
                  detail TEXT,
                  seq INTEGER NOT NULL)""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS decisions(
                  version_id TEXT NOT NULL,
                  did TEXT NOT NULL,
                  question TEXT NOT NULL,
                  status TEXT NOT NULL,
                  chosen TEXT,
                  PRIMARY KEY(version_id, did))""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS candidates(
                  version_id TEXT NOT NULL,
                  did TEXT NOT NULL,
                  candidate_id TEXT NOT NULL,
                  json TEXT NOT NULL,
                  adopted INTEGER NOT NULL,
                  PRIMARY KEY(version_id, did, candidate_id))""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_branch ON events(branch, seq)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_diag_version ON diagnostics(version_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_art_version ON artifacts(version_id)");
        }
        if (userVersion < Integer.parseInt(SCHEMA_VERSION)) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
        conn.commit();
    }

    public int schemaVersion() {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ------------------------------------------------------------------
    // Version management
    // ------------------------------------------------------------------

    public List<Map<String,Object>> listVersions() {
        List<Map<String,Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT vid,parent_vid,branch,seq,author,created_at,payload_hash,message,complete "
              + "FROM versions ORDER BY branch, seq")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LinkedHashMap<String,Object> m = new LinkedHashMap<>();
                    m.put("vid", rs.getString(1));
                    m.put("parentVid", rs.getString(2));
                    m.put("branch", rs.getString(3));
                    m.put("seq", rs.getInt(4));
                    m.put("author", rs.getString(5));
                    m.put("createdAt", rs.getString(6));
                    m.put("payloadHash", rs.getString(7));
                    m.put("message", rs.getString(8));
                    m.put("complete", rs.getInt(9) == 1);
                    m.put("current", rs.getString(1).equals(currentVid(rs.getString(3))));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public String currentVid(String branch) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT vid FROM current_pointer WHERE branch=?")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String currentVid() { return currentVid("main"); }

    public List<String> branches() {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT branch FROM current_pointer ORDER BY branch")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public String rawPayload(String vid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT payload FROM versions WHERE vid=? AND complete=1")) {
            ps.setString(1, vid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Raw.Project projectAt(String vid) {
        String json = rawPayload(vid);
        return json == null ? null : ImportExport.importProject(json).project;
    }

    public Map<String,Object> versionMeta(String vid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT vid,parent_vid,branch,seq,author,created_at,payload_hash,message FROM versions WHERE vid=?")) {
            ps.setString(1, vid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                LinkedHashMap<String,Object> m = new LinkedHashMap<>();
                m.put("vid", rs.getString(1)); m.put("parentVid", rs.getString(2));
                m.put("branch", rs.getString(3)); m.put("seq", rs.getInt(4));
                m.put("author", rs.getString(5)); m.put("createdAt", rs.getString(6));
                m.put("payloadHash", rs.getString(7)); m.put("message", rs.getString(8));
                return m;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static final class Commit {
        public String branch = "main";
        public String parentVid;
        public String author = "engineer";
        public String message = "";
        public String payloadJson;
        /** Precomputed derived artifacts: key -> {json, hash, deps:[..]} */
        public final LinkedHashMap<String, LinkedHashMap<String,Object>> artifacts = new LinkedHashMap<>();
        public final List<LinkedHashMap<String,Object>> diagnostics = new ArrayList<>();
        public final List<LinkedHashMap<String,Object>> events = new ArrayList<>();
        public final List<LinkedHashMap<String,Object>> decisions = new ArrayList<>();
        public final List<LinkedHashMap<String,Object>> candidates = new ArrayList<>();
        /** Switch this branch pointer when true. */
        public boolean switchPointer = true;
    }

    /**
     * Atomically write a new version. Concurrency rule:
     *  - parentVid must equal the branch's current vid (or be null when the
     *    branch is empty), otherwise VersionConflictException.
     * Crash rule: crashHook runs after rows are inserted but before commit; a
     * killed process rolls back automatically (SQLite transaction), so after
     * restart either the old pointer or a fully-complete new pointer is seen.
     */
    public String commitVersion(Commit c) {
        try {
            conn.setAutoCommit(false);
            String branch = c.branch == null ? "main" : c.branch;
            String current = currentVid(branch);
            boolean fresh = current == null && c.parentVid == null;
            if (!fresh && !java.util.Objects.equals(current, c.parentVid)) {
                conn.rollback();
                throw new VersionConflictException(current, c.parentVid);
            }
            int seq = branchSeq(branch) + 1;
            String hash = Hashing.sha256(c.payloadJson).substring(0, 12);
            String vid = branch + "-v" + seq + "-" + hash;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO versions(vid,parent_vid,branch,seq,author,payload,payload_hash,message,complete) "
                  + "VALUES(?,?,?,?,?,?,?,?,0)")) {
                ps.setString(1, vid); ps.setString(2, c.parentVid);
                ps.setString(3, branch); ps.setInt(4, seq);
                ps.setString(5, c.author); ps.setString(6, c.payloadJson);
                ps.setString(7, hash); ps.setString(8, c.message);
                ps.executeUpdate();
            }
            writeArtifacts(vid, c);
            writeDiagnostics(vid, c);
            writeDecisions(vid, c);
            writeCandidates(vid, c);
            int baseSeq = eventSeq();
            String lastEvent = null;
            for (int k = 0; k < c.events.size(); k++) {
                LinkedHashMap<String,Object> ev = c.events.get(k);
                String eid = "e" + (baseSeq + k + 1) + "-"
                        + Hashing.hash12(branch + ":" + ev.getOrDefault("type", "?") + ":" + k + ":" + Json.write(ev.get("payload")));
                lastEvent = insertEvent(eid, branch, baseSeq + k + 1, vid, lastEvent, ev);
            }

            Consumer<String> hook = crashHook;
            if (hook != null) hook.accept(vid);

            if (c.switchPointer) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO current_pointer(branch,vid) VALUES(?,?) "
                      + "ON CONFLICT(branch) DO UPDATE SET vid=excluded.vid")) {
                    ps.setString(1, branch); ps.setString(2, vid); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE versions SET complete=1 WHERE vid=?")) {
                ps.setString(1, vid); ps.executeUpdate();
            }
            conn.commit();
            return vid;
        } catch (RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(false); } catch (SQLException ignored) {}
        }
    }

