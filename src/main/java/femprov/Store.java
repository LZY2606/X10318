package femprov;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned provenance store on SQLite.
 *  - every derivation is an independent immutable version row;
 *  - the current pointer switches only inside the same transaction that
 *    completes the version: after an interrupted write a restart sees
 *    either the old version or a complete new one, never a partial one;
 *  - two editors saving from the same base version produce a resolvable
 *    conflict; both candidates are retained;
 *  - all judgments are machine-readable events.
 */
public final class Store implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    public record Version(String id, String parentId, String kind, String label,
                          String payload, long seq) {}
    public record Event(String id, String versionId, long seq, String kind, String payload) {}
    public record Conflict(String id, String baseVersionId, String candidateA, String candidateB,
                           String resolvedByEvent) {}

    /** Thrown when a save's expected base is no longer current. */
    public static final class ConflictException extends RuntimeException {
        public final String conflictId;
        ConflictException(String conflictId) { super("conflict " + conflictId); this.conflictId = conflictId; }
    }

    private final Connection conn;
    private Runnable crashHook; // test-only: fires mid-transaction

    public Store(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = conn.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
            migrate();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static Store inMemory() { return new Store(":memory:"); }

    void setCrashHook(Runnable hook) { this.crashHook = hook; }

    private void migrate() throws Exception {
        int v;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT user_version FROM pragma_user_version")) { v = rs.getInt(1); }
        if (v == 0 || v == 1) {
            try (Statement s = conn.createStatement()) {
                if (v == 0) {
                    s.execute("CREATE TABLE IF NOT EXISTS versions("
                            + "id TEXT PRIMARY KEY, parent_id TEXT, kind TEXT, label TEXT,"
                            + " payload TEXT, seq INTEGER, complete INTEGER NOT NULL DEFAULT 0)");
                    s.execute("CREATE TABLE IF NOT EXISTS current_pointer("
                            + "id INTEGER PRIMARY KEY CHECK(id=1), version_id TEXT)");
                }
                // v1 -> v2: events, conflicts, artifacts
                s.execute("CREATE TABLE IF NOT EXISTS events("
                        + "id TEXT PRIMARY KEY, version_id TEXT, seq INTEGER, kind TEXT, payload TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS conflicts("
                        + "id TEXT PRIMARY KEY, base_version_id TEXT, candidate_a TEXT,"
                        + " candidate_b TEXT, resolved_by_event TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS artifacts("
                        + "key TEXT, version_id TEXT, payload TEXT, valid_reason TEXT, dirty INTEGER,"
                        + " PRIMARY KEY(key, version_id))");
                s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        }
    }

    public String currentVersionId() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT version_id FROM current_pointer WHERE id=1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Save a new version atomically. expectedBase must equal the current
     * pointer, otherwise a conflict is recorded and ConflictException thrown.
     * The pointer switches only after every row of the version is written.
     */
    public synchronized void saveVersion(Version v, List<Event> events, String expectedBase) {
        String cur = currentVersionId();
        if (expectedBase != null && (cur == null || !cur.equals(expectedBase))) {
            String cid = "conflict-" + v.id();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conflicts(id,base_version_id,candidate_a,candidate_b,resolved_by_event)"
                            + " VALUES(?,?,?,?,NULL)")) {
                ps.setString(1, cid);
                ps.setString(2, expectedBase);
                ps.setString(3, cur);
                ps.setString(4, v.id());
                ps.executeUpdate();
            } catch (Exception e) { throw new RuntimeException(e); }
            throw new ConflictException(cid);
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO versions(id,parent_id,kind,label,payload,seq,complete)"
                            + " VALUES(?,?,?,?,?,?,0)")) {
                ps.setString(1, v.id());
                ps.setString(2, v.parentId());
                ps.setString(3, v.kind());
                ps.setString(4, v.label());
                ps.setString(5, v.payload());
                ps.setLong(6, v.seq());
                ps.executeUpdate();
            }
            for (Event ev : events) insertEvent(ev);
            if (crashHook != null) crashHook.run(); // simulate interrupted write
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE versions SET complete=1 WHERE id=?")) {
                ps.setString(1, v.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO current_pointer(id,version_id) VALUES(1,?)"
                            + " ON CONFLICT(id) DO UPDATE SET version_id=excluded.version_id")) {
                ps.setString(1, v.id());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    private void insertEvent(Event ev) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(id,version_id,seq,kind,payload) VALUES(?,?,?,?,?)")) {
            ps.setString(1, ev.id());
            ps.setString(2, ev.versionId());
            ps.setLong(3, ev.seq());
            ps.setString(4, ev.kind());
            ps.setString(5, ev.payload());
            ps.executeUpdate();
        }
    }

    /** Resolve a conflict by choosing one candidate; the other stays stored. */
    public synchronized void resolveConflict(String conflictId, String chosenVersionId, Event resolutionEvent) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conflicts SET resolved_by_event=? WHERE id=? AND resolved_by_event IS NULL")) {
                ps.setString(1, resolutionEvent.id());
                ps.setString(2, conflictId);
                if (ps.executeUpdate() != 1) throw new IllegalStateException("conflict not open: " + conflictId);
            }
            insertEvent(resolutionEvent);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE current_pointer SET version_id=? WHERE id=1")) {
                ps.setString(1, chosenVersionId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (Exception ignored) {}
            throw new RuntimeException(e);
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    public Version version(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,parent_id,kind,label,payload,seq FROM versions WHERE id=? AND complete=1")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new Version(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getLong(6));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public List<Version> versions() {
        List<Version> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id,parent_id,kind,label,payload,seq FROM versions WHERE complete=1 ORDER BY seq")) {
            while (rs.next())
                out.add(new Version(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Event> events() {
        List<Event> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,version_id,seq,kind,payload FROM events ORDER BY seq")) {
            while (rs.next())
                out.add(new Event(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5)));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Conflict> conflicts() {
        List<Conflict> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id,base_version_id,candidate_a,candidate_b,resolved_by_event FROM conflicts")) {
            while (rs.next())
                out.add(new Conflict(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    public void putArtifact(String key, String versionId, String payload, String validReason) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO artifacts(key,version_id,payload,valid_reason,dirty) VALUES(?,?,?,?,0)"
                        + " ON CONFLICT(key,version_id) DO UPDATE SET payload=excluded.payload,"
                        + " valid_reason=excluded.valid_reason, dirty=0")) {
            ps.setString(1, key);
            ps.setString(2, versionId);
            ps.setString(3, payload);
            ps.setString(4, validReason);
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public Map<String, String> artifacts(String versionId) {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key,payload FROM artifacts WHERE version_id=? AND dirty=0 ORDER BY key")) {
            ps.setString(1, versionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) { throw new RuntimeException(e); }
        return out;
    }

    // ---- export / import: identity, order and provenance relations preserved
    public String exportJson() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("schemaVersion", SCHEMA_VERSION);
        dump.put("current", currentVersionId());
        dump.put("versions", versions());
        dump.put("events", events());
        dump.put("conflicts", conflicts());
        return Json.write(dump);
    }

    public static Store importJson(String path, String json) {
        Store st = new Store(path);
        var n = Json.read(json);
        for (var v : n.get("versions"))
            st.saveVersion(new Version(v.get("id").asText(), v.get("parentId").isNull() ? null : v.get("parentId").asText(),
                    v.get("kind").asText(), v.get("label").asText(), v.get("payload").asText(),
                    v.get("seq").asLong()), List.of(), null);
        // events and conflicts replayed in original seq order
        try {
            st.conn.setAutoCommit(false);
            for (var e : n.get("events"))
                st.insertEvent(new Event(e.get("id").asText(), e.get("versionId").asText(),
                        e.get("seq").asLong(), e.get("kind").asText(), e.get("payload").asText()));
            for (var c : n.get("conflicts"))
                try (PreparedStatement ps = st.conn.prepareStatement(
                        "INSERT INTO conflicts(id,base_version_id,candidate_a,candidate_b,resolved_by_event)"
                                + " VALUES(?,?,?,?,?)")) {
                    ps.setString(1, c.get("id").asText());
                    ps.setString(2, c.get("baseVersionId").asText());
                    ps.setString(3, c.get("candidateA").asText());
                    ps.setString(4, c.get("candidateB").asText());
                    if (c.get("resolvedByEvent").isNull()) ps.setNull(5, 0);
                    else ps.setString(5, c.get("resolvedByEvent").asText());
                    ps.executeUpdate();
                }
            st.conn.commit();
        } catch (Exception e) {
            try { st.conn.rollback(); } catch (Exception ignored) {}
            throw new RuntimeException(e);
        } finally {
            try { st.conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
        // restore pointer to exported current
        String cur = n.get("current").isNull() ? null : n.get("current").asText();
        if (cur != null)
            try (PreparedStatement ps = st.conn.prepareStatement(
                    "UPDATE current_pointer SET version_id=? WHERE id=1")) {
                ps.setString(1, cur);
                ps.executeUpdate();
            } catch (Exception e) { throw new RuntimeException(e); }
        return st;
    }

    @Override public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
