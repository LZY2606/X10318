package femprov;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline domain case pack: "sparse assembly ledger - FEM entries,
 * constraint elimination and matrix provenance". Loads the fixture,
 * replays the narrative (baseline import, parameter revision, local
 * source change, conflicting human decisions) and the counterexample
 * archive (seven single-source mutations), emitting machine-readable
 * judgment events for every decision.
 */
public final class CasePack {
    public final JsonNode fixture;
    public final DepGraph graph = new DepGraph();
    public final List<String> mainlineIds = new ArrayList<>();
    public final List<String> mutationIds = new ArrayList<>();
    public String conflictId;
    private long seq = 0;

    private CasePack(JsonNode fixture) { this.fixture = fixture; }

    public static CasePack load() {
        try (InputStream in = CasePack.class.getResourceAsStream("/casepack/case.json")) {
            if (in == null) throw new IllegalStateException("casepack/case.json missing");
            return new CasePack(Json.read(new String(in.readAllBytes())));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static CasePack load(Path p) {
        try { return new CasePack(Json.read(Files.readString(p))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private long nextSeq() { return ++seq; }

    // ---------- raw import construction ----------
    public Model.RawImport toImport(JsonNode n) {
        List<Model.RawElement> elements = new ArrayList<>();
        for (JsonNode e : n.get("elements")) {
            List<List<String>> m = new ArrayList<>();
            for (JsonNode row : e.get("matrix")) {
                List<String> r = new ArrayList<>();
                row.forEach(x -> r.add(x.asText()));
                m.add(r);
            }
            List<String> conn = new ArrayList<>();
            e.get("connectivity").forEach(x -> conn.add(x.asText()));
            List<String> ldo = new ArrayList<>();
            e.get("localDofOrder").forEach(x -> ldo.add(x.asText()));
            elements.add(new Model.RawElement(e.get("id").asText(), conn, ldo, m, e.get("unit").asText()));
        }
        Map<String, List<String>> dofMaps = new LinkedHashMap<>();
        n.get("dofMaps").fields().forEachRemaining(en -> {
            List<String> v = new ArrayList<>();
            en.getValue().forEach(x -> v.add(x.asText()));
            dofMaps.put(en.getKey(), v);
        });
        List<Model.RawConstraint> cons = new ArrayList<>();
        for (JsonNode c : n.get("constraints"))
            cons.add(new Model.RawConstraint(c.get("dof").asText(), c.get("value").asText(),
                    c.get("unit").asText(), c.get("strategy").asText()));
        List<String> declared = new ArrayList<>();
        n.get("declaredDofs").forEach(x -> declared.add(x.asText()));
        JsonNode meta = n.get("meta");
        return new Model.RawImport(
                n.has("importId") ? n.get("importId").asText() : "import",
                meta.get("constraintVersion").asText(),
                meta.get("floatMode").asText(),
                meta.get("coordinateBasis").asText(),
                declared, elements, dofMaps, cons);
    }

    /** Apply a fixture patch to the baseline JSON, returning a new import JSON tree. */
    public JsonNode patched(JsonNode base, JsonNode patch) {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) Json.read(base.toString());
        if (patch.has("floatMode")) ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("meta"))
                .put("floatMode", patch.get("floatMode").asText());
        if (patch.has("constraintVersion")) ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("meta"))
                .put("constraintVersion", patch.get("constraintVersion").asText());
        if (patch.has("constraints")) root.set("constraints", patch.get("constraints"));
        if (patch.has("constraintsAdd")) {
            var arr = (com.fasterxml.jackson.databind.node.ArrayNode) root.get("constraints");
            patch.get("constraintsAdd").forEach(arr::add);
        }
        if (patch.has("declaredDofsAdd")) {
            var arr = (com.fasterxml.jackson.databind.node.ArrayNode) root.get("declaredDofs");
            patch.get("declaredDofsAdd").forEach(arr::add);
        }
        if (patch.has("dofMaps")) {
            var maps = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("dofMaps");
            patch.get("dofMaps").fields().forEachRemaining(en -> maps.set(en.getKey(), en.getValue()));
        }
        if (patch.has("elementsMatrix")) {
            patch.get("elementsMatrix").fields().forEachRemaining(en -> {
                for (JsonNode e : root.get("elements"))
                    if (e.get("id").asText().equals(en.getKey()))
                        ((com.fasterxml.jackson.databind.node.ObjectNode) e).set("matrix", en.getValue());
            });
        }
        if (patch.has("elementsConnectivity")) {
            patch.get("elementsConnectivity").fields().forEachRemaining(en -> {
                for (JsonNode e : root.get("elements"))
                    if (e.get("id").asText().equals(en.getKey()))
                        ((com.fasterxml.jackson.databind.node.ObjectNode) e).set("connectivity", en.getValue());
            });
        }
        if (patch.has("elementsRemove")) {
            var arr = (com.fasterxml.jackson.databind.node.ArrayNode) root.get("elements");
            for (JsonNode id : patch.get("elementsRemove"))
                for (int i = arr.size() - 1; i >= 0; i--)
                    if (arr.get(i).get("id").asText().equals(id.asText())) arr.remove(i);
        }
        return root;
    }

    // ---------- scenario ----------
    /** Replay the full narrative into the store. Returns baseline version id. */
    public String runScenario(Store store) {
        JsonNode base = fixture.get("baseline");
        Model.RawImport imp0 = toImport(base);
        Assembler.Result r1 = Assembler.assemble(imp0, null, null);
        registerGraph(r1);

        // v1: baseline import, raw layer fully preserved
        String v1 = "v1-baseline";
        save(store, v1, null, "import",
                "baseline import: original element ids, local dof order, local matrix values, "
                        + "global map, constraint version, units and float mode preserved",
                imp0, r1, null, List.of(judgment("baseline-preserved",
                        "raw layer preserved verbatim; normalization happens only in derived layer",
                        List.of())));

        // v2: parameter revision (constraint settlement value)
        JsonNode rev = fixture.get("revisions");
        Model.RawImport imp2 = toImport(patched(base, rev.get(0).get("patch")));
        Assembler.Result r2 = Assembler.assemble(imp2, null, null);
        String v2 = "v2-param-revision";
        save(store, v2, v1, "revision",
                "parameter revision: " + rev.get(0).get("note").asText(), imp2, r2, null,
                List.of(judgment("param-revision", rev.get(0).get("note").asText(),
                        graph.sourceChanged("constraints", seq))));

        // v3: local source change affecting only local dependencies
        Model.RawImport imp3 = toImport(patched(base, rev.get(1).get("patch")));
        Assembler.Result r3 = Assembler.assemble(imp3, null, null);
        List<String> affected = graph.sourceChanged("element:E6", nextSeq());
        String v3 = "v3-local-source-change";
        save(store, v3, v2, "revision",
                "local source change: " + rev.get(1).get("note").asText()
                        + "; recomputed only: " + affected,
                imp3, r3, null, List.of(judgment("local-recompute",
                        "dependency graph limited recompute to " + affected, affected)));

        // v4a / v4b: two users edit from v3 -> resolvable conflict
        Model.RawImport imp4a = toImport(patched(base, rev.get(2).get("patch")));
        Assembler.Result r4a = Assembler.assemble(imp4a, null, null);
        String v4a = "v4a-float-mode-branch";
        save(store, v4a, v3, "branch", "user A: " + rev.get(2).get("note").asText(), imp4a, r4a, null,
                List.of());

        Model.RawImport imp4b = toImport(patched(base, rev.get(3).get("patch")));
        Assembler.Result r4b = Assembler.assemble(imp4b, null, null);
        String v4b = "v4b-stiffness-branch";
        try {
            save(store, v4b, v3, "branch", "user B: " + rev.get(3).get("note").asText(), imp4b, r4b, null,
                    List.of());
            throw new IllegalStateException("expected conflict");
        } catch (Store.ConflictException ce) {
            conflictId = ce.conflictId;
            // persist the losing candidate as a complete version without moving the pointer
            store.saveVersion(new Store.Version(v4b, v3, "branch",
                    "user B (rejected candidate, retained): " + rev.get(3).get("note").asText(),
                    payload(imp4b, r4b, null), nextSeq()), List.of(), null);
            // resolution: adopt v4a, keep v4b
            Store.Event res = new Store.Event("ev-resolve-" + conflictId, v4a, nextSeq(),
                    "judgment", Json.write(Map.of(
                            "rule", "conflict-resolution",
                            "decision", "adopt " + v4a + "; " + v4b + " retained as unchosen candidate",
                            "basis", "float-mode branch preserves deterministic summation semantics; "
                                    + "stiffness change needs re-review",
                            "unchosen", List.of(v4b))));
            store.resolveConflict(conflictId, v4a, res);
        }
        mainlineIds.addAll(List.of(v1, v2, v3, v4a, v4b));

        // counterexample archive: seven single-source mutations from baseline
        for (JsonNode m : fixture.get("mutations")) {
            String id = "m-" + m.get("id").asText();
            JsonNode mutated = patched(base, m.get("patch"));
            Model.RawImport impM = toImport(mutated);
            Map<String, Integer> renumber = null;
            if (m.has("renumber") && m.get("renumber").asBoolean()) {
                renumber = new LinkedHashMap<>();
                List<String> dofs = new ArrayList<>();
                impM.declaredDofs().forEach(dofs::add);
                for (var e : impM.elements()) for (String d : impM.dofMaps().get(e.origId()))
                    if (!dofs.contains(d)) dofs.add(d);
                for (var c : impM.constraints()) if (!dofs.contains(c.dof())) dofs.add(c.dof());
                dofs.sort(String::compareTo);
                for (int i = 0; i < dofs.size(); i++) renumber.put(dofs.get(i), dofs.size() - 1 - i);
            }
            Assembler.Result rm = Assembler.assemble(impM, null, renumber);
            List<String> scope = affectedScope(r1, rm);
            List<String> expected = new ArrayList<>();
            m.get("expect").forEach(x -> expected.add(x.asText()));
            List<String> actual = rm.diags().stream().map(Assembler.Diag::kind).distinct().toList();
            boolean adopted = m.get("adopted").asBoolean();
            Map<String, Object> judgment = new LinkedHashMap<>();
            judgment.put("rule", m.get("rule").asText());
            judgment.put("changedSource", m.get("source").asText());
            judgment.put("expectedDiagnostics", expected);
            judgment.put("actualDiagnostics", actual);
            judgment.put("affectedScope", scope);
            judgment.put("adopted", adopted);
            judgment.put("decision", m.get("judgment").asText());
            if (!rm.candidates().isEmpty()) {
                judgment.put("candidatesRetained",
                        rm.candidates().stream().map(Assembler.Result::strategy).toList());
            }
            mutationIds.add(id);
            save(store, id, v1, "counterexample",
                    "counterexample [" + m.get("id").asText() + "]: " + m.get("judgment").asText(),
                    impM, rm, renumber, List.of(new Store.Event("ev-" + id, id, nextSeq(),
                            "judgment", Json.write(judgment))));
        }
        return v1;
    }

    /** Which physical entries changed value or appeared/disappeared vs baseline. */
    static List<String> affectedScope(Assembler.Result baseline, Assembler.Result mutated) {
        List<String> out = new ArrayList<>();
        for (Assembler.Entry e : mutated.entries()) {
            Assembler.Entry b = baseline.find(e.rowDof(), e.colDof());
            if (b == null) out.add(e.id() + " (added)");
            else if (Double.compare(b.value(), e.value()) != 0)
                out.add(e.id() + " (" + b.value() + " -> " + e.value() + ")");
        }
        for (Assembler.Entry b : baseline.entries())
            if (mutated.find(b.rowDof(), b.colDof()) == null) out.add(b.id() + " (removed)");
        return out;
    }

    private void registerGraph(Assembler.Result r) {
        graph.register("constraints-view", List.of("constraints"));
        for (Assembler.Entry e : r.entries()) {
            List<String> deps = new ArrayList<>();
            e.contribs().forEach(c -> { String d = "element:" + c.elementId();
                if (!deps.contains(d)) deps.add(d); });
            graph.register("entry:" + e.id(), deps);
        }
        graph.register("sparsity", r.entries().stream().map(e -> "entry:" + e.id()).toList());
    }

    private Store.Event judgment(String rule, String basis, List<String> affected) {
        return new Store.Event("ev-" + rule + "-" + seq, null, seq, "judgment",
                Json.write(Map.of("rule", rule, "basis", basis, "affected", affected)));
    }

    private String payload(Model.RawImport imp, Assembler.Result r, Map<String, Integer> renumber) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rawImport", imp);
        p.put("result", r);
        if (renumber != null) p.put("renumber", renumber);
        return Json.write(p);
    }

    private void save(Store store, String id, String parent, String kind, String label,
                      Model.RawImport imp, Assembler.Result r, Map<String, Integer> renumber,
                      List<Store.Event> extraEvents) {
        long s = nextSeq();
        List<Store.Event> evs = new ArrayList<>();
        evs.add(new Store.Event("ev-save-" + id, id, s, kind, Json.write(Map.of("label", label))));
        evs.addAll(extraEvents);
        store.saveVersion(new Store.Version(id, parent, kind, label, payload(imp, r, renumber), s),
                evs, parent == null ? null : store.currentVersionId());
        // artifacts: every entry carries why it is valid or was recomputed
        for (Assembler.Entry e : r.entries()) {
            String key = "entry:" + e.id();
            store.putArtifact(key, id, Json.write(e), graph.explain(key));
        }
        store.putArtifact("assembly", id, Json.write(r), "assembled at seq " + s);
    }
}
