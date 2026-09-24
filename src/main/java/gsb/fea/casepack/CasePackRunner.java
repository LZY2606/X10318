package gsb.fea.casepack;

import gsb.fea.assembly.Assembler;
import gsb.fea.assembly.AssemblyConfig;
import gsb.fea.assembly.AssemblyResult;
import gsb.fea.assembly.Diagnostic;
import gsb.fea.assembly.MatrixEntry;
import gsb.fea.assembly.SummationSpec;
import gsb.fea.graph.DependencyGraph;
import gsb.fea.model.ConstraintSet;
import gsb.fea.model.ProjectVersion;
import gsb.fea.store.ConflictException;
import gsb.fea.store.Records;
import gsb.fea.store.Store;
import gsb.fea.util.Json;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a case pack replay script against a store and verifies every machine-readable
 * expectation. The same runner backs the automated tests and the on-page replay.
 */
public final class CasePackRunner {

    public static final class StepReport {
        public int n;
        public String action;
        public String title;
        public String status = "OK";
        public List<String> failures = new ArrayList<>();
        public Map<String, Object> details = new LinkedHashMap<>();
    }

    public static final class ReplayReport {
        public List<StepReport> steps = new ArrayList<>();
        public boolean ok = true;
        public Map<String, Object> artifacts = new LinkedHashMap<>();

        public StepReport last() {
            return steps.get(steps.size() - 1);
        }
    }

    private final CasePack pack;
    private final Store store;
    private final Assembler assembler = new Assembler();
    private final ReplayReport report = new ReplayReport();
    private String conflictId;

    private CasePackRunner(CasePack pack, Store store) {
        this.pack = pack;
        this.store = store;
    }

    public static ReplayReport run(CasePack pack, Store store) {
        CasePackRunner runner = new CasePackRunner(pack, store);
        for (CasePack.ReplayStep step : pack.replay) {
            runner.execute(step);
        }
        return runner.report;
    }

