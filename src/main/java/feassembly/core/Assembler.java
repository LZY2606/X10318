package feassembly.core;

import feassembly.model.CaseState;
import feassembly.model.RawModels;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Core assembly rules.
 *
 * Determinism: every local matrix entry becomes one "contribution" carrying a
 * stable source identity. Contributions to one global (i,j) are summed in one
 * fixed canonical order (element id, then local row, then local col), so the
 * result is reproducible bit-for-bit. A reverse-order float sum is kept only
 * as a float-order diagnostic; it never replaces the adopted value.
 *
 * Constraints: ELIMINATION and PENALTY are never mixed. If a state contains
 * both, or two constraints conflict on the same dof, the result is
 * UNRESOLVED and every legal candidate is retained.
 */
public final class Assembler {

    public static final String ADOPTED = "ADOPTED";
    public static final String REJECTED = "REJECTED";
    public static final String UNRESOLVED = "UNRESOLVED";

    private static final double SYM_TOL = 1e-9;

    private Assembler() {}

    public static final class Diag {
        String code, severity, message, sourceKind, sourceId;
        List<String> affected = new ArrayList<>();
        boolean blocking;

        JSONObject json() {
            return new JSONObject()
                    .put("code", code).put("severity", severity)
                    .put("blocking", blocking).put("message", message)
                    .put("sourceKind", sourceKind).put("sourceId", sourceId)
                    .put("affected", new JSONArray(affected));
        }
    }

    private static Diag diag(String code, String severity, String message,
                             String kind, String id, boolean blocking) {
        Diag d = new Diag();
        d.code = code; d.severity = severity; d.message = message;
        d.sourceKind = kind; d.sourceId = id; d.blocking = blocking;
        return d;
    }

    public static double parseDouble(String raw) {
        return Double.parseDouble(raw.trim());
    }

    public static final class Result {
        public final JSONObject json;
        Result(JSONObject json) { this.json = json; }
    }

