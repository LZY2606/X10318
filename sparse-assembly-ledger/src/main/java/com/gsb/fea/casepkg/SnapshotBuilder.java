package com.gsb.fea.casepkg;

import com.gsb.fea.core.AssembleOptions;
import com.gsb.fea.core.Assembler;
import com.gsb.fea.core.AssemblyCandidate;
import com.gsb.fea.core.AssemblyReport;
import com.gsb.fea.core.Contribution;
import com.gsb.fea.core.Diagnostic;
import com.gsb.fea.core.FloatSensitivity;
import com.gsb.fea.core.Ids;
import com.gsb.fea.core.SparseEntry;
import com.gsb.fea.model.Constraint;
import com.gsb.fea.model.DofMap;
import com.gsb.fea.model.Element;
import com.gsb.fea.model.Node;
import com.gsb.fea.model.RawValue;
import com.gsb.fea.model.Renumbering;
import com.gsb.fea.model.SourceBundle;
import com.gsb.fea.storage.Repository;
import com.gsb.fea.util.Hashing;
import com.gsb.fea.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one source bundle + options into an immutable set of stored objects:
 * raw domain objects (with fingerprints), the dependency graph linking each
 * sparse entry to the exact local terms feeding it, and the derived assembly
 * report with every candidate and diagnostic.
 */
final class SnapshotBuilder {
    private final List<Repository.NewObject> objects = new ArrayList<>();
    private final DependencyGraphDto graph = new DependencyGraphDto();
    private int diagnosticSeq = 0;

    static final class DependencyGraphDto {
        final List<Map<String, Object>> nodes = new ArrayList<>();
        final List<Map<String, String>> edges = new ArrayList<>();
    }

    record Result(List<Repository.NewObject> objects, String graphJson) {}

    static Result build(SourceBundle bundle, AssembleOptions options,
                        String sourceCommitId, String bundleId) {
        SnapshotBuilder b = new SnapshotBuilder();
        b.storeSources(bundle, sourceCommitId, bundleId);
        AssemblyReport report = Assembler.assemble(bundle, options);
        b.storeReport(report, options, bundle);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("nodes", b.graph.nodes);
        g.put("edges", b.graph.edges);
        return new Result(b.objects, Json.write(g));
    }

    private void addObject(String urn, String kind, String stableId, Object payload,
                           String sourceCommitId) {
        String json = Json.write(payload);
        objects.add(new Repository.NewObject(urn, kind, stableId, json,
                Hashing.sha256(json), sourceCommitId));
    }

    private void graphNode(String urn, String kind, Object content, String status,
                           String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("urn", urn);
        m.put("kind", kind);
        m.put("fingerprint", Hashing.sha256(Json.write(content)));
        m.put("status", status);
        m.put("reason", reason);
        graph.nodes.add(m);
    }

    private void graphEdge(String fromUrn, String toUrn) {
        graph.edges.add(Map.of("from", fromUrn, "to", toUrn));
    }

