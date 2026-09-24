package gsb.fea.store;

import gsb.fea.model.ConstraintSet;
import gsb.fea.model.DofMapping;
import gsb.fea.model.Element;
import gsb.fea.model.LocalMatrix;
import gsb.fea.model.Node;
import gsb.fea.model.ProjectVersion;
import gsb.fea.model.Renumber;
import gsb.fea.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, append-only store. Every derivation is written completely inside one
 * SQLite transaction and the current pointer is switched only after the full write
 * commits; a crash mid-write leaves either the old version or the complete new one.
 */
public final class Store implements AutoCloseable {

    /** Test/observability hook fired at named points inside commit paths. */
    public interface CrashHook {
        void on(String point);
    }

    private final Db db;
    private final CrashHook crashHook;

    private Store(Db db, CrashHook crashHook) {
        this.db = db;
        this.crashHook = crashHook;
    }

    public static Store open(Path path) {
        return new Store(Db.open(path.toString()), null);
    }

    public static Store open(Path path, CrashHook hook) {
        return new Store(Db.open(path.toString()), hook);
    }

    public Db db() {
        return db;
    }

    private void hook(String point) {
        if (crashHook != null) {
            crashHook.on(point);
        }
    }

    // ---------------------------------------------------------------- versions

    public String currentVersionId() {
        return db.scalar("SELECT value FROM meta WHERE key='current_version'");
    }

    public ProjectVersion currentVersion() {
        String id = currentVersionId();
        return id == null ? null : version(id);
    }

    public List<Records.VersionMeta> versions() {
        List<Records.VersionMeta> out = new ArrayList<>();
        for (Db.Row row : db.query(
                "SELECT id,parent_id,label,author,created_at,edit_summary,status"
                        + " FROM versions ORDER BY created_at, id")) {
            Records.VersionMeta meta = new Records.VersionMeta();
            meta.id = row.text(0);
            meta.parentId = row.text(1);
            meta.label = row.text(2);
            meta.author = row.text(3);
            meta.createdAtMillis = row.number(4);
            meta.editSummary = row.text(5);
            meta.status = row.text(6);
            out.add(meta);
        }
        return out;
    }

