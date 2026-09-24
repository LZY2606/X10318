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
 * Whole-database JSON archive and re-import. The archive is the ordered,
 * identity-bearing material needed to recreate the repository byte-for-byte at
 * the logical level: deleting the local database file and importing the archive
 * preserves every urn, stable id, ordinal, parent/provenance link and event.
 */
public final class Archive {
    private Archive() {}

    public static void exportTo(Repository repo, Path file) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "sparse-assembly-ledger-archive");
        doc.put("schemaVersion", Schema.CURRENT_VERSION);
        doc.put("exportedAt", Instant.now().toString());
        doc.put("currentCommitId", repo.currentCommitId());

        List<Map<String, Object>> commits = new ArrayList<>();
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Commit c : repo.commits()) {
            commits.add(toMap(c));
            for (StoredObject o : repo.objectsOf(c.commitId())) objects.add(toMap(o));
        }
        doc.put("commits", commits);
        doc.put("objects", objects);
        doc.put("events", repo.events(null));
        doc.put("digest", Hashing.sha256(Json.write(
                Map.of("commits", commits, "objects", objects, "events", repo.events(null)))));
        Json.write(file, doc);
    }

    public static void importFrom(Path archive, Path newDbFile) {
        Map<String, Object> doc = Json.readMap(archive);
        String digest = Hashing.sha256(Json.write(Map.of(
                "commits", doc.get("commits"),
                "objects", doc.get("objects"),
                "events", doc.get("events"))));
        if (!digest.equals(doc.get("digest"))) {
            throw new IllegalStateException("archive digest mismatch; refusing import");
        }
        try {
            Files.deleteIfExists(newDbFile);
            Files.deleteIfExists(Path.of(newDbFile + "-wal"));
            Files.deleteIfExists(Path.of(newDbFile + "-shm"));
        } catch (Exception ignored) { }

        try (Connection cx = DriverManager.getConnection(
                "jdbc:sqlite:" + newDbFile.toAbsolutePath())) {
            cx.setAutoCommit(false);
            try (Statement st = cx.createStatement()) {
                st.execute(Schema.V2_OBJECTS);
                st.execute(Schema.COMMITS);
                st.execute(Schema.EVENTS);
                st.execute(Schema.REF);
                st.execute(Schema.META);
            }
            int sv = ((Number) doc.getOrDefault("schemaVersion", 2)).intValue();
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES('schema_version',?)")) {
                ps.setString(1, Integer.toString(Math.max(sv, Schema.CURRENT_VERSION)));
                ps.executeUpdate();
            }
            for (Object co : (List<?>) doc.get("commits")) insertCommit(cx, (Map<?, ?>) co);
            for (Object ob : (List<?>) doc.get("objects")) insertObject(cx, (Map<?, ?>) ob);
            for (Object ev : (List<?>) doc.get("events")) insertEvent(cx, (Map<?, ?>) ev);
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO current_ref(singleton,commit_id,updated_at) VALUES(1,?,?)")) {
                ps.setString(1, (String) doc.get("currentCommitId"));
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
            cx.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("archive import failed", e);
        }
    }

    private static Map<String, Object> toMap(Commit c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commitId", c.commitId());
        m.put("parentCommitId", c.parentCommitId());
        m.put("branch", c.branch());
        m.put("label", c.label());
        m.put("createdAt", c.createdAt());
        m.put("eventRef", c.eventRef());
        m.put("basisRevision", c.basisRevision());
        m.put("digest", c.digest());
        m.put("status", c.status());
        return m;
    }

    private static Map<String, Object> toMap(StoredObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commitId", o.commitId());
        m.put("urn", o.urn());
        m.put("kind", o.kind());
        m.put("stableId", o.stableId());
        m.put("ord", o.ord());
        m.put("json", o.json());
        m.put("fingerprint", o.fingerprint());
        m.put("sourceCommitId", o.sourceCommitId());
        return m;
    }

    private static void insertCommit(Connection cx, Map<?, ?> m) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO commit_t(commit_id,parent_commit_id,branch,label,created_at,"
                        + "event_ref,basis_revision,digest,status) VALUES(?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, str(m.get("commitId")));
            ps.setString(i++, str(m.get("parentCommitId")));
            ps.setString(i++, str(m.get("branch")));
            ps.setString(i++, str(m.get("label")));
            ps.setString(i++, str(m.get("createdAt")));
            ps.setString(i++, str(m.get("eventRef")));
            ps.setString(i++, str(m.get("basisRevision")));
            ps.setString(i++, str(m.get("digest")));
            ps.setString(i, str(m.get("status")));
            ps.executeUpdate();
        }
    }

    private static void insertObject(Connection cx, Map<?, ?> m) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO object(commit_id,urn,kind,stable_id,ord,json,fingerprint,"
                        + "source_commit_id) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, str(m.get("commitId")));
            ps.setString(2, str(m.get("urn")));
            ps.setString(3, str(m.get("kind")));
            ps.setString(4, str(m.get("stableId")));
            ps.setInt(5, ((Number) m.get("ord")).intValue());
            ps.setString(6, str(m.get("json")));
            ps.setString(7, str(m.get("fingerprint")));
            ps.setString(8, str(m.get("sourceCommitId")));
            ps.executeUpdate();
        }
    }

    private static void insertEvent(Connection cx, Map<?, ?> m) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO event(seq,event_id,commit_id,at,actor,kind,branch,json) "
                        + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, ((Number) m.get("seq")).longValue());
            ps.setString(2, str(m.get("eventId")));
            ps.setString(3, str(m.get("commitId")));
            ps.setString(4, str(m.get("at")));
            ps.setString(5, str(m.get("actor")));
            ps.setString(6, str(m.get("kind")));
            ps.setString(7, str(m.get("branch")));
            Object p = m.get("payload");
            ps.setString(8, p instanceof String s ? s : Json.write(p));
            ps.executeUpdate();
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