    /** Parse global index; returns Integer.MAX_VALUE on failure and records diagnostic. */
    private static int globalIndex(String raw, List<Diag> diags, String dof) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (v != Math.rint(v)) throw new NumberFormatException();
            return (int) v;
        } catch (Exception e) {
            Diag d = diag("UNCONVERTIBLE_INDEX", "error",
                    "global index '" + raw + "' for " + dof
                            + " cannot be converted to an integer; left in place",
                    "DOF", dof, true);
            diags.add(d);
            return Integer.MAX_VALUE;
        }
    }

    private static double parseEntry(String raw, List<Diag> diags, String elemId,
                                     String lr, String lc) {
        try {
            return parseDouble(raw);
        } catch (Exception e) {
            diags.add(diag("UNCONVERTIBLE_VALUE", "error",
                    "local entry [" + lr + "," + lc + "]='" + raw + "' cannot be"
                            + " normalized; original text retained at source",
                    "LOCAL_ENTRY", elemId + "#" + lr + "," + lc, true));
            return Double.NaN;
        }
    }

    /**
     * Assemble one state. The returned JSON is the derived bundle: sparse
     * pattern, contributions with provenance, both constraint candidates,
     * diagnostics with affected objects, and stable fingerprints.
     */
    public static Result assemble(CaseState state) {
        List<Diag> diags = new ArrayList<>();

        // ---- physical dof -> global index (duplicate mapping diagnostic) ----
        Map<String, Integer> physToGlobal = new LinkedHashMap<>();
        Map<Integer, String> globalToPhys = new TreeMap<>();
        Map<Integer, List<String>> duplicateSeen = new TreeMap<>();
        int n = 0;
        for (Map.Entry<String, RawModels.DofMapEntry> e : state.dofMap.entrySet()) {
            String phys = e.getKey();
            int g = globalIndex(e.getValue().globalIndexRaw, diags, phys);
            if (g == Integer.MAX_VALUE) continue;
            if (globalToPhys.containsKey(g)) {
                duplicateSeen.computeIfAbsent(g, k -> {
                    List<String> l = new ArrayList<>();
                    l.add(globalToPhys.get(k));
                    return l;
                }).add(phys);
                diags.add(diag("DUPLICATE_GLOBAL_DOF", "error",
                        "physical dof " + phys + " maps to occupied global index "
                                + g + " (held by " + globalToPhys.get(g) + ")",
                        "DOF", phys, true));
                continue;
            }
            physToGlobal.put(phys, g);
            globalToPhys.put(g, phys);
            n = Math.max(n, g + 1);
        }

        // ---- unit bookkeeping ----
        String matrixUnits = null;
        for (RawModels.LocalMatrix lm : state.localMatrices.values()) {
            if (matrixUnits == null) matrixUnits = lm.units;
        }

        // ---- raw contributions from every local matrix entry ----
        List<JSONObject> contributions = new ArrayList<>();
        List<String> elementOrder = new ArrayList<>(state.localMatrices.keySet());
        elementOrder.sort(String::compareTo);
        int contribOrdinal = 0;

        for (String elemId : elementOrder) {
            RawModels.LocalMatrix lm = state.localMatrices.get(elemId);
            RawModels.Element el = state.elements.get(elemId);

            // degenerate element: connectivity has repeated or missing nodes
            boolean degenerate = false;
            if (el != null) {
                List<String> seen = new ArrayList<>();
                for (String nd : el.nodeIds) {
                    if (!state.nodes.containsKey(nd)) {
                        diags.add(diag("DANGLING_NODE_REF", "error",
                                "element " + elemId + " references unknown node " + nd,
                                "ELEMENT", elemId, true));
                        degenerate = true;
                    }
                    if (seen.contains(nd)) {
                        diags.add(diag("DEGENERATE_ELEMENT", "error",
                                "element " + elemId + " has repeated node " + nd
                                        + " (zero-volume / collapsed element)",
                                "ELEMENT", elemId, true));
                        degenerate = true;
                    }
                    seen.add(nd);
                }
            }

            // repeated local dof within the local matrix
            List<String> localSeen = new ArrayList<>();
            for (String ld : lm.localDofs) {
                if (localSeen.contains(ld)) {
                    diags.add(diag("REPEATED_LOCAL_DOF", "error",
                            "element " + elemId + " declares local dof " + ld
                                    + " more than once",
                            "LOCAL_MATRIX", elemId, true));
                    degenerate = true;
                }
                localSeen.add(ld);
            }

            double[][] m = buildLocal(lm, diags, degenerate);
            int k = lm.localDofs.size();

            for (int r = 0; r < k; r++) {
                for (int c = 0; c < k; c++) {
                    String lr = lm.localDofs.get(r);
                    String lc = lm.localDofs.get(c);
                    double value = m[r][c];
                    Integer gr = physToGlobal.get(lr);
                    Integer gc = physToGlobal.get(lc);
                    JSONObject co = new JSONObject()
                            .put("contributionId",
                                    "C-" + elemId + "-" + r + "-" + c + "-"
                                            + (contribOrdinal++))
                            .put("elementId", elemId)
                            .put("localRow", r).put("localCol", c)
                            .put("localRowDof", lr).put("localColDof", lc)
                            .put("rawValue", lm.entries.get(r * k + c)[2])
                            .put("value", Double.isNaN(value) ? JSONObject.NULL : value)
                            .put("units", lm.units)
                            .put("floatMode", lm.floatMode)
                            .put("fromDegenerateElement", degenerate);
                    if (gr == null || gc == null) {
                        co.put("globalRow", JSONObject.NULL)
                          .put("globalCol", JSONObject.NULL)
                          .put("adoption", REJECTED)
                          .put("reason", gr == null
                                  ? ("local dof " + lr + " has no usable global mapping")
                                  : ("local dof " + lc + " has no usable global mapping"));
                        if (gr == null) diags.add(diag("UNMAPPED_LOCAL_DOF", "error",
                                "element " + elemId + " local dof " + lr
                                        + " cannot be mapped to a global dof",
                                "LOCAL_MATRIX", elemId, true));
                    } else {
                        co.put("globalRow", gr).put("globalCol", gc).put("adoption", ADOPTED);
                    }
                    if (degenerate && gr != null && gc != null) {
                        co.put("adoption", REJECTED)
                          .put("reason", "contribution of degenerate element not adopted");
                    }
                    contributions.add(co);
                }
            }
        }

        // ---- deterministic aggregation by (i,j) ----
        Map<String, List<JSONObject>> buckets = new TreeMap<>();
        for (JSONObject co : contributions) {
            if (!ADOPTED.equals(co.optString("adoption"))) continue;
            if (co.isNull("value")) continue;
            String key = co.getInt("globalRow") + "," + co.getInt("globalCol");
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(co);
        }
        // canonical per-bucket order: elementId, localRow, localCol
        Comparator<JSONObject> cc = Comparator
                .comparing((JSONObject o) -> o.getString("elementId"))
                .thenComparingInt(o -> o.getInt("localRow"))
                .thenComparingInt(o -> o.getInt("localCol"));

        JSONArray entries = new JSONArray();
        JSONArray nzIds = new JSONArray();
        int nzOrdinal = 0;
        for (Map.Entry<String, List<JSONObject>> b : buckets.entrySet()) {
            List<JSONObject> list = b.getValue();
            list.sort(cc);
            double forward = 0.0;
            for (JSONObject co : list) forward += co.getDouble("value");
            double reverse = 0.0;
            for (int i = list.size() - 1; i >= 0; i--) reverse += list.get(i).getDouble("value");

            String[] ij = b.getKey().split(",");
            int gi = Integer.parseInt(ij[0]), gj = Integer.parseInt(ij[1]);
            String nzId = "NZ-" + gi + "-" + gj + "#" + nzOrdinal;
            JSONArray stack = new JSONArray();
            for (JSONObject co : list) {
                stack.put(new JSONObject()
                        .put("contributionId", co.get("contributionId"))
                        .put("elementId", co.getString("elementId"))
                        .put("localRow", co.getInt("localRow"))
                        .put("localCol", co.getInt("localCol"))
                        .put("rawValue", co.getString("rawValue"))
                        .put("value", co.getDouble("value")));
            }
            entries.put(new JSONObject()
                    .put("nzId", nzId)
                    .put("row", gi).put("col", gj)
                    .put("value", forward)
                    .put("reverseOrderValue", reverse)
                    .put("floatOrderDelta", forward - reverse)
                    .put("sumOrder", "ELEMENT_ID,LOCAL_ROW,LOCAL_COL")
                    .put("stack", stack));
            nzIds.put(nzId);
            nzOrdinal++;
            if (list.size() > 1 && forward != reverse) {
                diags.add(diag("FLOAT_SUM_ORDER", "info",
                        "entry (" + gi + "," + gj + ") gets " + list.size()
                                + " contributions; forward sum " + forward
                                + " vs reverse sum " + reverse
                                + " — deterministic order retained, delta="
                                + (forward - reverse),
                        "NZ", nzId, false));
            }
        }

        // ---- diagnostics: isolated dofs / nodes ----
        boolean[] touched = new boolean[Math.max(n, 0)];
        for (Object o : entries) {
            JSONObject nz = (JSONObject) o;
            if (nz.getInt("row") < touched.length) touched[nz.getInt("row")] = true;
            if (nz.getInt("col") < touched.length) touched[nz.getInt("col")] = true;
        }
        for (int g = 0; g < touched.length; g++) {
            if (!touched[g]) {
                String phys = globalToPhys.get(g);
                diags.add(diag("ISOLATED_DOF", "warning",
                        "global dof " + g + " (" + phys
                                + ") receives no contribution (isolated)",
                        "DOF", phys, false));
            }
        }
        for (RawModels.Node nd : state.nodes.values()) {
            boolean referenced = state.elements.values().stream()
                    .anyMatch(el -> el.nodeIds.contains(nd.nodeId));
            if (!referenced) {
                diags.add(diag("ISOLATED_NODE", "warning",
                        "node " + nd.nodeId + " is not referenced by any element",
                        "NODE", nd.nodeId, false));
            }
        }

        // ---- asymmetry diagnostics: local and global ----
        for (String elemId : elementOrder) {
            RawModels.LocalMatrix lm = state.localMatrices.get(elemId);
            double[][] m = buildLocal(lm, new ArrayList<>(), false);
            int k = lm.localDofs.size();
            double maxAbs = 0;
            for (double[] row : m) for (double v : row) maxAbs = Math.max(maxAbs, Math.abs(v));
            for (int r = 0; r < k; r++) {
                for (int c = r + 1; c < k; c++) {
                    if (!Double.isNaN(m[r][c]) && !Double.isNaN(m[c][r])
                            && Math.abs(m[r][c] - m[c][r])
                            > SYM_TOL * Math.max(1.0, maxAbs)) {
                        diags.add(diag("LOCAL_MATRIX_ASYMMETRIC", "warning",
                                "local matrix of element " + elemId + " is asymmetric at ("
                                        + lm.localDofs.get(r) + "," + lm.localDofs.get(c)
                                        + "): " + m[r][c] + " vs " + m[c][r]
                                        + " — contribution kept but flagged",
                                "LOCAL_MATRIX", elemId, false));
                    }
                }
            }
        }

        JSONObject freeMatrix = new JSONObject()
                .put("size", n)
                .put("units", matrixUnits == null ? "UNKNOWN" : matrixUnits)
                .put("entries", entries)
                .put("nzIds", nzIds)
                .put("rowLabels", rowLabels(globalToPhys, n));

        // ---- constraints: ELIMINATION and PENALTY are never mixed ----
        JSONArray candidates = new JSONArray();
        List<RawModels.Constraint> cons = new ArrayList<>(state.constraints.values());

        // conflict: two distinct constraints on the same physical dof
        Map<String, List<RawModels.Constraint>> byDof = new LinkedHashMap<>();
        for (RawModels.Constraint c : cons) {
            byDof.computeIfAbsent(c.physicalDof, k -> new ArrayList<>()).add(c);
        }
        boolean conflict = false;
        for (Map.Entry<String, List<RawModels.Constraint>> e : byDof.entrySet()) {
            if (e.getValue().size() > 1) {
                conflict = true;
                double v0 = parseDoubleSafe(e.getValue().get(0).valueRaw);
                for (int i = 1; i < e.getValue().size(); i++) {
                    double vi = parseDoubleSafe(e.getValue().get(i).valueRaw);
                    if (vi != v0) {
                        Diag d = diag("CONSTRAINT_CONFLICT", "error",
                                "dof " + e.getKey() + " constrained to conflicting values "
                                        + v0 + " and " + vi + " (constraint versions "
                                        + e.getValue().get(0).constraintVersion + " vs "
                                        + e.getValue().get(i).constraintVersion
                                        + "); all legal candidates retained",
                                "CONSTRAINT", e.getValue().get(i).constraintId, true);
                        d.affected.add(e.getValue().get(0).constraintId);
                        diags.add(d);
                    }
                }
            }
        }

        boolean hasElim = cons.stream().anyMatch(c -> "ELIMINATION".equals(c.strategy));
        boolean hasPen = cons.stream().anyMatch(c -> "PENALTY".equals(c.strategy));
        boolean mixed = hasElim && hasPen;
        if (mixed) {
            diags.add(diag("MIXED_CONSTRAINT_STRATEGY", "error",
                    "ELIMINATION and PENALTY constraints coexist; strategies must not be"
                            + " mixed, both candidates kept unresolved",
                    "CONSTRAINT_SET", "ALL", true));
        }

        if (!cons.isEmpty() && !mixed && !conflict) {
            candidates.put(buildEliminationCandidate(freeMatrix, cons, physToGlobal));
            candidates.put(buildPenaltyCandidate(freeMatrix, cons, physToGlobal));
        } else if (!cons.isEmpty()) {
            // unresolved: still expose both legal candidates, marked UNRESOLVED
            JSONObject elim = buildEliminationCandidate(freeMatrix, cons, physToGlobal);
            JSONObject pen = buildPenaltyCandidate(freeMatrix, cons, physToGlobal);
            elim.put("resolution", UNRESOLVED);
            pen.put("resolution", UNRESOLVED);
            candidates.put(elim);
            candidates.put(pen);
        }

        String adoptedStrategy = "FREE";
        if (!cons.isEmpty() && !mixed && !conflict) {
            adoptedStrategy = hasPen ? "PENALTY" : "ELIMINATION";
        } else if (!cons.isEmpty()) {
            adoptedStrategy = UNRESOLVED;
        }

        JSONArray diagArr = new JSONArray();
        for (Diag d : diags) diagArr.put(d.json());
        boolean hasBlocking = diags.stream().anyMatch(d -> d.blocking);

        JSONObject out = new JSONObject()
                .put("freeMatrix", freeMatrix)
                .put("contributions", new JSONArray(contributions))
                .put("constraintCandidates", candidates)
                .put("adoptedStrategy", adoptedStrategy)
                .put("diagnostics", diagArr)
                .put("hasBlockingDiagnostics", hasBlocking)
                .put("determinism", new JSONObject()
                        .put("sumOrder", "ELEMENT_ID,LOCAL_ROW,LOCAL_COL")
                        .put("note", "reverse-order float sums kept for diagnostics only"))
                .put("fingerprint", fingerprint(entries, adoptedStrategy));
        return new Result(out);
    }

    private static JSONArray rowLabels(Map<Integer, String> globalToPhys, int n) {
        JSONArray labels = new JSONArray();
        for (int g = 0; g < n; g++) labels.put(globalToPhys.getOrDefault(g, "UNMAPPED:" + g));
        return labels;
    }

    private static double[][] buildLocal(RawModels.LocalMatrix lm, List<Diag> diags,
                                         boolean alreadyDegenerate) {
        int k = lm.localDofs.size();
        double[][] m = new double[k][k];
        for (String[] e : lm.entries) {
            int r = Integer.parseInt(e[0].trim());
            int c = Integer.parseInt(e[1].trim());
            double v = parseEntry(e[2], diags, lm.elementId, e[0], e[1]);
            if (r >= 0 && r < k && c >= 0 && c < k) m[r][c] = v;
        }
        return m;
    }

    private static double parseDoubleSafe(String raw) {
        try { return parseDouble(raw); } catch (Exception e) { return Double.NaN; }
    }

    /** Elimination: prescribed rows/cols removed; reactions exposed on rhs. */
    private static JSONObject buildEliminationCandidate(JSONObject freeMatrix,
                                                        List<RawModels.Constraint> cons,
                                                        Map<String, Integer> physToGlobal) {
        int n = freeMatrix.getInt("size");
        boolean[] fixed = new boolean[n];
        double[] prescribed = new double[n];
        JSONArray fixedDofs = new JSONArray();
        JSONArray rhsExtra = new JSONArray();
        for (RawModels.Constraint c : cons) {
            Integer g = physToGlobal.get(c.physicalDof);
            if (g == null) continue;
            if (!"ELIMINATION".equals(c.strategy)) continue;
            fixed[g] = true;
            prescribed[g] = parseDoubleSafe(c.valueRaw);
            fixedDofs.put(new JSONObject().put("global", g)
                    .put("physicalDof", c.physicalDof)
                    .put("valueRaw", c.valueRaw).put("units", c.units)
                    .put("constraintId", c.constraintId)
                    .put("constraintVersion", c.constraintVersion));
        }
        JSONArray entries = new JSONArray();
        JSONArray nzIds = new JSONArray();
        int ord = 0;
        for (Object o : freeMatrix.getJSONArray("entries")) {
            JSONObject nz = (JSONObject) o;
            int r = nz.getInt("row"), c = nz.getInt("col");
            if (fixed[r] || fixed[c]) continue;
            String id = "EL-NZ-" + r + "-" + c + "#" + ord++;
            entries.put(new JSONObject().put("nzId", id).put("row", r).put("col", c)
                    .put("value", nz.getDouble("value"))
                    .put("originNzId", nz.getString("nzId")));
            nzIds.put(id);
        }
        // rhs load of eliminated dofs: f_i -= K(i,f)*u_f
        for (RawModels.Constraint c : cons) {
            Integer gf = physToGlobal.get(c.physicalDof);
            if (gf == null || !fixed[gf]) continue;
            JSONArray loads = new JSONArray();
            for (Object o : freeMatrix.getJSONArray("entries")) {
                JSONObject nz = (JSONObject) o;
                if (nz.getInt("col") == gf && !fixed[nz.getInt("row")]) {
                    loads.put(new JSONObject().put("row", nz.getInt("row"))
                            .put("coupling", nz.getDouble("value"))
                            .put("load", -nz.getDouble("value") * prescribed[gf]));
                }
            }
            rhsExtra.put(new JSONObject().put("fixedGlobal", gf)
                    .put("prescribed", prescribed[gf]).put("loads", loads));
        }
        return new JSONObject().put("candidateId", "ELIMINATION")
                .put("strategy", "ELIMINATION").put("resolution", "ADOPTED_CANDIDATE")
                .put("reducedSize", n - countTrue(fixed))
                .put("fixedDofs", fixedDofs).put("entries", entries).put("nzIds", nzIds)
                .put("rhsCoupling", rhsExtra)
                .put("units", freeMatrix.get("units"));
    }

    /** Penalty: K*(i,i)+=P, rhs(i)+=P*u; matrix keeps full size. */
    private static JSONObject buildPenaltyCandidate(JSONObject freeMatrix,
                                                    List<RawModels.Constraint> cons,
                                                    Map<String, Integer> physToGlobal) {
        int n = freeMatrix.getInt("size");
        double[][] mat = new double[n][n];
        boolean[][] present = new boolean[n][n];
        for (Object o : freeMatrix.getJSONArray("entries")) {
            JSONObject nz = (JSONObject) o;
            mat[nz.getInt("row")][nz.getInt("col")] = nz.getDouble("value");
            present[nz.getInt("row")][nz.getInt("col")] = true;
        }
        JSONArray penalized = new JSONArray();
        JSONArray rhs = new JSONArray();
        for (RawModels.Constraint c : cons) {
            Integer g = physToGlobal.get(c.physicalDof);
            if (g == null || !"PENALTY".equals(c.strategy)) continue;
            double p = c.penaltyRaw == null ? Double.NaN : parseDoubleSafe(c.penaltyRaw);
            double v = parseDoubleSafe(c.valueRaw);
            mat[g][g] += p;
            present[g][g] = true;
            penalized.put(new JSONObject().put("global", g).put("physicalDof", c.physicalDof)
                    .put("penaltyRaw", c.penaltyRaw).put("penalty", p)
                    .put("constraintId", c.constraintId)
                    .put("constraintVersion", c.constraintVersion));
            rhs.put(new JSONObject().put("global", g).put("load", p * v));
        }
        JSONArray entries = new JSONArray();
        JSONArray nzIds = new JSONArray();
        int ord = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (present[r][c]) {
                    String id = "PEN-NZ-" + r + "-" + c + "#" + ord++;
                    entries.put(new JSONObject().put("nzId", id).put("row", r)
                            .put("col", c).put("value", mat[r][c]));
                    nzIds.put(id);
                }
            }
        }
        return new JSONObject().put("candidateId", "PENALTY")
                .put("strategy", "PENALTY").put("resolution", "ADOPTED_CANDIDATE")
                .put("reducedSize", n).put("entries", entries).put("nzIds", nzIds)
                .put("penalizedDofs", penalized).put("rhsAdditions", rhs)
                .put("units", freeMatrix.get("units"));
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    private static String fingerprint(JSONArray entries, String strategy) {
        StringBuilder sb = new StringBuilder();
        sb.append(strategy).append('|');
        for (Object o : entries) {
            JSONObject nz = (JSONObject) o;
            sb.append(nz.getInt("row")).append(',').append(nz.getInt("col"))
              .append('=').append(nz.getDouble("value")).append(';');
        }
        long h = 1125899906842597L;
        for (int i = 0; i < sb.length(); i++) h = 31 * h + sb.charAt(i);
        return "FP-" + Long.toHexString(h);
    }
}