    public ProjectVersion version(String id) {
        List<Db.Row> rows = db.query(
                "SELECT parent_id,label,author,created_at,edit_summary FROM versions WHERE id=?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Db.Row head = rows.get(0);
        ProjectVersion version = new ProjectVersion(id, head.text(0), head.text(1), head.number(3));
        version.author = head.text(2);
        version.editSummary = head.text(4);

        for (Db.Row row : db.query("SELECT json FROM nodes WHERE version_id=? ORDER BY uid", id)) {
            Node node = Json.read(row.text(0), Node.class);
            version.nodes.put(node.uid, node);
        }
        for (Db.Row row : db.query("SELECT json FROM elements WHERE version_id=? ORDER BY uid", id)) {
            Element element = Json.read(row.text(0), Element.class);
            version.elements.put(element.uid, element);
        }
        for (Db.Row row : db.query("SELECT json FROM matrices WHERE version_id=? ORDER BY uid", id)) {
            LocalMatrix matrix = Json.read(row.text(0), LocalMatrix.class);
            version.matrices.put(matrix.uid, matrix);
        }
        for (Db.Row row : db.query("SELECT revision,json FROM mappings WHERE version_id=? ORDER BY revision", id)) {
            version.mappings.put(row.text(0), Json.read(row.text(1), DofMapping.class));
        }
        for (Db.Row row : db.query("SELECT revision,json FROM constraint_sets WHERE version_id=? ORDER BY revision", id)) {
            version.constraintSets.put(row.text(0), Json.read(row.text(1), ConstraintSet.class));
        }
        for (Db.Row row : db.query("SELECT revision,json FROM renumbers WHERE version_id=? ORDER BY revision", id)) {
            version.renumbers.put(row.text(0), Json.read(row.text(1), Renumber.class));
        }
        List<Db.Row> selection = db.query(
                "SELECT mapping_revision,constraint_revision,renumber_revision FROM selections WHERE version_id=?", id);
        if (!selection.isEmpty()) {
            version.selectedMappingRevision = selection.get(0).text(0);
            version.selectedConstraintRevision = selection.get(0).text(1);
            version.selectedRenumberRevision = selection.get(0).text(2);
        }
        return version;
    }

    /**
     * Saves a new immutable version and switches the current pointer, atomically.
     *
     * @param expectedParent optimistic-concurrency token: must equal the current pointer,
     *                       otherwise a resolvable conflict is raised and nothing is written.
     */
    public String saveVersion(ProjectVersion version, String expectedParent,
                              String eventType, String actor, String rationale,
                              Map<String, Object> payload) {
        db.begin();
        try {
            String current = currentVersionId();
            if (expectedParent != null && current != null && !expectedParent.equals(current)) {
                throw new ConflictException(expectedParent, current);
            }
            insertVersionHead(version);
            hook("after-version-rows");
            db.update("UPDATE versions SET status='COMPLETE' WHERE id=?", version.id);
            db.update("INSERT OR REPLACE INTO meta(key,value) VALUES('current_version',?)", version.id);
            appendEvent(version.id, eventType, actor, rationale, payload);
            db.commit();
            hook("after-commit");
            return version.id;
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    /**
     * Saves a version as a retained branch without moving the current pointer.
     * Used when a concurrent save loses the optimistic-concurrency race: the branch
     * stays fully available for later comparison and resolution.
     */
    public String saveBranch(ProjectVersion version, String eventType, String actor,
                             String rationale, Map<String, Object> payload) {
        db.begin();
        try {
            insertVersionHead(version);
            hook("after-version-rows");
            db.update("UPDATE versions SET status='COMPLETE' WHERE id=?", version.id);
            appendEvent(version.id, eventType, actor, rationale, payload);
            db.commit();
            hook("after-commit");
            return version.id;
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    private void insertVersionHead(ProjectVersion version) {
        db.update("INSERT INTO versions(id,parent_id,label,author,created_at,edit_summary,status)"
                        + " VALUES(?,?,?,?,?,?,?)",
                version.id, version.parentId, version.label, version.author,
                version.createdAtMillis, version.editSummary, "WRITING");
        insertVersionRows(version);
    }

    /** Moves the current pointer to an existing version (history checkout), with an event. */
    public void checkout(String versionId, String actor, String rationale) {
        if (version(versionId) == null) {
            throw new IllegalArgumentException("unknown version " + versionId);
        }
        db.begin();
        try {
            db.update("INSERT OR REPLACE INTO meta(key,value) VALUES('current_version',?)", versionId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkedOutVersion", versionId);
            appendEvent(versionId, "CHECKOUT", actor, rationale, payload);
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    // ------------------------------------------------------------- derivations

    public String saveDerivation(Records.DerivationRecord record) {
        db.begin();
        try {
            String eventId = nextEventId();
            record.eventId = eventId;
            db.update("INSERT INTO derivations(id,version_id,kind,config_signature,input_hashes,"
                            + "result_json,created_at,event_id) VALUES(?,?,?,?,?,?,?,?)",
                    record.id, record.versionId, record.kind, record.configSignature,
                    Json.write(record.inputHashes), record.resultJson,
                    record.createdAtMillis, eventId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("derivationId", record.id);
            payload.put("configSignature", record.configSignature);
            appendEvent(record.versionId, "ASSEMBLY_DERIVED", record.kind,
                    "deterministic assembler", payload);
            db.commit();
            return record.id;
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    public Records.DerivationRecord derivation(String id) {
        List<Db.Row> rows = db.query(
                "SELECT id,version_id,kind,config_signature,input_hashes,result_json,created_at,event_id"
                        + " FROM derivations WHERE id=?", id);
        if (rows.isEmpty()) {
            return null;
        }
        return derivationFrom(rows.get(0));
    }

    public List<Records.DerivationRecord> derivations() {
        List<Records.DerivationRecord> out = new ArrayList<>();
        for (Db.Row row : db.query(
                "SELECT id,version_id,kind,config_signature,input_hashes,result_json,created_at,event_id"
                        + " FROM derivations ORDER BY created_at,id")) {
            out.add(derivationFrom(row));
        }
        return out;
    }

    public List<Records.DerivationRecord> derivationsFor(String versionId) {
        List<Records.DerivationRecord> out = new ArrayList<>();
        for (Db.Row row : db.query(
                "SELECT id,version_id,kind,config_signature,input_hashes,result_json,created_at,event_id"
                        + " FROM derivations WHERE version_id=? ORDER BY created_at,id", versionId)) {
            out.add(derivationFrom(row));
        }
        return out;
    }

    private Records.DerivationRecord derivationFrom(Db.Row row) {
        Records.DerivationRecord record = new Records.DerivationRecord();
        record.id = row.text(0);
        record.versionId = row.text(1);
        record.kind = row.text(2);
        record.configSignature = row.text(3);
        record.inputHashes = Json.read(row.text(4),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {
                });
        record.resultJson = row.text(5);
        record.createdAtMillis = row.number(6);
        record.eventId = row.text(7);
        return record;
    }

    // ------------------------------------------------------------------ events

    public String appendEvent(String versionId, String type, String actor,
                              String rationale, Map<String, Object> payload) {
        long seq = db.scalar("SELECT MAX(seq) FROM events") == null
                ? 1L
                : Long.parseLong(db.scalar("SELECT MAX(seq) FROM events")) + 1L;
        String id = String.format("EV-%04d", seq);
        db.update("INSERT INTO events(id,seq,version_id,type,actor,rationale,payload_json,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                id, seq, versionId, type, actor, rationale,
                payload == null ? null : Json.write(payload), System.currentTimeMillis());
        return id;
    }

    public String appendEventCommitted(String versionId, String type, String actor,
                                       String rationale, Map<String, Object> payload) {
        db.begin();
        try {
            String id = appendEvent(versionId, type, actor, rationale, payload);
            db.commit();
            return id;
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    private String nextEventId() {
        long seq = db.scalar("SELECT MAX(seq) FROM events") == null
                ? 1L
                : Long.parseLong(db.scalar("SELECT MAX(seq) FROM events")) + 1L;
        return String.format("EV-%04d", seq);
    }

    public List<Records.EventRecord> events(String versionId) {
        List<Records.EventRecord> out = new ArrayList<>();
        String sql = "SELECT id,seq,version_id,type,actor,rationale,payload_json,created_at FROM events";
        List<Db.Row> rows = versionId == null
                ? db.query(sql + " ORDER BY seq")
                : db.query(sql + " WHERE version_id=? ORDER BY seq", versionId);
        for (Db.Row row : rows) {
            Records.EventRecord event = new Records.EventRecord();
            event.id = row.text(0);
            event.seq = row.number(1);
            event.versionId = row.text(2);
            event.type = row.text(3);
            event.actor = row.text(4);
            event.rationale = row.text(5);
            event.payloadJson = row.text(6);
            event.createdAtMillis = row.number(7);
            out.add(event);
        }
        return out;
    }

    // --------------------------------------------------------------- decisions

    public void saveDecision(String decisionId, String versionId, String decisionType,
                             String actor, String chosen, List<String> rejectedCandidates,
                             String rationale, Map<String, Object> payload) {
        db.begin();
        try {
            String eventId = appendEvent(versionId, "DECISION_" + decisionType, actor, rationale, payload);
            db.update("INSERT INTO decisions(id,event_id,version_id,decision_type,actor,chosen,"
                            + "rejected_json,rationale,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    decisionId, eventId, versionId, decisionType, actor, chosen,
                    Json.write(rejectedCandidates), rationale, System.currentTimeMillis());
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    public List<Map<String, Object>> decisions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Db.Row row : db.query("SELECT id,event_id,version_id,decision_type,actor,chosen,"
                + "rejected_json,rationale,created_at FROM decisions ORDER BY created_at,id")) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("id", row.text(0));
            decision.put("eventId", row.text(1));
            decision.put("versionId", row.text(2));
            decision.put("decisionType", row.text(3));
            decision.put("actor", row.text(4));
            decision.put("chosen", row.text(5));
            decision.put("rejected", Json.read(row.text(6), List.class));
            decision.put("rationale", row.text(7));
            decision.put("createdAtMillis", row.number(8));
            out.add(decision);
        }
        return out;
    }

    // --------------------------------------------------------------- conflicts

    public String recordConflict(Records.ConflictRecord record) {
        db.begin();
        try {
            db.update("INSERT INTO conflicts(id,base_version_id,version_a,version_b,actor_a,actor_b,"
                            + "state,resolved_version_id,resolved_by,created_at,resolved_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    record.id, record.baseVersionId, record.versionAId, record.versionBId,
                    record.actorA, record.actorB, record.state, record.resolvedVersionId,
                    record.resolvedBy, record.createdAtMillis, record.resolvedAtMillis);
            appendEvent(record.baseVersionId, "CONFLICT_DETECTED", record.actorA + "/" + record.actorB,
                    "two editors saved from the same base version " + record.baseVersionId
                            + "; branches " + record.versionAId + " and " + record.versionBId
                            + " retained", null);
            db.commit();
            return record.id;
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    public List<Records.ConflictRecord> conflicts() {
        List<Records.ConflictRecord> out = new ArrayList<>();
        for (Db.Row row : db.query(
                "SELECT id,base_version_id,version_a,version_b,actor_a,actor_b,state,"
                        + "resolved_version_id,resolved_by,created_at,resolved_at"
                        + " FROM conflicts ORDER BY created_at,id")) {
            Records.ConflictRecord conflict = new Records.ConflictRecord();
            conflict.id = row.text(0);
            conflict.baseVersionId = row.text(1);
            conflict.versionAId = row.text(2);
            conflict.versionBId = row.text(3);
            conflict.actorA = row.text(4);
            conflict.actorB = row.text(5);
            conflict.state = row.text(6);
            conflict.resolvedVersionId = row.text(7);
            conflict.resolvedBy = row.text(8);
            conflict.createdAtMillis = row.number(9);
            conflict.resolvedAtMillis = row.values.get(10) == null ? null : row.number(10);
            out.add(conflict);
        }
        return out;
    }

    public void resolveConflict(String conflictId, String chosenVersionId, String actor) {
        List<Db.Row> rows = db.query("SELECT base_version_id,version_a,version_b,state"
                + " FROM conflicts WHERE id=?", conflictId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown conflict " + conflictId);
        }
        Db.Row row = rows.get(0);
        if (!"OPEN".equals(row.text(3))) {
            throw new IllegalStateException("conflict " + conflictId + " already resolved");
        }
        String chosen = chosenVersionId;
        if (!row.text(1).equals(chosen) && !row.text(2).equals(chosen)) {
            throw new IllegalArgumentException("chosen version must be one of the conflict branches");
        }
        db.begin();
        try {
            db.update("UPDATE conflicts SET state='RESOLVED',resolved_version_id=?,resolved_by=?,"
                            + "resolved_at=? WHERE id=?",
                    chosen, actor, System.currentTimeMillis(), conflictId);
            db.update("INSERT OR REPLACE INTO meta(key,value) VALUES('current_version',?)", chosen);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conflictId", conflictId);
            payload.put("chosenVersion", chosen);
            payload.put("unchosenVersion", row.text(1).equals(chosen) ? row.text(2) : row.text(1));
            appendEvent(row.text(0), "CONFLICT_RESOLVED", actor,
                    "chose " + chosen + "; the other branch remains available, nothing was overwritten",
                    payload);
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    // ------------------------------------------------------------ export/import

    /** Full-fidelity archive: identities, ordering and provenance survive a round trip. */
    public byte[] exportArchive() {
        Map<String, Object> archive = new LinkedHashMap<>();
        archive.put("archiveType", "gsb.fea.archive");
        archive.put("schemaVersion", Db.SCHEMA_VERSION);
        archive.put("exportedAtMillis", System.currentTimeMillis());
        archive.put("currentVersionId", currentVersionId());

        List<Map<String, Object>> versions = new ArrayList<>();
        for (Records.VersionMeta meta : versions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("meta", meta);
            entry.put("version", version(meta.id));
            versions.add(entry);
        }
        archive.put("versions", versions);
        archive.put("derivations", derivations());
        archive.put("events", events(null));
        archive.put("conflicts", conflicts());

        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Db.Row row : db.query("SELECT id,event_id,version_id,decision_type,actor,chosen,"
                + "rejected_json,rationale,created_at FROM decisions ORDER BY created_at,id")) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("id", row.text(0));
            decision.put("eventId", row.text(1));
            decision.put("versionId", row.text(2));
            decision.put("decisionType", row.text(3));
            decision.put("actor", row.text(4));
            decision.put("chosen", row.text(5));
            decision.put("rejected", Json.read(row.text(6), List.class));
            decision.put("rationale", row.text(7));
            decision.put("createdAtMillis", row.number(8));
            decisions.add(decision);
        }
        archive.put("decisions", decisions);
        return Json.writeBytes(archive);
    }

    /** Replaces the whole database with the archive content, preserving every identity. */
    @SuppressWarnings("unchecked")
    public void importArchive(byte[] bytes) {
        Map<String, Object> archive = Json.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
        if (!"gsb.fea.archive".equals(archive.get("archiveType"))) {
            throw new IllegalArgumentException("not a gsb.fea archive");
        }
        db.begin();
        try {
            for (String table : List.of("nodes", "elements", "matrices", "mappings",
                    "constraint_sets", "renumbers", "selections", "derivations",
                    "events", "decisions", "conflicts", "versions", "meta")) {
                db.update("DELETE FROM " + table);
            }
            List<Map<String, Object>> versions =
                    (List<Map<String, Object>>) archive.get("versions");
            for (Map<String, Object> entry : versions) {
                ProjectVersion version = Json.convert(entry.get("version"),
                        new com.fasterxml.jackson.core.type.TypeReference<ProjectVersion>() {
                        });
                Map<String, Object> meta = (Map<String, Object>) entry.get("meta");
                db.update("INSERT INTO versions(id,parent_id,label,author,created_at,edit_summary,status)"
                                + " VALUES(?,?,?,?,?,?,?)",
                        version.id, version.parentId, version.label, version.author,
                        asLong(meta.get("createdAtMillis")), version.editSummary, "COMPLETE");
                insertVersionRows(version);
            }
            List<Map<String, Object>> derivations =
                    (List<Map<String, Object>>) archive.get("derivations");
            for (Map<String, Object> item : derivations) {
                db.update("INSERT INTO derivations(id,version_id,kind,config_signature,input_hashes,"
                                + "result_json,created_at,event_id) VALUES(?,?,?,?,?,?,?,?)",
                        item.get("id"), item.get("versionId"), item.get("kind"),
                        item.get("configSignature"), Json.write(item.get("inputHashes")),
                        item.get("resultJson"), asLong(item.get("createdAtMillis")),
                        item.get("eventId"));
            }
            List<Map<String, Object>> events = (List<Map<String, Object>>) archive.get("events");
            for (Map<String, Object> item : events) {
                db.update("INSERT INTO events(id,seq,version_id,type,actor,rationale,payload_json,created_at)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                        item.get("id"), asLong(item.get("seq")), item.get("versionId"),
                        item.get("type"), item.get("actor"), item.get("rationale"),
                        item.get("payloadJson") == null ? null
                                : item.get("payloadJson") instanceof String stringPayload
                                ? stringPayload : Json.write(item.get("payloadJson")),
                        asLong(item.get("createdAtMillis")));
            }
            List<Map<String, Object>> decisions = (List<Map<String, Object>>) archive.get("decisions");
            for (Map<String, Object> item : decisions) {
                db.update("INSERT INTO decisions(id,event_id,version_id,decision_type,actor,chosen,"
                                + "rejected_json,rationale,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        item.get("id"), item.get("eventId"), item.get("versionId"),
                        item.get("decisionType"), item.get("actor"), item.get("chosen"),
                        Json.write(item.get("rejected")), item.get("rationale"),
                        asLong(item.get("createdAtMillis")));
            }
            List<Map<String, Object>> conflicts = (List<Map<String, Object>>) archive.get("conflicts");
            for (Map<String, Object> item : conflicts) {
                db.update("INSERT INTO conflicts(id,base_version_id,version_a,version_b,actor_a,actor_b,"
                                + "state,resolved_version_id,resolved_by,created_at,resolved_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        item.get("id"), item.get("baseVersionId"), item.get("versionAId"),
                        item.get("versionBId"), item.get("actorA"), item.get("actorB"),
                        item.get("state"), item.get("resolvedVersionId"), item.get("resolvedBy"),
                        asLong(item.get("createdAtMillis")),
                        item.get("resolvedAtMillis") == null ? null : asLong(item.get("resolvedAtMillis")));
            }
            db.update("INSERT OR REPLACE INTO meta(key,value) VALUES('current_version',?)",
                    String.valueOf(archive.get("currentVersionId")));
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    private void insertVersionRows(ProjectVersion version) {
        for (Node node : version.nodes.values()) {
            db.update("INSERT INTO nodes(version_id,uid,original_number,json) VALUES(?,?,?,?)",
                    version.id, node.uid, node.originalNumber, Json.write(node));
        }
        for (Element element : version.elements.values()) {
            db.update("INSERT INTO elements(version_id,uid,original_number,json) VALUES(?,?,?,?)",
                    version.id, element.uid, element.originalNumber, Json.write(element));
        }
        for (LocalMatrix matrix : version.matrices.values()) {
            db.update("INSERT INTO matrices(version_id,uid,element_id,revision,json) VALUES(?,?,?,?,?)",
                    version.id, matrix.uid, matrix.elementId, matrix.revision, Json.write(matrix));
        }
        for (Map.Entry<String, DofMapping> entry : version.mappings.entrySet()) {
            db.update("INSERT INTO mappings(version_id,revision,json) VALUES(?,?,?)",
                    version.id, entry.getKey(), Json.write(entry.getValue()));
        }
        for (Map.Entry<String, ConstraintSet> entry : version.constraintSets.entrySet()) {
            db.update("INSERT INTO constraint_sets(version_id,revision,json) VALUES(?,?,?)",
                    version.id, entry.getKey(), Json.write(entry.getValue()));
        }
        for (Map.Entry<String, Renumber> entry : version.renumbers.entrySet()) {
            db.update("INSERT INTO renumbers(version_id,revision,json) VALUES(?,?,?)",
                    version.id, entry.getKey(), Json.write(entry.getValue()));
        }
        db.update("INSERT INTO selections(version_id,mapping_revision,constraint_revision,renumber_revision)"
                        + " VALUES(?,?,?,?)",
                version.id, version.selectedMappingRevision,
                version.selectedConstraintRevision, version.selectedRenumberRevision);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    @Override
    public void close() {
        db.close();
    }
}
