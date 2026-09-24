package gsb.fea.web;

import gsb.fea.assembly.Assembler;
import gsb.fea.assembly.AssemblyDiff;
import gsb.fea.assembly.AssemblyResult;
import gsb.fea.assembly.ContributionItem;
import gsb.fea.assembly.MatrixEntry;
import gsb.fea.casepack.CasePack;
import gsb.fea.casepack.CasePackLoader;
import gsb.fea.casepack.CasePackRunner;
import gsb.fea.graph.DependencyGraph;
import gsb.fea.model.ProjectVersion;
import gsb.fea.store.Records;
import gsb.fea.store.Store;
import gsb.fea.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** HTTP + SVG review surface over the versioned store. */
public final class WebServer {
    private final Store store;
    private final Assembler assembler = new Assembler();
    private final Javalin app;

    public WebServer(Store store) {
        this.store = store;
        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add("/web");
        });
        routes();
    }

    public void start(String host, int port) {
        app.start(host, port);
    }

    public void stop() {
        app.stop();
    }

    private void routes() {
        app.get("/api/state", this::state);
        app.get("/api/derivation/{id}", this::derivation);
        app.get("/api/derivation/{id}/entry/{row}/{col}", this::entryDetail);
        app.get("/api/diff", this::diff);
        app.get("/api/validity/{id}", this::validity);
        app.get("/svg/mesh", this::meshSvg);
        app.get("/svg/pattern", this::patternSvg);
        app.get("/svg/diff", this::diffSvg);
        app.post("/api/assemble", this::assemble);
        app.post("/api/decide", this::decide);
        app.post("/api/resolve", this::resolve);
        app.post("/api/checkout", this::checkout);
        app.post("/api/replay", this::replay);
        app.get("/api/export", this::export);
        app.post("/api/import", this::importArchive);
    }

    private void state(Context ctx) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentVersionId", store.currentVersionId());
        state.put("versions", store.versions());
        List<Map<String, Object>> derivations = new ArrayList<>();
        for (Records.DerivationRecord record : store.derivations()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.id);
            item.put("versionId", record.versionId);
            item.put("kind", record.kind);
            item.put("configSignature", record.configSignature);
            item.put("createdAtMillis", record.createdAtMillis);
            item.put("eventId", record.eventId);
            derivations.add(item);
        }
        state.put("derivations", derivations);
        state.put("events", store.events(null));
        state.put("conflicts", store.conflicts());
        state.put("decisions", store.decisions());
        CasePack pack = CasePackLoader.load();
        state.put("casepack", Map.of("packId", pack.packId, "title", pack.title,
                "narrative", pack.narrative, "steps", pack.replay.size()));
        ctx.json(state);
    }

    private AssemblyResult loadResult(String derivationId) {
        Records.DerivationRecord record = store.derivation(derivationId);
        if (record == null) {
            return null;
        }
        return Json.read(record.resultJson, AssemblyResult.class);
    }

    private void derivation(Context ctx) {
        AssemblyResult result = loadResult(ctx.pathParam("id"));
        if (result == null) {
            ctx.status(404).json(Map.of("error", "unknown derivation"));
            return;
        }
        ctx.json(result);
    }

    private void entryDetail(Context ctx) {
        AssemblyResult result = loadResult(ctx.pathParam("id"));
        if (result == null) {
            ctx.status(404).json(Map.of("error", "unknown derivation"));
            return;
        }
        int row = Integer.parseInt(ctx.pathParam("row"));
        int col = Integer.parseInt(ctx.pathParam("col"));
        Map<String, Object> detail = new LinkedHashMap<>();
        MatrixEntry found = null;
        for (MatrixEntry entry : result.entries) {
            if (entry.physicalRow == row && entry.physicalCol == col) {
                found = entry;
            }
        }
        if (found == null) {
            ctx.status(404).json(Map.of("error", "no entry at " + row + "," + col));
            return;
        }
        detail.put("entry", found);
        List<Map<String, Object>> stack = new ArrayList<>();
        for (ContributionItem item : result.contributions) {
            if (item.physicalRow == row && item.physicalCol == col) {
                Map<String, Object> layer = new LinkedHashMap<>();
                layer.put("uid", item.uid);
                layer.put("sumOrder", item.sumOrder);
                layer.put("element", item.elementId);
                layer.put("originalElementNumber", item.originalElementNumber);
                layer.put("localMatrix", item.localMatrixId);
                layer.put("localPosition", "(" + item.localRow + "," + item.localCol + ")");
                layer.put("localDofs", item.localRowDof + " / " + item.localColDof);
                layer.put("globalDofs", item.globalRowDofId + " / " + item.globalColDofId);
                layer.put("rawValue", item.rawValue);
                layer.put("unit", item.unit);
                layer.put("floatMode", item.floatMode);
                layer.put("exactValue", item.exactValue);
                layer.put("doubleValue", item.doubleValue);
                layer.put("sourceChain", item.sourceChain());
                stack.add(layer);
            }
        }
        detail.put("contributionStack", stack);
        detail.put("summationMode", found.summationMode);
        ctx.json(detail);
    }

    private void diff(Context ctx) {
        AssemblyResult left = loadResult(ctx.queryParam("a"));
        AssemblyResult right = loadResult(ctx.queryParam("b"));
        if (left == null || right == null) {
            ctx.status(404).json(Map.of("error", "unknown derivation"));
            return;
        }
        ctx.json(AssemblyDiff.of(left, right, ctx.queryParam("a"), ctx.queryParam("b")));
    }

    private void validity(Context ctx) {
        Records.DerivationRecord record = store.derivation(ctx.pathParam("id"));
        if (record == null) {
            ctx.status(404).json(Map.of("error", "unknown derivation"));
            return;
        }
        ProjectVersion current = store.currentVersion();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("derivationId", record.id);
        out.put("basedOnVersion", record.versionId);
        out.put("currentVersion", current.id);
        List<String> changed = DependencyGraph.changedInputs(record.inputHashes, current);
        out.put("stillValid", changed.isEmpty());
        out.put("changedInputs", changed);
        out.put("explanation", changed.isEmpty()
                ? "所有记录输入的内容哈希未变，该结果仍然有效"
                : "这些输入已变化，需要重算: " + changed);
        ctx.json(out);
    }

    private void meshSvg(Context ctx) {
        String versionId = ctx.queryParam("version");
        ProjectVersion version = store.version(versionId == null ? store.currentVersionId() : versionId);
        if (version == null) {
            ctx.status(404).result("unknown version");
            return;
        }
        java.util.Set<String> isolated = new TreeSet<>();
        Records.DerivationRecord latest = latestDerivationFor(version.id);
        if (latest != null) {
            AssemblyResult result = Json.read(latest.resultJson, AssemblyResult.class);
            for (gsb.fea.assembly.Diagnostic diagnostic : result.diagnostics) {
                if (diagnostic.code.equals("ISOLATED_DOF")) {
                    for (String object : diagnostic.objects) {
                        isolated.add(object.contains(".")
                                ? object.substring(0, object.indexOf('.')) : object);
                    }
                }
            }
        }
        ctx.contentType("image/svg+xml").result(Svg.mesh(version, isolated));
    }

    private Records.DerivationRecord latestDerivationFor(String versionId) {
        Records.DerivationRecord latest = null;
        for (Records.DerivationRecord record : store.derivationsFor(versionId)) {
            latest = record;
        }
        return latest;
    }

    private void patternSvg(Context ctx) {
        AssemblyResult result = loadResult(ctx.queryParam("derivation"));
        if (result == null) {
            ctx.status(404).result("unknown derivation");
            return;
        }
        ctx.contentType("image/svg+xml").result(Svg.pattern(result));
    }

    private void diffSvg(Context ctx) {
        AssemblyResult left = loadResult(ctx.queryParam("a"));
        AssemblyResult right = loadResult(ctx.queryParam("b"));
        if (left == null || right == null) {
            ctx.status(404).result("unknown derivation");
            return;
        }
        AssemblyDiff diff = AssemblyDiff.of(left, right, ctx.queryParam("a"), ctx.queryParam("b"));
        int dim = Math.max(left.physicalDimension, right.physicalDimension);
        ctx.contentType("image/svg+xml").result(Svg.diff(diff, dim));
    }

    private void assemble(Context ctx) {
        Map<String, Object> body = Json.read(ctx.body(), Map.class);
        String versionId = String.valueOf(body.get("version"));
        ProjectVersion version = store.version(versionId);
        if (version == null) {
            ctx.status(404).json(Map.of("error", "unknown version " + versionId));
            return;
        }
        gsb.fea.assembly.AssemblyConfig config = new gsb.fea.assembly.AssemblyConfig();
        if (body.get("summation") != null) {
            config.summation = gsb.fea.assembly.SummationSpec.valueOf(String.valueOf(body.get("summation")));
        }
        if (body.get("strategy") != null && !String.valueOf(body.get("strategy")).isBlank()) {
            config.resolvedStrategy = gsb.fea.model.ConstraintSet.Strategy
                    .valueOf(String.valueOf(body.get("strategy")));
        }
        if (body.get("renumber") != null && !String.valueOf(body.get("renumber")).isBlank()) {
            config.renumberRevision = String.valueOf(body.get("renumber"));
        }
        if (body.get("constraintRevision") != null
                && !String.valueOf(body.get("constraintRevision")).isBlank()) {
            config.constraintRevision = String.valueOf(body.get("constraintRevision"));
        }
        String derivationId = body.get("derivationId") != null
                ? String.valueOf(body.get("derivationId"))
                : "W" + (store.derivations().size() + 1);
        AssemblyResult result = assembler.assemble(version, config, derivationId);
        Records.DerivationRecord record = new Records.DerivationRecord();
        record.id = derivationId;
        record.versionId = version.id;
        record.kind = "ASSEMBLY";
        record.configSignature = config.signature();
        record.inputHashes = DependencyGraph.assemblyInputs(version);
        record.resultJson = Json.write(result);
        record.createdAtMillis = System.currentTimeMillis();
        store.saveDerivation(record);
        ctx.json(Map.of("derivationId", derivationId, "complete", result.complete,
                "blocked", result.blocked(),
                "diagnostics", result.diagnostics.stream().map(d -> d.code).toList()));
    }

    private void decide(Context ctx) {
        Map<String, Object> body = Json.read(ctx.body(), Map.class);
        String baseId = String.valueOf(body.get("on"));
        String choose = String.valueOf(body.get("choose"));
        ProjectVersion base = store.version(baseId);
        if (base == null) {
            ctx.status(404).json(Map.of("error", "unknown version"));
            return;
        }
        if (!baseId.equals(store.currentVersionId())) {
            store.checkout(baseId, "web-reviewer", "decision branches from its base version");
        }
        gsb.fea.model.ConstraintSet conflicted = base.constraintSets.get("BC-4");
        if (conflicted == null) {
            ctx.status(400).json(Map.of("error", "no conflicting constraint set BC-4 on " + baseId));
            return;
        }
        gsb.fea.model.ConstraintSet resolved = new gsb.fea.model.ConstraintSet();
        resolved.revision = "BC-4-RESOLVED";
        List<String> rejected = new ArrayList<>();
        for (gsb.fea.model.ConstraintSet.Constraint constraint : conflicted.constraints) {
            if (constraint.uid.equals(choose)) {
                resolved.constraints.add(constraint);
            } else {
                rejected.add(constraint.uid);
            }
        }
        ProjectVersion next = gsb.fea.casepack.FixtureData.copy(base,
                "v" + (store.versions().size() + 1), "web-decision",
                System.currentTimeMillis(), "web decision: keep " + choose + ", reject " + rejected);
        next.constraintSets.put(resolved.revision, resolved);
        next.selectedConstraintRevision = resolved.revision;
        store.saveVersion(next, baseId, "DECISION_CONSTRAINT_CONFLICT", "web-reviewer",
                "constraint conflict resolved by human decision; rejected candidates retained",
                Map.of("chosen", choose, "rejected", rejected));
        store.saveDecision("DEC-W" + System.currentTimeMillis(), next.id,
                "CONSTRAINT_CONFLICT", "web-reviewer", choose, rejected,
                "rejected candidates remain in BC-4, not deleted",
                Map.of("baseVersion", baseId));
        ctx.json(Map.of("newVersion", next.id, "chosen", choose, "rejectedKept", rejected));
    }

    private void resolve(Context ctx) {
        Map<String, Object> body = Json.read(ctx.body(), Map.class);
        store.resolveConflict(String.valueOf(body.get("conflictId")),
                String.valueOf(body.get("choose")), "web-reviewer");
        ctx.json(Map.of("resolved", body.get("conflictId"), "current", store.currentVersionId()));
    }

    private void checkout(Context ctx) {
        Map<String, Object> body = Json.read(ctx.body(), Map.class);
        store.checkout(String.valueOf(body.get("version")), "web-reviewer",
                "回到历史事件对应版本：恢复当时的依赖版本与可见结论");
        ctx.json(Map.of("current", store.currentVersionId()));
    }

    private void replay(Context ctx) {
        try {
            Path temp = Files.createTempDirectory("fea-replay");
            CasePack pack = CasePackLoader.load();
            CasePackRunner.ReplayReport report;
            try (Store replayStore = Store.open(temp.resolve("replay.db"))) {
                report = CasePackRunner.run(pack, replayStore);
            }
            ctx.json(report);
        } catch (java.io.IOException e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void export(Context ctx) {
        ctx.contentType("application/json")
                .header("Content-Disposition", "attachment; filename=fea-ledger-archive.json")
                .result(store.exportArchive());
    }

    private void importArchive(Context ctx) {
        store.importArchive(ctx.bodyAsBytes());
        ctx.json(Map.of("imported", true, "current", store.currentVersionId(),
                "versions", store.versions().size()));
    }
}
