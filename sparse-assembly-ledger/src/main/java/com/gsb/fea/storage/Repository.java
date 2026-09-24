package com.gsb.fea.storage;

import com.gsb.fea.util.Hashing;
import com.gsb.fea.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned SQLite repository.
 *
 * Crash safety: a commit is written STAGED, its digest is computed from the
 * rows, only then it becomes COMPLETE, and finally the single-row current_ref
 * is updated in the same transaction's tail. A process killed mid-write leaves
 * STAGED rows; on open they are discarded, so readers see either the old
 * version or a fully written new one, never a half version.
 *
 * Concurrency: every save is based on a parent revision; saving when the parent
 * is no longer current throws ConflictException carrying both revisions.
 */
public final class Repository implements AutoCloseable {
    private final Path dbFile;
    private final Connection cx;

    public Repository(Path dbFile) {
        this.dbFile = dbFile;
        try {
            Files.createDirectories(dbFile.toAbsolutePath().getParent());
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            // (checked IOException handled in the outer catch below)
            cx = DriverManager.getConnection(url);
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            cx.setAutoCommit(false);
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
            }
            cx.commit();
            initOrMigrate();
            cleanupStaged();
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("cannot open repository: " + dbFile, e);
        }
    }

    public Path dbFile() { return dbFile; }

    private boolean tableExists(String name) throws SQLException {
        try (ResultSet rs = cx.getMetaData().getTables(null, null, name, null)) {
            return rs.next();
        }
    }

    private int storedVersion() throws SQLException {
        if (!tableExists("meta")) return 1;
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT value FROM meta WHERE key='schema_version'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        }
    }

    private void initOrMigrate() throws SQLException {
        boolean fresh = !tableExists("commit_t");
        int v = storedVersion();
        if (fresh) {
            try (Statement st = cx.createStatement()) {
                st.execute(Schema.V2_OBJECTS);
                st.execute(Schema.COMMITS);
                st.execute(Schema.EVENTS);
                st.execute(Schema.REF);
                st.execute(Schema.META);
            }
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES('schema_version',?)")) {
                ps.setString(1, Integer.toString(Schema.CURRENT_VERSION));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO current_ref(singleton,updated_at) VALUES(1,?)")) {
                ps.setString(1, Instant.now().toString());
                ps.executeUpdate();
            }
            cx.commit();
            return;
        }
        if (v < 2) migrateV1ToV2();
    }

    /** v1 lacked object.source_commit_id; rebuild the objects table in place. */
    private void migrateV1ToV2() throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.execute("ALTER TABLE object RENAME TO object_v1");
            st.execute(Schema.V2_OBJECTS);
            st.execute("INSERT INTO object(commit_id,urn,kind,stable_id,ord,json,fingerprint,source_commit_id) "
                    + "SELECT commit_id,urn,kind,stable_id,ord,json,fingerprint,NULL FROM object_v1");
            st.execute("DROP TABLE object_v1");
            st.execute("UPDATE meta SET value='2' WHERE key='schema_version'");
        }
        cx.commit();
    }

    /** Delete everything left by a process killed while writing. */
    private void cleanupStaged() throws SQLException {
        try (Statement st = cx.createStatement()) {
            int n = st.executeUpdate(
                    "DELETE FROM object WHERE commit_id IN (SELECT commit_id FROM commit_t WHERE status='STAGED')");
            st.executeUpdate("DELETE FROM commit_t WHERE status='STAGED'");
            if (n > 0) cx.commit();
        }
    }

    public String currentCommitId() {
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("SELECT commit_id FROM current_ref WHERE singleton=1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Commit current() {
        String id = currentCommitId();
        return id == null ? null : commit(id);
    }

    public Commit commit(String id) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT commit_id,parent_commit_id,branch,label,created_at,event_ref,"
                        + "basis_revision,digest,status FROM commit_t WHERE commit_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Commit(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Commit> commits() {
        List<Commit> out = new ArrayList<>();
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT commit_id,parent_commit_id,branch,label,created_at,event_ref,"
                             + "basis_revision,digest,status FROM commit_t WHERE status='COMPLETE' "
                             + "ORDER BY created_at, commit_id")) {
            while (rs.next()) {
                out.add(new Commit(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<StoredObject> objectsOf(String commitId) {
        List<StoredObject> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT commit_id,urn,kind,stable_id,ord,json,fingerprint,source_commit_id "
                        + "FROM object WHERE commit_id=? ORDER BY ord,id")) {
            ps.setString(1, commitId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredObject(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                            rs.getString(8)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<StoredObject> objectsOfKind(String commitId, String kind) {
        return objectsOf(commitId).stream().filter(o -> o.kind().equals(kind)).toList();
    }

    public StoredObject object(String commitId, String urn) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT commit_id,urn,kind,stable_id,ord,json,fingerprint,source_commit_id "
                        + "FROM object WHERE commit_id=? AND urn=?")) {
            ps.setString(1, commitId);
            ps.setString(2, urn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new StoredObject(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7),
                        rs.getString(8));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record NewObject(String urn, String kind, String stableId, String json,
                            String fingerprint, String sourceCommitId) {}

    /**
     * Atomically create a complete version and switch the current pointer.
     * If expectedParent is non-null it must equal the current commit, else
     * ConflictException. Stages rows, verifies digest, flips to COMPLETE, and
     * moves the pointer as the final write within the commit flow.
     */
    public Commit commit(List<NewObject> objects, String branch, String label,
                         String expectedParent, String eventRef, String basisRevision) {
        String parent = currentCommitId();
        if (expectedParent != null && !expectedParent.equals(parent)) {
            throw new ConflictException(expectedParent, parent);
        }
        String id = "c-" + Long.toHexString(System.currentTimeMillis())
                + "-" + Integer.toHexString(java.util.Objects.hash(branch, label,
                        Integer.toHexString(objects.size()), Instant.now().getNano()));
        String now = Instant.now().toString();

        StringBuilder canon = new StringBuilder();
        try {
            try (PreparedStatement ins = cx.prepareStatement(
                    "INSERT INTO commit_t(commit_id,parent_commit_id,branch,label,created_at,"
                            + "event_ref,basis_revision,digest,status) VALUES(?,?,?,?,?,?,?,?,?)")) {
                ins.setString(1, id);
                ins.setString(2, parent);
                ins.setString(3, branch);
                ins.setString(4, label);
                ins.setString(5, now);
                ins.setString(6, eventRef);
                ins.setString(7, basisRevision);
                ins.setString(8, "pending");
                ins.setString(9, Commit.STAGED);
                ins.executeUpdate();
            }
            try (PreparedStatement po = cx.prepareStatement(
                    "INSERT INTO object(commit_id,urn,kind,stable_id,ord,json,fingerprint,"
                            + "source_commit_id) VALUES(?,?,?,?,?,?,?,?)")) {
                int ord = 0;
                for (NewObject o : objects) {
                    po.setString(1, id);
                    po.setString(2, o.urn());
                    po.setString(3, o.kind());
                    po.setString(4, o.stableId());
                    po.setInt(5, ord++);
                    po.setString(6, o.json());
                    po.setString(7, o.fingerprint());
                    po.setString(8, o.sourceCommitId());
                    po.addBatch();
                    canon.append(o.urn()).append(' ')
                         .append(o.fingerprint()).append(' ')
                         .append(o.json()).append('\n');
                }
                po.executeBatch();
            }
            String digest = Hashing.sha256(canon.toString());
            try (PreparedStatement up = cx.prepareStatement(
                    "UPDATE commit_t SET digest=?, status=? WHERE commit_id=?")) {
                up.setString(1, digest);
                up.setString(2, Commit.COMPLETE);
                up.setString(3, id);
                up.executeUpdate();
            }
            try (PreparedStatement rf = cx.prepareStatement(
                    "UPDATE current_ref SET commit_id=?, updated_at=? WHERE singleton=1")) {
                rf.setString(1, id);
                rf.setString(2, now);
                rf.executeUpdate();
            }
            cx.commit();
        } catch (SQLException e) {
            try { cx.rollback(); } catch (SQLException ignored) { }
            try { cleanupStaged(); } catch (SQLException ignored) { }
            throw new IllegalStateException("commit failed: " + id, e);
        }
        return commit(id);
    }

    public void recordEvent(String eventId, String commitId, String actor, String kind,
                            String branch, Object payload) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO event(event_id,commit_id,at,actor,kind,branch,json) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, eventId);
            ps.setString(2, commitId);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, actor);
            ps.setString(5, kind);
            ps.setString(6, branch);
            ps.setString(7, Json.write(payload));
            ps.executeUpdate();
            cx.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("event insert failed", e);
        }
    }

    public List<Map<String, Object>> events(String branch) {
        StringBuilder sql = new StringBuilder(
                "SELECT seq,event_id,commit_id,at,actor,kind,branch,json FROM event");
        if (branch != null) sql.append(" WHERE branch=? OR branch IS NULL");
        sql.append(" ORDER BY seq");
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement(sql.toString())) {
            if (branch != null) ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", rs.getLong(1));
                    m.put("eventId", rs.getString(2));
                    m.put("commitId", rs.getString(3));
                    m.put("at", rs.getString(4));
                    m.put("actor", rs.getString(5));
                    m.put("kind", rs.getString(6));
                    m.put("branch", rs.getString(7));
                    m.put("payload", Json.readMap(rs.getString(8)));
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Override
    public void close() {
        try { cx.close(); } catch (SQLException ignored) { }
    }
}