    private void storeSources(SourceBundle b, String sourceCommitId, String bundleId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("bundleId", b.bundleId());
        meta.put("label", b.label());
        meta.put("dofOrder", b.allDofIds());
        addObject("bundle:" + b.bundleId(), "bundle", b.bundleId(), meta, sourceCommitId);
        graphNode("bundle:" + b.bundleId(), "bundle", meta, "VALID", "imported");

        for (Node n : b.nodes()) {
            String urn = Ids.node(n.nodeId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.nodeId());
            m.put("originalNumber", n.originalNumber());
            m.put("coords", n.coords());
            m.put("dofIds", n.dofIds());
            addObject(urn, "node", n.nodeId(), m, sourceCommitId);
            graphNode(urn, "node", m, "VALID", "imported");
            graphEdge("bundle:" + b.bundleId(), urn);
            for (String d : n.dofIds()) {
                Map<String, Object> dm = Map.of("dofId", d, "nodeId", n.nodeId());
                addObject(Ids.dof(d), "dof", d, dm, sourceCommitId);
                graphNode(Ids.dof(d), "dof", dm, "VALID", "declared at node");
                graphEdge(urn, Ids.dof(d));
            }
        }
        for (Element e : b.elements()) {
            String urn = Ids.element(e.elementId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("elementId", e.elementId());
            m.put("originalNumber", e.originalNumber());
            m.put("nodeIds", e.nodeIds());
            m.put("localDofs", e.localDofs());
            m.put("matrixUnit", e.matrixUnit());
            m.put("floatMode", e.floatMode());
            m.put("sourceFragment", e.sourceFragment());
            List<List<Map<String, Object>>> raw = new ArrayList<>();
            for (List<RawValue> row : e.matrix()) {
                List<Map<String, Object>> r = new ArrayList<>();
                for (RawValue rv : row) r.add(rawToMap(rv));
                raw.add(r);
            }
            m.put("matrix", raw);
            addObject(urn, "element", e.elementId(), m, sourceCommitId);
            graphNode(urn, "element", m, "VALID", "imported local matrix");
            graphEdge("bundle:" + b.bundleId(), urn);
            for (int a = 0; a < e.size(); a++) {
                for (int k = 0; k < e.size(); k++) {
                    String tu = Ids.term(e.elementId(), a, k);
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("urn", tu);
                    tm.put("elementId", e.elementId());
                    tm.put("localRow", a);
                    tm.put("localCol", k);
                    tm.put("localRowDof", e.localDofs().get(a));
                    tm.put("localColDof", e.localDofs().get(k));
                    RawValue rv = a < e.matrix().size() && k < e.matrix().get(a).size()
                            ? e.matrix().get(a).get(k) : null;
                    tm.put("raw", rawToMap(rv));
                    addObject(tu, "term", e.elementId() + ":" + a + "," + k, tm, sourceCommitId);
                    graphNode(tu, "term", tm, "VALID", "local matrix term");
                    graphEdge(urn, tu);
                }
            }
        }
        for (DofMap mp : b.maps()) {
            String urn = Ids.map(mp.elementId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("elementId", mp.elementId());
            m.put("mapVersion", mp.mapVersion());
            m.put("mapping", mp.mapping());
            addObject(urn, "map", mp.elementId(), m, sourceCommitId);
            graphNode(urn, "map", m, "VALID", "imported dof map");
            graphEdge(Ids.element(mp.elementId()), urn);
        }
        for (Constraint c : b.constraints()) {
            String urn = Ids.constraint(c.constraintId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("constraintId", c.constraintId());
            m.put("dofId", c.dofId());
            m.put("kind", c.kind());
            m.put("value", rawToMap(c.value()));
            m.put("version", c.version());
            m.put("rationale", c.rationale());
            addObject(urn, "constraint", c.constraintId(), m, sourceCommitId);
            graphNode(urn, "constraint", m, "VALID", "imported boundary constraint " + c.version());
            graphEdge(Ids.dof(c.dofId()), urn);
        }
        Renumbering rn = b.renumbering();
        if (rn != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("renumberingId", rn.renumberingId());
            m.put("dofToIndex", rn.dofToIndex());
            m.put("basis", rn.basis());
            m.put("rationale", rn.rationale());
            addObject("rn:" + rn.renumberingId(), "renumbering", rn.renumberingId(),
                    m, sourceCommitId);
            graphNode("rn:" + rn.renumberingId(), "renumbering", m, "VALID",
                    "index-only permutation");
        }
    }

    private void storeReport(AssemblyReport report, AssembleOptions options,
                             SourceBundle bundle) {
        for (AssemblyCandidate cand : report.candidates()) {
            String curn = "candidate:" + cand.candidateId();
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("candidateId", cand.candidateId());
            cm.put("label", cand.label());
            cm.put("prescribed", cand.prescribed());
            cm.put("penalty", cand.penalty());
            cm.put("reason", cand.reason());
            cm.put("entryUrns", cand.entries().stream().map(SparseEntry::urn).toList());
            addObject(curn, "candidate", cand.candidateId(), cm, null);
            graphNode(curn, "candidate", cm, "VALID", "legal assembly candidate");
            for (SparseEntry e : cand.entries()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("urn", e.urn());
                em.put("rowDof", e.rowDof());
                em.put("colDof", e.colDof());
                em.put("rowIndex", e.rowIndex());
                em.put("colIndex", e.colIndex());
                em.put("value", e.value());
                em.put("valueHex", Double.toHexString(e.value()));
                em.put("candidateId", cand.candidateId());
                List<Map<String, Object>> cts = new ArrayList<>();
                for (Contribution c : e.contributions()) cts.add(contributionToMap(c));
                em.put("contributions", cts);
                String eu = e.urn() + "@" + cand.candidateId();
                addObject(eu, "entry", e.urn() + "#" + cand.candidateId(), em, null);
                graphNode(eu, "entry", em, "VALID",
                        "summed from " + e.contributions().size() + " contributions in order");
                graphEdge(curn, eu);
                for (Contribution c : e.contributions()) {
                    if (!c.elementId().startsWith("#penalty:")) {
                        graphEdge(Ids.term(c.elementId(), c.localRow(), c.localCol()), eu);
                        graphEdge(Ids.map(c.elementId()), eu);
                    } else {
                        graphEdge("con:" + c.elementId().substring("#penalty:".length()), eu);
                    }
                }
            }
            for (Diagnostic dg : cand.diagnostics()) {
                addObject("diag:" + cand.candidateId() + ":" + diagnosticSeq++,
                        "candidate-diagnostic", dg.code(), diagnosticMap(dg), null);
            }
        }
        for (Diagnostic dg : report.diagnostics()) {
            addObject("diag:report:" + diagnosticSeq++,
                    "diagnostic", dg.code(), diagnosticMap(dg), null);
        }
        List<Map<String, Object>> sens = new ArrayList<>();
        for (FloatSensitivity fs : report.sensitivity()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("entryUrn", fs.entryUrn());
            sm.put("canonical", fs.canonical());
            sm.put("grouped", fs.grouped());
            sm.put("canonicalHex", Double.toHexString(fs.canonical()));
            sm.put("groupedHex", Double.toHexString(fs.grouped()));
            sm.put("absDiff", fs.absDiff());
            sm.put("ulps", fs.ulps());
            sens.add(sm);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("strategy", options.strategy().name());
        r.put("floatOrder", options.floatOrder().name());
        r.put("dofOrder", report.dofOrder());
        r.put("rejected", report.rejected());
        r.put("indeterminate", report.indeterminate());
        r.put("candidateIds", report.candidates().stream()
                .map(AssemblyCandidate::candidateId).toList());
        r.put("diagnosticCodes", report.diagnostics().stream()
                .map(Diagnostic::code).sorted().distinct().toList());
        r.put("sensitivity", sens);
        addObject("report:assembly", "report", "assembly", r, null);
        graphNode("report:assembly", "report", r,
                report.rejected() ? "DIRTY" : "VALID",
                report.rejected() ? "rejected: mixed constraint strategy" : "assembled");
        for (AssemblyCandidate c : report.candidates()) {
            graphEdge("candidate:" + c.candidateId(), "report:assembly");
        }
    }

    private static Map<String, Object> rawToMap(RawValue rv) {
        if (rv == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", rv.text());
        m.put("numeric", rv.numeric());
        m.put("numericHex", rv.numeric() == null ? null : Double.toHexString(rv.numeric()));
        m.put("parseOk", rv.parseOk());
        m.put("parseError", rv.parseError());
        m.put("unit", rv.unit());
        m.put("datum", rv.datum());
        return m;
    }

    private static Map<String, Object> contributionToMap(Contribution c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("urn", c.urn());
        m.put("sparseEntryUrn", c.sparseEntryUrn());
        m.put("elementId", c.elementId());
        m.put("localRow", c.localRow());
        m.put("localCol", c.localCol());
        m.put("rowDof", c.rowDof());
        m.put("colDof", c.colDof());
        m.put("value", c.value());
        m.put("valueHex", Double.toHexString(c.value()));
        m.put("rawToken", c.rawToken());
        m.put("unit", c.unit());
        m.put("ordinal", c.ordinal());
        return m;
    }

    private static Map<String, Object> diagnosticMap(Diagnostic dg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", dg.code());
        m.put("severity", dg.severity().name());
        m.put("message", dg.message());
        m.put("objects", dg.objects());
        m.put("details", dg.details());
        return m;
    }
}
