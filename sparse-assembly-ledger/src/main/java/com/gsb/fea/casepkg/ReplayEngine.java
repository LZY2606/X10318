package com.gsb.fea.casepkg;

import com.gsb.fea.core.AssembleOptions;
import com.gsb.fea.model.SourceBundle;
import com.gsb.fea.storage.ConflictException;
import com.gsb.fea.storage.Repository;
import com.gsb.fea.util.Json;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the offline case package against a versioned repository. Every step
 * produces an independent immutable version (branch-aware) and a machine
 * readable event. Steps that disagree from a common base produce the required
 * resolvable optimistic-lock conflict; both candidates are retained.
 */
public final class ReplayEngine {
    public static final String CASE_RESOURCE = "/cases/sparse-assembly-ledger.json";

    private final Repository repo;
    private CaseFile caseFile;
    private final Map<String, SourceBundle> bundles = new LinkedHashMap<>();

    public ReplayEngine(Repository repo) {
        this.repo = repo;
    }

    @SuppressWarnings("unchecked")
    public CaseFile loadDefault() {
        try (InputStream in = ReplayEngine.class.getResourceAsStream(CASE_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing case resource: " + CASE_RESOURCE);
            Map<String, Object> raw = Json.read(in, Map.class);
            return loadMap(raw);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load case package", e);
        }
    }

    @SuppressWarnings("unchecked")
    public CaseFile loadMap(Map<String, Object> raw) {
        List<CaseStep> steps = new ArrayList<>();
        for (Object so : (List<Object>) raw.get("steps")) {
            Map<String, Object> m = (Map<String, Object>) so;
            steps.add(new CaseStep((String) m.get("id"), (String) m.get("kind"),
                    (String) m.get("title"), (String) m.get("narrative"),
                    (Map<String, Object>) m.get("params"),
                    strs(m.get("expectDiagnostics")),
                    (Map<String, Object>) m.get("expectState")));
        }
        CaseFile cf = new CaseFile((String) raw.get("caseId"), (String) raw.get("title"),
                (String) raw.get("summary"), (Map<String, Object>) raw.get("units"),
                bundlesOf(raw), steps, (Map<String, Object>) raw.get("expected"));
        this.caseFile = cf;
        for (Map.Entry<String, Map<String, Object>> e : bundleMaps(raw).entrySet()) {
            bundles.put(e.getKey(), Bundles.fromMap(e.getKey(), e.getValue()));
        }
        return cf;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bundlesOf(Map<String, Object> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : ((Map<String, Object>) raw.get("bundles")).values()) {
            out.add((Map<String, Object>) v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> bundleMaps(Map<String, Object> raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Object> bs = (Map<String, Object>) raw.get("bundles");
        for (Map.Entry<String, Object> e : bs.entrySet()) {
            out.put(e.getKey(), (Map<String, Object>) e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strs(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : l) out.add((String) x);
        return out;
    }

    public SourceBundle bundle(String id) {
        if (bundles.isEmpty()) loadDefault();
        SourceBundle b = bundles.get(id);
        if (b == null) throw new IllegalArgumentException("no bundle: " + id);
        return b;
    }

    public CaseFile caseFile() {
        if (caseFile == null) loadDefault();
        return caseFile;
    }

    public record StepOutcome(CaseStep step, String commitId, Map<String, Object> event,
                              List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    private String lastCommitForStep;

    public List<StepOutcome> replayAll() {
        if (caseFile == null) loadDefault();
        List<StepOutcome> outcomes = new ArrayList<>();
        for (CaseStep st : caseFile.steps()) outcomes.add(replayStep(st));
        return outcomes;
    }

    public StepOutcome replayStep(CaseStep st) {
        return switch (st.kind()) {
            case "IMPORT", "PARAM_REVISE", "SOURCE_PATCH", "PROBE" -> importStep(st);
            case "DECIDE" -> decideStep(st);
            case "REPLAY_END" -> endStep(st);
            default -> new StepOutcome(st, null, Map.of(),
                    List.of("unknown step kind: " + st.kind()));
        };
    }

    private StepOutcome importStep(CaseStep st) {
        String bundleId = (String) st.params().get("bundleId");
        String strategyName = (String) st.params().getOrDefault("strategy", "FREE");
        String branch = (String) st.params().getOrDefault("branch", "main");
        AssembleOptions.Strategy strategy =
                AssembleOptions.Strategy.valueOf(strategyName);
        AssembleOptions options = new AssembleOptions(strategy,
                AssembleOptions.FloatOrder.CANONICAL);
        SourceBundle bundle = bundle(bundleId);
        String parent = repo.currentCommitId();
        SnapshotBuilder.Result snap =
                SnapshotBuilder.build(bundle, options, parent, bundleId);
        com.gsb.fea.storage.Commit commit = repo.commit(snap.objects(), branch, st.title(),
                parent, st.id(), bundleId);
        String commitId = commit.commitId();
        Map<String, Object> payload = eventPayload(st, commitId, parent, bundleId,
                strategy, snap, Map.of());
        repo.recordEvent("ev-" + st.id(), commitId, actorOf(st), "IMPORT", branch, payload);
        lastCommitForStep = commitId;
        List<String> problems = verifyExpectations(st, snap);
        return new StepOutcome(st, commitId, payload, problems);
    }

    private StepOutcome decideStep(CaseStep st) {
        String branch = (String) st.params().getOrDefault("branch", "branch-decision");
        String base = repo.currentCommitId();
        // user A saves pick one from the shared base
        Map<String, Object> decisionA = Map.of(
                "pickedConstraint", st.params().get("firstPick"), "by", "reviewer-a",
                "resolution", "accept drawing value 0 mm");
        String commitA = repo.commit(List.of(), branch,
                "决定A：采纳图纸 0mm", base, st.id() + ":a", null).commitId();
        repo.recordEvent("ev-" + st.id() + ":a", commitA, "reviewer-a", "DECISION", branch,
                decisionA);
        // user B edits from the SAME base and saves -> resolvable conflict
        String conflictWith = null;
        String commitB = null;
        try {
            repo.commit(List.of(), branch, "决定B：采纳现场 2.5mm", base,
                    st.id() + ":b", null);
        } catch (ConflictException ce) {
            conflictWith = ce.actualCurrent();
        }
        // the conflicting edit is retained as an event (candidate not lost)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baseCommit", base);
        payload.put("acceptedCommit", commitA);
        payload.put("conflictExpectedCurrent", conflictWith);
        payload.put("resolvable", conflictWith != null);
        payload.put("retainedCandidate",
                Map.of("pickedConstraint", st.params().get("secondPick"),
                        "by", "reviewer-b", "resolution", "site RFI-17 2.5 mm"));
        repo.recordEvent("ev-" + st.id() + ":b-conflict", null, "reviewer-b",
                "DECISION_CONFLICT", branch, payload);
        boolean ok = Boolean.TRUE.equals(st.expectState().get("resolvable"))
                && conflictWith != null;
        return new StepOutcome(st, commitA, payload,
                ok ? List.of() : List.of("expected a resolvable optimistic-lock conflict"));
    }

    private StepOutcome endStep(CaseStep st) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", "narrative closed; all previous events remain inspectable");
        payload.put("currentCommit", repo.currentCommitId());
        repo.recordEvent("ev-" + st.id(), repo.currentCommitId(), "reviewer",
                "REPLAY_END", null, payload);
        return new StepOutcome(st, repo.currentCommitId(), payload, List.of());
    }

    private String actorOf(CaseStep st) {
        return switch (st.kind()) {
            case "PARAM_REVISE" -> "reviewer";
            case "SOURCE_PATCH" -> "engineer";
            default -> "engineering-team";
        };
    }

    private Map<String, Object> eventPayload(CaseStep st, String commitId, String parent,
                                             String bundleId,
                                             AssembleOptions.Strategy strategy,
                                             SnapshotBuilder.Result snap,
                                             Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("stepId", st.id());
        p.put("kind", st.kind());
        p.put("title", st.title());
        p.put("narrative", st.narrative());
        p.put("bundleId", bundleId);
        p.put("strategy", strategy.name());
        p.put("parentCommit", parent);
        p.put("commitId", commitId);
        p.put("dependencyGraph", Json.readMap(snap.graphJson()));
        p.put("changedObject", st.params().get("changedObject"));
        p.putAll(extra);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<String> verifyExpectations(CaseStep st, SnapshotBuilder.Result snap) {
        List<String> problems = new ArrayList<>();
        StoredObjectView report = findObject(snap, "report:assembly");
        if (report == null) {
            problems.add("missing report object");
            return problems;
        }
        Map<String, Object> r = report.map;
        List<String> codes = (List<String>) r.get("diagnosticCodes");
        for (String want : st.expectDiagnostics()) {
            if (!codes.contains(want)) {
                problems.add("step " + st.id() + ": expected diagnostic " + want
                        + " but got " + codes);
            }
        }
        Object cc = st.expectState().get("candidateCount");
        if (cc != null && ((Number) cc).intValue() != ((List<String>) r.get("candidateIds")).size()) {
            problems.add("step " + st.id() + ": candidateCount expected " + cc + " but was "
                    + ((List<String>) r.get("candidateIds")).size());
        }
        Object rej = st.expectState().get("rejected");
        if (rej != null && Boolean.TRUE.equals(rej) && !Boolean.TRUE.equals(r.get("rejected"))) {
            problems.add("step " + st.id() + ": expected rejected assembly");
        }
        if (st.expectState().containsKey("sensitivityEntry")) {
            String want = (String) st.expectState().get("sensitivityEntry");
            List<Map<String, Object>> sens = (List<Map<String, Object>>) r.get("sensitivity");
            boolean found = false;
            long minUlps = Long.MAX_VALUE;
            for (Map<String, Object> s : sens) {
                minUlps = Math.min(minUlps, ((Number) s.get("ulps")).longValue());
                if (want.equals(s.get("entryUrn")) && ((Number) s.get("ulps")).longValue() >= 1) {
                    found = true;
                }
            }
            if (!found) {
                problems.add("step " + st.id() + ": float-order sensitivity missing for "
                        + want + " (min ulps seen=" + (minUlps == Long.MAX_VALUE ? "none" : minUlps) + ")");
            }
        }
        return problems;
    }

    private StoredObjectView findObject(SnapshotBuilder.Result snap, String urn) {
        for (Repository.NewObject o : snap.objects()) {
            if (o.urn().equals(urn)) return new StoredObjectView(o.urn(), Json.readMap(o.json()));
        }
        return null;
    }

    private record StoredObjectView(String urn, Map<String, Object> map) {}
}
