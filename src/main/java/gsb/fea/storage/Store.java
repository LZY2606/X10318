package gsb.fea.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gsb.fea.engine.Assembler;
import gsb.fea.engine.CaseBundle;
import gsb.fea.model.AssemblyResult;
import gsb.fea.model.CandidateSet;
import gsb.fea.model.CaseInput;
import gsb.fea.model.Decision;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Versioned SQLite storage with three guarantees:
 *
 * 1. Atomic pointer switch: a new derivation is written as status=PENDING and
 *    only flipped to COMPLETE (and the current pointer moved) inside one
 *    transaction. A crash mid-write leaves PENDING rows that recovery discards,
 *    so a process restart sees either the old version or a complete new one.
 * 2. Optimistic concurrency: saving requires the parent revision still to be
 *    the branch head; two editors from the same version produce a resolvable
 *    conflict.
 * 3. Stable identity: ids are physical/content based, preserved on export and
 *    re-import into a fresh database.
 */
public class Store implements AutoCloseable {

    public static final String SCHEMA_VERSION = "1";

    private final String url;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ReentrantLock> branchLocks = new ConcurrentHashMap<>();

    public Store(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        this.url = "jdbc:sqlite:" + path;
        try (Connection c = connect()) {
            initialize(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private ReentrantLock lock(String branchId) {
        return branchLocks.computeIfAbsent(branchId, k -> new ReentrantLock());
    }

    private void initialize(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL");
            st.executeUpdate("PRAGMA foreign_keys=ON");
        }
        recoverPending(c);
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS branch(
                        id TEXT PRIMARY KEY, name TEXT NOT NULL,
                        parent_branch_id TEXT, created_at TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS revision(
                        id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,
                        parent_revision_id TEXT, author TEXT NOT NULL,
                        kind TEXT NOT NULL, label TEXT NOT NULL,
                        source_json TEXT NOT NULL, source_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS version(
                        id TEXT PRIMARY KEY, revision_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL, candidates_json TEXT NOT NULL,
                        adopted_candidate_id TEXT, status TEXT NOT NULL,
                        created_at TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS branch_current(
                        branch_id TEXT PRIMARY KEY, revision_id TEXT NOT NULL,
                        version_id TEXT NOT NULL, event_id TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS event(
                        id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,
                        revision_id TEXT NOT NULL, version_id TEXT NOT NULL,
                        parent_event_id TEXT, type TEXT NOT NULL,
                        payload_json TEXT NOT NULL, author TEXT NOT NULL,
                        created_at TEXT NOT NULL, seq INTEGER NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS decision(
                        id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
                        chosen_candidate_id TEXT NOT NULL,
                        retained_candidate_ids TEXT NOT NULL,
                        rationale TEXT, author TEXT NOT NULL, created_at TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS experiment(
                        id TEXT PRIMARY KEY, scenario TEXT NOT NULL,
                        baseline_version_id TEXT NOT NULL,
                        direct_source_changed TEXT NOT NULL,
                        scope_json TEXT NOT NULL, accepted_version_id TEXT,
                        rejected_version_id TEXT, events_json TEXT NOT NULL,
                        created_at TEXT NOT NULL)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS migration_log(
                        from_version TEXT NOT NULL, to_version TEXT NOT NULL,
                        applied_at TEXT NOT NULL, note TEXT NOT NULL)""");
        }
        upsertMeta(c, "schema_version", SCHEMA_VERSION);
    }

    /**
     * On restart, any version left PENDING by a crashed writer is discarded
     * together with its revision/events that never became current. Readers
     * therefore observe only the previous complete version or a complete new
     * one.
     */
    private synchronized void recoverPending(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM version WHERE status='PENDING'");
            st.executeUpdate("DELETE FROM event WHERE version_id NOT IN (SELECT id FROM version)");
            st.executeUpdate("DELETE FROM revision WHERE id NOT IN "
                    + "(SELECT DISTINCT revision_id FROM version WHERE status='COMPLETE') "
                    + "AND parent_revision_id IS NOT NULL");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Result of a derivation commit. */
    public record CommitResult(RevisionRow revision, VersionRow version, EventRow event,
                               String previousRevisionId, CandidateSet candidateSet) {
    }

    /**
     * Derive and commit a new version atomically. {@code expectedParentRevisionId}
     * implements optimistic concurrency: when another writer already moved the
     * branch head, a {@link ConflictException} carries the current head so the
     * caller can merge/resolve instead of silently overwriting.
     */
    public CommitResult commitDerivation(String branchId, String author, String kind, String label,
                                         CaseInput input, String expectedParentRevisionId,
                                         String parentEventId, String eventType, String eventPayload) {
        lock(branchId).lock();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                String headRevision = headRevision(c, branchId);
                if (headRevision != null && expectedParentRevisionId != null
                        && !headRevision.equals(expectedParentRevisionId)) {
                    c.rollback();
                    throw new ConflictException(
                            "branch " + branchId + " advanced to " + headRevision
                                    + " since your edit started at " + expectedParentRevisionId,
                            headRevision);
                }
                CaseBundle bundle = CaseBundle.of(input);
                CandidateSet candidates = Assembler.assemble(bundle);

                String now = Instant.now().toString();
                String revisionId = "rev:" + bundle.sourceHash() + ":" + seq(c, branchId);
                String versionId = "ver:" + revisionId;
                String sourceJson = mapper.writeValueAsString(input);
                String candidatesJson = mapper.writeValueAsString(candidates);
                String revisionIdFinal = revisionId;
                RevisionRow revision = new RevisionRow(revisionId, branchId, headRevision, author,
                        kind, label, sourceJson, bundle.sourceHash(), now);
                VersionRow version = new VersionRow(versionId, revisionId, branchId, candidatesJson,
                        null, "PENDING", now);
                insertRevision(c, revision);
                insertVersion(c, version);

                String eventId = "evt:" + branchId + ":" + seq(c, branchId);
                EventRow event = new EventRow(eventId, branchId, revisionId, versionId,
                        parentEventId != null ? parentEventId : headEvent(c, branchId),
                        eventType, eventPayload == null ? "{}" : eventPayload,
                        author, now, nextSeq(c, branchId));
                insertEvent(c, event);

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO branch_current(branch_id,revision_id,version_id,event_id) "
                                + "VALUES(?,?,?,?) ON CONFLICT(branch_id) DO UPDATE SET "
                                + "revision_id=excluded.revision_id, version_id=excluded.version_id, "
                                + "event_id=excluded.event_id")) {
                    ps.setString(1, branchId);
                    ps.setString(2, revisionId);
                    ps.setString(3, versionId);
                    ps.setString(4, eventId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE version SET status='COMPLETE' WHERE id=?")) {
                    ps.setString(1, versionId);
                    ps.executeUpdate();
                }
                c.commit();
                return new CommitResult(revision, new VersionRow(versionId, revisionId, branchId,
                        candidatesJson, null, "COMPLETE", now), event, headRevision, candidates);
            } catch (ConflictException ce) {
                throw ce;
            } catch (Exception e) {
                c.rollback();
                throw new IllegalStateException("derivation commit failed; old version retained", e);
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            lock(branchId).unlock();
        }
    }

    /** Adopt one candidate; rejected candidates are retained on the same version. */
    public Decision decide(String branchId, String author, String versionId,
                           String chosenCandidateId, String rationale) {
        lock(branchId).lock();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                VersionRow version = getVersion(c, versionId);
                if (version == null || !version.branchId().equals(branchId)) {
                    throw new IllegalStateException("version not found on branch: " + versionId);
                }
                CandidateSet set = mapper.readValue(version.candidatesJson(), CandidateSet.class);
                if (set.candidates().stream().noneMatch(x -> x.id().equals(chosenCandidateId))) {
                    throw new IllegalArgumentException("not a legal candidate: " + chosenCandidateId);
                }
                List<String> retained = set.candidates().stream().map(AssemblyResult::id)
                        .filter(id -> !id.equals(chosenCandidateId)).toList();
                String now = Instant.now().toString();
                String parentEvent = headEvent(c, branchId);
                String eventId = "evt:" + branchId + ":" + seq(c, branchId);
                EventRow event = new EventRow(eventId, branchId, version.revisionId(), versionId,
                        parentEvent, "DECISION",
                        mapper.writeValueAsString(Map.of("chosen", chosenCandidateId,
                                "retained", retained, "rationale", rationale == null ? "" : rationale)),
                        author, now, nextSeq(c, branchId));
                insertEvent(c, event);
                String decisionId = "dec:" + eventId;
                Decision decision = new Decision(decisionId, eventId, chosenCandidateId, retained,
                        rationale, author, now);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO decision(id,event_id,chosen_candidate_id,retained_candidate_ids,"
                                + "rationale,author,created_at) VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, decisionId);
                    ps.setString(2, eventId);
                    ps.setString(3, chosenCandidateId);
                    ps.setString(4, mapper.writeValueAsString(retained));
                    ps.setString(5, rationale);
                    ps.setString(6, author);
                    ps.setString(7, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE version SET adopted_candidate_id=? WHERE id=?")) {
                    ps.setString(1, chosenCandidateId);
                    ps.setString(2, versionId);
                    ps.executeUpdate();
                }
                updateHeadEvent(c, branchId, eventId);
                c.commit();
                return decision;
            } catch (Exception e) {
                c.rollback();
                throw new IllegalStateException(e);
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            lock(branchId).unlock();
        }
    }

    public BranchRow createBranch(String branchId, String name, String parentBranchId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO branch(id,name,parent_branch_id,created_at) VALUES(?,?,?,?)")) {
            String now = Instant.now().toString();
            ps.setString(1, branchId);
            ps.setString(2, name);
            ps.setString(3, parentBranchId);
            ps.setString(4, now);
            ps.executeUpdate();
            return new BranchRow(branchId, name, parentBranchId, now);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<BranchRow> listBranches() {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM branch ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            List<BranchRow> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new BranchRow(rs.getString("id"), rs.getString("name"),
                        rs.getString("parent_branch_id"), rs.getString("created_at")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public CurrentView current(String branchId) {
        try (Connection c = connect()) {
            String revisionId;
            String versionId;
            String eventId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT revision_id,version_id,event_id FROM branch_current WHERE branch_id=?")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    revisionId = rs.getString(1);
                    versionId = rs.getString(2);
                    eventId = rs.getString(3);
                }
            }
            return new CurrentView(getRevision(c, revisionId), getVersion(c, versionId), getEvent(c, eventId));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CurrentView(RevisionRow revision, VersionRow version, EventRow event) {
    }

    public RevisionRow getRevision(String id) {
        try (Connection c = connect()) {
            return getRevision(c, id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public VersionRow getVersion(String id) {
        try (Connection c = connect()) {
            return getVersion(c, id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public EventRow getEvent(String id) {
        try (Connection c = connect()) {
            return getEvent(c, id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<EventRow> events(String branchId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM event WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                List<EventRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readEvent(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<RevisionRow> revisions(String branchId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM revision WHERE branch_id=? ORDER BY created_at")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RevisionRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRevision(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public CaseInput inputOf(RevisionRow revision) {
        try {
            return mapper.readValue(revision.sourceJson(), CaseInput.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public CandidateSet candidatesOf(VersionRow version) {
        try {
            return mapper.readValue(version.candidatesJson(), CandidateSet.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void saveExperiment(ExperimentRow row) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO experiment(id,scenario,baseline_version_id,"
                             + "direct_source_changed,scope_json,accepted_version_id,"
                             + "rejected_version_id,events_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, row.id());
            ps.setString(2, row.scenario());
            ps.setString(3, row.baselineVersionId());
            ps.setString(4, row.directSourceChanged());
            ps.setString(5, row.scopeJson());
            ps.setString(6, row.acceptedVersionId());
            ps.setString(7, row.rejectedVersionId());
            ps.setString(8, row.eventsJson());
            ps.setString(9, row.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<ExperimentRow> experiments() {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM experiment ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            List<ExperimentRow> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new ExperimentRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String headRevision(Connection c, String branchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revision_id FROM branch_current WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String headEvent(Connection c, String branchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id FROM branch_current WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void updateHeadEvent(Connection c, String branchId, String eventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE branch_current SET event_id=? WHERE branch_id=?")) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.executeUpdate();
        }
    }

    private int seq(Connection c, String branchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(seq),0) FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int nextSeq(Connection c, String branchId) throws SQLException {
        return seq(c, branchId) + 1;
    }

    private void upsertMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void insertRevision(Connection c, RevisionRow r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO revision(id,branch_id,parent_revision_id,author,kind,label,"
                        + "source_json,source_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.id());
            ps.setString(2, r.branchId());
            ps.setString(3, r.parentRevisionId());
            ps.setString(4, r.author());
            ps.setString(5, r.kind());
            ps.setString(6, r.label());
            ps.setString(7, r.sourceJson());
            ps.setString(8, r.sourceHash());
            ps.setString(9, r.createdAt());
            ps.executeUpdate();
        }
    }

    private void insertVersion(Connection c, VersionRow v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO version(id,revision_id,branch_id,candidates_json,"
                        + "adopted_candidate_id,status,created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, v.id());
            ps.setString(2, v.revisionId());
            ps.setString(3, v.branchId());
            ps.setString(4, v.candidatesJson());
            ps.setString(5, v.adoptedCandidateId());
            ps.setString(6, v.status());
            ps.setString(7, v.createdAt());
            ps.executeUpdate();
        }
    }

    private void insertEvent(Connection c, EventRow e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO event(id,branch_id,revision_id,version_id,parent_event_id,"
                        + "type,payload_json,author,created_at,seq) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.id());
            ps.setString(2, e.branchId());
            ps.setString(3, e.revisionId());
            ps.setString(4, e.versionId());
            ps.setString(5, e.parentEventId());
            ps.setString(6, e.type());
            ps.setString(7, e.payloadJson());
            ps.setString(8, e.author());
            ps.setString(9, e.createdAt());
            ps.setString(10, e.seq());
            ps.executeUpdate();
        }
    }

    private RevisionRow getRevision(Connection c, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM revision WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRevision(rs) : null;
            }
        }
    }

    private RevisionRow readRevision(ResultSet rs) throws SQLException {
        return new RevisionRow(rs.getString("id"), rs.getString("branch_id"),
                rs.getString("parent_revision_id"), rs.getString("author"), rs.getString("kind"),
                rs.getString("label"), rs.getString("source_json"), rs.getString("source_hash"),
                rs.getString("created_at"));
    }

    private VersionRow getVersion(Connection c, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM version WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new VersionRow(rs.getString("id"), rs.getString("revision_id"),
                        rs.getString("branch_id"), rs.getString("candidates_json"),
                        rs.getString("adopted_candidate_id"), rs.getString("status"),
                        rs.getString("created_at")) : null;
            }
        }
    }

    private EventRow getEvent(Connection c, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM event WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEvent(rs) : null;
            }
        }
    }

    private EventRow readEvent(ResultSet rs) throws SQLException {
        return new EventRow(rs.getString("id"), rs.getString("branch_id"), rs.getString("revision_id"),
                rs.getString("version_id"), rs.getString("parent_event_id"), rs.getString("type"),
                rs.getString("payload_json"), rs.getString("author"), rs.getString("created_at"),
                rs.getInt("seq"));
    }

    @Override
    public void close() {
        // DriverManager connections are opened and closed per operation.
    }
}
