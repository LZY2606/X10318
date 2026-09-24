package fea.asm;

import fea.model.CaseData;
import fea.model.UnitConv;
import java.util.*;

/** Assembly result with full provenance. Every nonzero entry keeps its
 *  contribution stack; every contribution keeps a stable identity. */
public final class Assembly {
    public static final double SYM_TOL = 1e-9;

    public static final class Contribution implements Comparable<Contribution> {
        public String id;          // ctr:<element>:<i>:<j>
        public String element;     // original element id
        public int localRow, localCol;
        public String rawValue;    // original representation preserved
        public double normalized;  // N/mm canonical
        public String unit;
        @Override public int compareTo(Contribution o) {
            int c = element.compareTo(o.element);
            if (c != 0) return c;
            c = Integer.compare(localRow, o.localRow);
            return c != 0 ? c : Integer.compare(localCol, o.localCol);
        }
    }

    public static final class Entry {
        public String id;                 // entry:<rowDof>:<colDof>  (physical identity)
        public String rowDof, colDof;     // physical global dof ids
        public int row, col;              // display indices (renumbering changes only these)
        public double value;
        public final List<Contribution> stack = new ArrayList<>();
    }

    public static final class Diagnostic {
        public String id;        // diag-NNN
        public String type;      // DUPLICATE_DOF | DEGENERATE_ELEMENT | ISOLATED_DOF | ...
        public String severity;  // error | warn | info
        public String message;
        public List<String> subjects = new ArrayList<>(); // stable ids of affected objects
        public Diagnostic(String id, String type, String severity, String msg) {
            this.id = id; this.type = type; this.severity = severity; this.message = msg;
        }
    }

    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();       // sorted by (rowDof,colDof)
        public final List<Diagnostic> diagnostics = new ArrayList<>();
        public final List<String> dofOrder = new ArrayList<>();     // physical dof ids, canonical order
        public final Map<String, Integer> dofIndex = new LinkedHashMap<>();
        public double insertionOrderMaxDiff = 0.0;                  // float-order sensitivity
        public final List<String> unconvertible = new ArrayList<>();
    }

    /** Canonical assembly: duplicate contributions summed in deterministic order
     *  (element id, local row, local col). Also computes an insertion-order sum to
     *  quantify floating-point summation-order sensitivity. */
    public static Result assemble(CaseData c) {
        Result r = new Result();
        // global dof universe: from mapping targets + node-derived dofs (1 dof per node)
        Set<String> dofs = new TreeSet<>();
        for (CaseData.Mapping m : c.mappings) dofs.add(m.global);
        for (CaseData.Node n : c.nodes) dofs.add("G@" + n.id); // physical dof per node
        // mapping lookup: (element, localDof) -> global
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        for (CaseData.Mapping m : c.mappings) {
            String key = m.element + "|" + m.local;
            if (map.containsKey(key)) {
                // conflicting re-map of same local dof: keep first, diagnose
                Diagnostic d = new Diagnostic("", "MAPPING_CONFLICT", "error",
                        "local dof " + key + " mapped twice: " + map.get(key) + " vs " + m.global);
                d.subjects.add(key);
                r.diagnostics.add(d);
                continue;
            }
            map.put(key, m.global);
            reverse.computeIfAbsent(m.global, k -> new ArrayList<>()).add(key);
        }
        // duplicate dof: same global dof receives >1 local dof
        int diagSeq = 1;
        for (var e : reverse.entrySet()) {
            if (e.getValue().size() > 1) {
                Diagnostic d = new Diagnostic("", "DUPLICATE_DOF", "warn",
                        "global dof " + e.getKey() + " receives " + e.getValue().size()
                                + " local dofs " + e.getValue() + "; contributions summed in deterministic order");
                d.subjects.add(e.getKey());
                d.subjects.addAll(e.getValue());
                r.diagnostics.add(d);
            }
        }
        r.dofOrder.addAll(dofs);
        for (int i = 0; i < r.dofOrder.size(); i++) r.dofIndex.put(r.dofOrder.get(i), i);

        // accumulate contributions per (rowDof, colDof)
        Map<String, Entry> acc = new TreeMap<>();
        Map<String, List<Contribution>> insertion = new LinkedHashMap<>();
        for (CaseData.Element el : c.elements) {
            // degenerate element: repeated node in connectivity
            Set<String> seen = new HashSet<>(el.connectivity);
            if (seen.size() != el.connectivity.size()) {
                Diagnostic d = new Diagnostic("", "DEGENERATE_ELEMENT", "error",
                        "element " + el.key + " has repeated nodes in connectivity " + el.connectivity);
                d.subjects.add(el.key);
                r.diagnostics.add(d);
            }
            int n = el.localDofOrder.size();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    String raw = el.localMatrix.get(i).get(j);
                    String gRow = map.get(el.key + "|" + el.localDofOrder.get(i));
                    String gCol = map.get(el.key + "|" + el.localDofOrder.get(j));
                    if (gRow == null || gCol == null) continue;
                    Contribution ct = new Contribution();
                    ct.id = "ctr:" + el.key + ":" + i + ":" + j;
                    ct.element = el.key; ct.localRow = i; ct.localCol = j;
                    ct.rawValue = raw; ct.unit = el.unit;
                    try {
                        ct.normalized = UnitConv.stiffnessToNperMm(raw, el.unit);
                    } catch (RuntimeException ex) {
                        r.unconvertible.add(ct.id);
                        Diagnostic d = new Diagnostic("", "UNIT_UNCONVERTIBLE", "error",
                                "value " + raw + " unit " + el.unit + " of " + ct.id
                                        + " not convertible; kept raw in place");
                        d.subjects.add(ct.id);
                        r.diagnostics.add(d);
                        continue; // value stays in raw layer, not silently dropped
                    }
                    String ek = gRow + "|" + gCol;
                    Entry en = acc.computeIfAbsent(ek, k -> {
                        Entry ne = new Entry();
                        ne.id = "entry:" + gRow + ":" + gCol;
                        ne.rowDof = gRow; ne.colDof = gCol;
                        return ne;
                    });
                    en.stack.add(ct);
                    insertion.computeIfAbsent(ek, k -> new ArrayList<>()).add(ct);
                }
            }
            // local matrix asymmetry
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                double a, b;
                try {
                    a = UnitConv.stiffnessToNperMm(el.localMatrix.get(i).get(j), el.unit);
                    b = UnitConv.stiffnessToNperMm(el.localMatrix.get(j).get(i), el.unit);
                } catch (RuntimeException ex) { continue; }
                if (Math.abs(a - b) > SYM_TOL * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)))) {
                    Diagnostic d = new Diagnostic("", "ASYMMETRIC_ENTRY", "warn",
                            "element " + el.key + " local k[" + i + "][" + j + "]="
                                    + el.localMatrix.get(i).get(j) + " != k[" + j + "][" + i + "]="
                                    + el.localMatrix.get(j).get(i));
                    d.subjects.add("ctr:" + el.key + ":" + i + ":" + j);
                    d.subjects.add("ctr:" + el.key + ":" + j + ":" + i);
                    r.diagnostics.add(d);
                }
            }
        }
        // deterministic summation: sort each stack by (element, i, j)
        for (Entry en : acc.values()) {
            Collections.sort(en.stack);
            double s = 0.0;
            for (Contribution ct : en.stack) s += ct.normalized;
            en.value = s;
            en.row = r.dofIndex.get(en.rowDof);
            en.col = r.dofIndex.get(en.colDof);
            r.entries.add(en);
        }
        // float summation-order sensitivity: insertion order vs canonical sorted order
        for (var e : insertion.entrySet()) {
            List<Contribution> ins = e.getValue();
            if (ins.size() < 2) continue;
            double sIns = 0.0;
            for (Contribution ct : ins) sIns += ct.normalized;
            List<Contribution> sorted = new ArrayList<>(ins);
            Collections.sort(sorted);
            double sSorted = 0.0;
            for (Contribution ct : sorted) sSorted += ct.normalized;
            r.insertionOrderMaxDiff = Math.max(r.insertionOrderMaxDiff, Math.abs(sIns - sSorted));
        }
        if (r.insertionOrderMaxDiff > 0.0) {
            Diagnostic d = new Diagnostic("", "FLOAT_ORDER_SENSITIVE", "info",
                    "summation order changes result by up to " + r.insertionOrderMaxDiff
                            + " under float mode " + c.floatMode + "; canonical order is sorted-contribution");
            r.diagnostics.add(d);
        }
        // isolated dofs: dof with no row and no column entry
        Set<String> touched = new HashSet<>();
        for (Entry en : r.entries) { touched.add(en.rowDof); touched.add(en.colDof); }
        for (String dof : r.dofOrder) {
            if (!touched.contains(dof)) {
                Diagnostic d = new Diagnostic("", "ISOLATED_DOF", "warn",
                        "global dof " + dof + " has no contributions (isolated)");
                d.subjects.add(dof);
                r.diagnostics.add(d);
            }
        }
        // constraint conflicts + strategy mixing
        applyConstraintDiagnostics(c, r);
        // assign stable diag ids
        for (Diagnostic d : r.diagnostics) d.id = String.format("diag-%03d", diagSeq++);
        return r;
    }

    static void applyConstraintDiagnostics(CaseData c, Result r) {
        Map<String, List<String>> byDof = new LinkedHashMap<>();
        for (CaseData.ConstraintSet.Entry ce : c.constraints.entries)
            byDof.computeIfAbsent(ce.dof, k -> new ArrayList<>()).add(ce.value);
        for (var e : byDof.entrySet()) {
            if (new HashSet<>(e.getValue()).size() > 1) {
                Diagnostic d = new Diagnostic("", "CONSTRAINT_CONFLICT", "error",
                        "dof " + e.getKey() + " constrained with conflicting values " + e.getValue());
                d.subjects.add(e.getKey());
                r.diagnostics.add(d);
            }
        }
        if (c.constraints.strategy.equals("mixed")) {
            Diagnostic d = new Diagnostic("", "STRATEGY_MIX", "error",
                    "constraint set " + c.constraints.version
                            + " mixes elimination and penalty; both candidate assemblies retained, none auto-selected");
            d.subjects.add(c.constraints.version);
            r.diagnostics.add(d);
        }
    }
}