    private void execute(CasePack.ReplayStep step) {
        StepReport stepReport = new StepReport();
        stepReport.n = step.n;
        stepReport.action = step.action;
        stepReport.title = step.title;
        report.steps.add(stepReport);
        try {
            switch (step.action) {
                case "LOAD" -> doLoad(step, stepReport);
                case "SAVE" -> doSave(step, stepReport);
                case "ASSEMBLE" -> doAssemble(step, stepReport);
                case "ANALYZE" -> doAnalyze(step, stepReport);
                case "DECIDE" -> doDecide(step, stepReport);
                case "RESOLVE" -> doResolve(step, stepReport);
                case "EXPORT_IMPORT" -> doExportImport(step, stepReport);
                case "CHECKOUT" -> doCheckout(step, stepReport);
                default -> fail(stepReport, "unknown action " + step.action);
            }
        } catch (RuntimeException e) {
            fail(stepReport, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!stepReport.failures.isEmpty()) {
            stepReport.status = "FAIL";
            report.ok = false;
        }
    }

    private void doLoad(CasePack.ReplayStep step, StepReport out) {
        String target = step.version != null ? step.version : step.id;
        CasePack.VersionEntry entry = pack.versionEntry(target);
        if (entry == null) {
            fail(out, "no version entry " + step.version);
            return;
        }
        String parent = store.currentVersionId();
        store.saveVersion(entry.version, parent, entry.eventType, entry.actor,
                entry.rationale, Map.of("casepack", pack.packId, "step", step.n));
        out.details.put("version", target);
        out.details.put("current", store.currentVersionId());
        expectEquals(out, "version", step.expect.get("version"), store.currentVersionId());
        if (step.expect.containsKey("elements")) {
            ProjectVersion loaded = store.currentVersion();
            expectEquals(out, "elements", step.expect.get("elements"), loaded.elements.size());
        }
    }

    private void doSave(CasePack.ReplayStep step, StepReport out) {
        CasePack.VersionEntry entry = pack.versionEntry(step.version);
        try {
            store.saveVersion(entry.version, step.on, entry.eventType,
                    step.actor == null ? entry.actor : step.actor, entry.rationale,
                    Map.of("casepack", pack.packId, "step", step.n));
            out.details.put("saved", step.version);
            if (step.expect.containsKey("conflict")) {
                fail(out, "expected a conflict but save succeeded");
            }
        } catch (ConflictException conflict) {
            store.saveBranch(entry.version, entry.eventType,
                    step.actor == null ? entry.actor : step.actor,
                    entry.rationale + " (retained as unchosen branch)",
                    Map.of("casepack", pack.packId, "step", step.n, "branch", true));
            Records.ConflictRecord record = new Records.ConflictRecord();
            record.id = "CONFLICT-1";
            record.baseVersionId = conflict.baseVersionId;
            record.versionAId = store.currentVersionId();
            record.versionBId = step.version;
            record.actorA = "engineer-a";
            record.actorB = step.actor == null ? "engineer-b" : step.actor;
            record.state = "OPEN";
            record.createdAtMillis = System.currentTimeMillis();
            conflictId = store.recordConflict(record);
            out.details.put("conflictId", conflictId);
            out.details.put("branches", List.of(record.versionAId, record.versionBId));
            if (!step.expect.containsKey("conflict")) {
                fail(out, "unexpected conflict: " + conflict.getMessage());
            }
        }
    }

    private void doResolve(CasePack.ReplayStep step, StepReport out) {
        store.resolveConflict(conflictId, step.choose, "reviewer");
        out.details.put("chosen", step.choose);
        expectEquals(out, "chosen", step.expect.get("chosen"), store.currentVersionId());
        ProjectVersion unchosen = store.version((String) step.expect.get("unchosenRetained"));
        if (unchosen == null) {
            fail(out, "unchosen branch was not retained");
        } else {
            out.details.put("unchosenRetained", unchosen.id);
        }
    }

    private AssemblyResult doAssemble(CasePack.ReplayStep step, StepReport out) {
        ProjectVersion version = store.version(step.on);
        if (version == null) {
            fail(out, "unknown version " + step.on);
            return null;
        }
        AssemblyConfig config = new AssemblyConfig();
        config.summation = SummationSpec.valueOf(step.summation);
        if (step.strategy != null) {
            config.resolvedStrategy = ConstraintSet.Strategy.valueOf(step.strategy);
        }
        config.renumberRevision = step.renumber;
        config.constraintRevision = step.constraintRevision;
        AssemblyResult result = assembler.assemble(version, config, step.id);

        Records.DerivationRecord record = new Records.DerivationRecord();
        record.id = step.id;
        record.versionId = version.id;
        record.kind = "ASSEMBLY";
        record.configSignature = config.signature();
        record.inputHashes = DependencyGraph.assemblyInputs(version);
        record.resultJson = Json.write(result);
        record.createdAtMillis = System.currentTimeMillis();
        store.saveDerivation(record);
        report.artifacts.put(step.id, version.id);

        out.details.put("derivation", step.id);
        out.details.put("entries", result.entries.size());
        out.details.put("diagnostics", result.diagnostics.stream().map(d -> d.code).toList());
        if (!result.legalCandidates.isEmpty()) {
            out.details.put("legalCandidates",
                    result.legalCandidates.stream().map(c -> c.label).toList());
        }
        verifyAssemblyExpectations(step, out, result);
        return result;
    }

    private void verifyAssemblyExpectations(CasePack.ReplayStep step, StepReport out,
                                            AssemblyResult result) {
        Map<String, Object> expect = step.expect;
        if (expect.containsKey("complete")) {
            expectEquals(out, "complete", expect.get("complete"), result.complete);
        }
        if (expect.containsKey("blocked")) {
            expectEquals(out, "blocked", expect.get("blocked"), result.blocked());
        }
        if (expect.containsKey("diagnosticsContains")) {
            List<String> codes = result.diagnostics.stream().map(d -> d.code).toList();
            for (String code : (List<String>) expect.get("diagnosticsContains")) {
                if (!codes.contains(code)) {
                    fail(out, "missing diagnostic " + code + " (have " + codes + ")");
                }
            }
        }
        if (expect.containsKey("candidates")) {
            expectEquals(out, "candidates", expect.get("candidates"), result.legalCandidates.size());
        }
        if (expect.containsKey("renumbered")) {
            boolean renumbered = result.physicalToDisplayed.entrySet().stream()
                    .anyMatch(e -> !e.getKey().equals(e.getValue()));
            expectEquals(out, "renumbered", expect.get("renumbered"), renumbered);
        }
        if (expect.containsKey("constraintEffect")) {
            String uid = (String) expect.get("constraintEffect");
            boolean found = result.constraintEffects.stream()
                    .anyMatch(effect -> effect.constraintUid.equals(uid));
            if (!found) {
                fail(out, "no constraint effect for " + uid);
            }
        }
        if (expect.containsKey("entry")) {
            Map<String, Object> spec = (Map<String, Object>) expect.get("entry");
            MatrixEntry entry = findEntry(result, (String) spec.get("rowDof"),
                    (String) spec.get("colDof"));
            if (entry == null) {
                fail(out, "entry not found " + spec);
            } else {
                if (spec.containsKey("double")) {
                    double expected = ((Number) spec.get("double")).doubleValue();
                    if (Double.compare(expected, entry.doubleValue) != 0) {
                        fail(out, "entry double " + entry.doubleValue + " != " + expected);
                    }
                }
                if (spec.containsKey("exact")) {
                    String expected = (String) spec.get("exact");
                    if (entry.exactValue == null
                            || entry.exactValue.compareTo(new BigDecimal(expected)) != 0) {
                        fail(out, "entry exact " + entry.exactValue + " != " + expected);
                    }
                }
            }
        }
        if (expect.containsKey("incompleteEntry")) {
            Map<String, Object> spec = (Map<String, Object>) expect.get("incompleteEntry");
            MatrixEntry entry = findEntry(result, (String) spec.get("rowDof"),
                    (String) spec.get("colDof"));
            if (entry == null || entry.numeric) {
                fail(out, "expected incomplete numeric entry at " + spec);
            }
        }
    }

    private MatrixEntry findEntry(AssemblyResult result, String rowDof, String colDof) {
        for (MatrixEntry entry : result.entries) {
            if (entry.globalRowDofId.equals(rowDof) && entry.globalColDofId.equals(colDof)) {
                return entry;
            }
        }
        return null;
    }

    private void doAnalyze(CasePack.ReplayStep step, StepReport out) {
        String[] parts = step.on.split("->");
        ProjectVersion before = store.version(parts[0]);
        ProjectVersion after = store.version(parts[1]);
        DependencyGraph.AffectedAnalysis analysis = DependencyGraph.analyze(
                DependencyGraph.assemblyInputs(before), before, after);
        out.details.put("changedEntities", analysis.changedEntities);
        out.details.put("affectedDofs", new ArrayList<>(analysis.affectedDofs));
        out.details.put("explanation", analysis.explain());
        for (String dof : (List<String>) step.expect.get("affectedDofs")) {
            if (!analysis.affectedDofs.contains(dof)) {
                fail(out, "dof " + dof + " should be affected");
            }
        }
        for (String dof : (List<String>) step.expect.get("notAffected")) {
            if (analysis.affectedDofs.contains(dof)) {
                fail(out, "dof " + dof + " should NOT be affected");
            }
        }
    }

    private void doDecide(CasePack.ReplayStep step, StepReport out) {
        if (!step.on.equals(store.currentVersionId())) {
            store.checkout(step.on, "reviewer",
                    "decision branches from the historical version it refers to");
        }
        ProjectVersion base = store.version(step.on);
        ConstraintSet conflicted = base.constraintSets.get("BC-4");
        ConstraintSet resolved = new ConstraintSet();
        resolved.revision = "BC-4-RESOLVED";
        List<String> rejected = new ArrayList<>();
        for (ConstraintSet.Constraint constraint : conflicted.constraints) {
            if (constraint.uid.equals(step.choose)) {
                resolved.constraints.add(constraint);
            } else {
                rejected.add(constraint.uid);
            }
        }
        if (!base.id.equals(store.currentVersionId())) {
            store.checkout(base.id, "reviewer",
                    "decision is taken on the dependency versions of the historical event");
        }
        ProjectVersion decided = FixtureData.copy(base, "v5", "decision-constraint-conflict",
                System.currentTimeMillis(),
                "human decision: keep " + step.choose + ", reject " + rejected);
        decided.constraintSets.put(resolved.revision, resolved);
        decided.selectedConstraintRevision = resolved.revision;
        store.saveVersion(decided, step.on, "DECISION_CONSTRAINT_CONFLICT", "reviewer",
                "constraint conflict resolved by keeping " + step.choose
                        + "; rejected candidates retained in BC-4",
                Map.of("chosen", step.choose, "rejected", rejected));
        store.saveDecision("DEC-1", decided.id, "CONSTRAINT_CONFLICT", "reviewer",
                step.choose, rejected,
                "both constraints target the same dof; exactly one must apply",
                Map.of("baseVersion", base.id));
        out.details.put("decision", step.choose);
        out.details.put("rejectedRetained", rejected);
        out.details.put("newVersion", decided.id);
        expectEquals(out, "decision", step.expect.get("decision"), step.choose);
    }

    private void doExportImport(CasePack.ReplayStep step, StepReport out) {
        byte[] exported = store.exportArchive();
        try {
            Path temp = Files.createTempDirectory("fea-reimport");
            try (Store reimported = Store.open(temp.resolve("reimported.db"))) {
                reimported.importArchive(exported);
                byte[] roundTrip = reimported.exportArchive();
                Map<?, ?> before = Json.read(new String(exported,
                        java.nio.charset.StandardCharsets.UTF_8), Map.class);
                Map<?, ?> after = Json.read(new String(roundTrip,
                        java.nio.charset.StandardCharsets.UTF_8), Map.class);
                boolean identical = withoutExportStamp(before).equals(withoutExportStamp(after));
                if (!identical) {
                    fail(out, "identity mismatch at " + firstDifference("",
                            withoutExportStamp(before), withoutExportStamp(after)));
                }
                expectEquals(out, "identityPreserved",
                        step.expect.get("identityPreserved"), identical);
                out.details.put("versions", reimported.versions().size());
                out.details.put("derivations", reimported.derivations().size());
                out.details.put("events", reimported.events(null).size());
                out.details.put("currentVersion", reimported.currentVersionId());
            }
        } catch (java.io.IOException e) {
            fail(out, "export/import failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static String firstDifference(String path, Object a, Object b) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            for (Map.Entry<?, ?> entry : ma.entrySet()) {
                if (!mb.containsKey(entry.getKey())) {
                    return path + "." + entry.getKey() + " missing after import";
                }
                Object av = entry.getValue();
                Object bv = mb.get(entry.getKey());
                if (!java.util.Objects.equals(av, bv)) {
                    return firstDifference(path + "." + entry.getKey(), av, bv);
                }
            }
            for (Object key : mb.keySet()) {
                if (!ma.containsKey(key)) {
                    return path + "." + key + " added after import";
                }
            }
            return path + " maps differ";
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return path + " list size " + la.size() + " vs " + lb.size();
            }
            for (int i = 0; i < la.size(); i++) {
                if (!java.util.Objects.equals(la.get(i), lb.get(i))) {
                    return firstDifference(path + "[" + i + "]", la.get(i), lb.get(i));
                }
            }
            return path + " lists differ";
        }
        return path + ": " + a + " (" + (a == null ? "null" : a.getClass().getSimpleName())
                + ") vs " + b + " (" + (b == null ? "null" : b.getClass().getSimpleName()) + ")";
    }

    private static Map<?, ?> withoutExportStamp(Map<?, ?> archive) {
        Map<Object, Object> copy = new LinkedHashMap<>(archive);
        copy.remove("exportedAtMillis");
        return copy;
    }

    private void doCheckout(CasePack.ReplayStep step, StepReport out) {
        store.checkout(step.version, "reviewer", "return to the dependency versions and "
                + "visible conclusions of that historical event");
        expectEquals(out, "version", step.expect.get("version"), store.currentVersionId());
    }

    private void expectEquals(StepReport out, String key, Object expected, Object actual) {
        if (expected == null) {
            return;
        }
        boolean equal = expected.equals(actual)
                || String.valueOf(expected).equals(String.valueOf(actual));
        if (!equal) {
            fail(out, key + ": expected " + expected + " but got " + actual);
        }
        out.details.put(key, actual);
    }

    private void fail(StepReport out, String message) {
        out.failures.add(message);
    }

}
