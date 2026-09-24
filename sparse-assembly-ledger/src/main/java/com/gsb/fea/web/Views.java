package com.gsb.fea.web;

import com.gsb.fea.storage.Commit;
import com.gsb.fea.storage.Repository;
import com.gsb.fea.storage.StoredObject;
import com.gsb.fea.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only view models built from one immutable commit's object set. */
final class Views {
    private Views() {}

    static Map<String, Object> overview(Repository repo, String commitId) {
        Commit c = repo.commit(commitId);
        if (c == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commit", commitView(c));
        StoredObject report = repo.object(commitId, "report:assembly");
        if (report != null) out.put("report", Json.readMap(report.json()));
        StoredObject bundle = firstOfKind(repo, commitId, "bundle");
        if (bundle != null) out.put("bundle", Json.readMap(bundle.json()));
        return out;
    }

    static Map<String, Object> commitView(Commit c) {
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

    static StoredObject firstOfKind(Repository repo, String commitId, String kind) {
        List<StoredObject> all = repo.objectsOfKind(commitId, kind);
        return all.isEmpty() ? null : all.get(0);
    }

    static Map<String, Object> mesh(Repository repo, String commitId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (StoredObject o : repo.objectsOfKind(commitId, "node")) {
            Map<String, Object> m = Json.readMap(o.json());
            m.put("urn", o.urn());
            nodes.add(m);
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (StoredObject o : repo.objectsOfKind(commitId, "element")) {
            Map<String, Object> m = Json.readMap(o.json());
            m.put("urn", o.urn());
            elements.add(m);
        }
        out.put("nodes", nodes);
        out.put("elements", elements);
        return out;
    }

    static Map<String, Object> sparsity(Repository repo, String commitId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (StoredObject o : repo.objectsOfKind(commitId, "candidate")) {
            candidates.add(Json.readMap(o.json()));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (StoredObject o : repo.objectsOfKind(commitId, "entry")) {
            entries.add(Json.readMap(o.json()));
        }
        out.put("candidates", candidates);
        out.put("entries", entries);
        return out;
    }

    static Map<String, Object> diagnostics(Repository repo, String commitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredObject o : repo.objectsOf(commitId)) {
            if (o.kind().equals("diagnostic") || o.kind().equals("candidate-diagnostic")) {
                Map<String, Object> m = Json.readMap(o.json());
                m.put("urn", o.urn());
                out.add(m);
            }
        }
        return Map.of("diagnostics", out);
    }

    static Map<String, Object> sourceObject(Repository repo, String commitId, String urn) {
        StoredObject o = repo.object(commitId, urn);
        if (o == null) return null;
        Map<String, Object> m = Json.readMap(o.json());
        m.put("urn", o.urn());
        m.put("kind", o.kind());
        m.put("stableId", o.stableId());
        m.put("fingerprint", o.fingerprint());
        m.put("sourceCommitId", o.sourceCommitId());
        return m;
    }

    static Map<String, Object> diff(Repository repo, String leftId, String rightId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("left", commitView(repo.commit(leftId)));
        out.put("right", commitView(repo.commit(rightId)));
        Map<String, Map<String, StoredObject>> l = byUrn(repo, leftId);
        Map<String, Map<String, StoredObject>> r = byUrn(repo, rightId);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map.Entry<String, Map<String, StoredObject>> e : r.entrySet()) {
            if (!l.containsKey(e.getKey())) added.add(diffItem("added", e.getKey(), null, e.getValue().get("self")));
        }
        for (Map.Entry<String, Map<String, StoredObject>> e : l.entrySet()) {
            if (!r.containsKey(e.getKey())) removed.add(diffItem("removed", e.getKey(), e.getValue().get("self"), null));
        }
        for (String urn : l.keySet()) {
            StoredObject lo = l.get(urn).get("self");
            StoredObject ro = r.get(urn) == null ? null : r.get(urn).get("self");
            if (ro != null && !lo.fingerprint().equals(ro.fingerprint())) {
                changed.add(diffItem("changed", urn, lo, ro));
            }
        }
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    private static Map<String, Object> diffItem(String kind, String urn,
                                                StoredObject lo, StoredObject ro) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change", kind);
        m.put("urn", urn);
        m.put("leftFingerprint", lo == null ? null : lo.fingerprint());
        m.put("rightFingerprint", ro == null ? null : ro.fingerprint());
        if (lo != null) m.put("left", Json.readMap(lo.json()));
        if (ro != null) m.put("right", Json.readMap(ro.json()));
        return m;
    }

    private static Map<String, Map<String, StoredObject>> byUrn(Repository repo,
                                                                String commitId) {
        Map<String, Map<String, StoredObject>> out = new LinkedHashMap<>();
        for (StoredObject o : repo.objectsOf(commitId)) {
            out.computeIfAbsent(o.urn(), k -> new LinkedHashMap<>()).put("self", o);
        }
        return out;
    }
}
