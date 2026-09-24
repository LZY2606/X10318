package org.gsb.fem.rules;

import org.gsb.fem.domain.AssemblyResult;
import org.gsb.fem.domain.Constraint;
import org.gsb.fem.domain.ConstraintEffect;
import org.gsb.fem.domain.ConstraintSet;
import org.gsb.fem.domain.Contribution;
import org.gsb.fem.domain.Diagnostic;
import org.gsb.fem.domain.DofMap;
import org.gsb.fem.domain.DofMapping;
import org.gsb.fem.domain.Element;
import org.gsb.fem.domain.GlobalEntry;
import org.gsb.fem.domain.LocalEntry;
import org.gsb.fem.domain.LocalMatrix;
import org.gsb.fem.domain.Node;
import org.gsb.fem.domain.RawValue;
import org.gsb.fem.domain.RenumberSpec;
import org.gsb.fem.domain.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic finite-element assembly with full provenance.
 *
 * Every derived object carries a stable identity back to its raw source. Edge
 * cases (duplicate dofs, degenerate elements, isolated nodes/dofs, constraint
 * conflicts, float order variance, renumbering, local asymmetry) produce
 * explicit diagnostics instead of being silently collapsed.
 */
public final class AssemblyEngine {
    private static final double SYM_TOL = 1.0e-9;
    private static final double ORDER_TOL = 1.0e-12;

    private final Snapshot snap;
    private final String dofMapId;
    private final String constraintSetId;
    private final String renumberId;
    private final String floatSpecId;

    private final AssemblyResult result = new AssemblyResult();
    private final List<Contribution> contributions = new ArrayList<>();
    private final Map<String, String> dofOf = new HashMap<>();
    private final List<String> universe = new ArrayList<>();
    private final Map<String, String> elementUnit = new HashMap<>();

    private AssemblyEngine(Snapshot snap, String dofMapId, String constraintSetId,
                           String renumberId, String floatSpecId) {
        this.snap = snap;
        this.dofMapId = idOr(dofMapId, snap.selectedDofMapId);
        this.constraintSetId = idOr(constraintSetId, snap.selectedConstraintSetId);
        this.renumberId = idOr(renumberId, snap.selectedRenumberId);
        this.floatSpecId = idOr(floatSpecId, snap.selectedFloatSpecId);
    }

    public static AssemblyResult assemble(Snapshot snap, String dofMapId, String constraintSetId,
                                          String renumberId, String floatSpecId) {
        AssemblyEngine engine = new AssemblyEngine(snap, dofMapId, constraintSetId, renumberId, floatSpecId);
        return engine.run();
    }

    public static AssemblyResult assembleSelected(Snapshot snap) {
        return assemble(snap, null, null, null, null);
    }

    private static String idOr(String explicit, String selected) {
        return explicit != null ? explicit : selected;
    }

    private AssemblyResult run() {
        result.snapshotId = snap.id;
        result.snapshotHash = snap.contentHash;
        result.dofMapId = dofMapId;
        result.constraintSetId = constraintSetId;
        result.renumberId = renumberId;
        result.floatSpecId = floatSpecId;
        result.summation = resolveSummation();
        result.strategy = "NONE";

        buildUniverse();
        diagnoseNodesAndElements();
        scatterElements();
        ConstraintSet bc = snap.constraintSetOf(constraintSetId);
        if (bc != null) {
            applyPenalties(bc);
        }
        groupAndSum();
        markGlobalAsymmetry();
        if (bc != null) {
            result.strategy = validStrategy(bc.strategy) ? bc.strategy : bc.strategy;
            applyElimination(bc);
        }
        diagnoseIsolated();
        applyRenumber();

        result.complete = !result.hasErrors();
        result.contentHash = Jsons.hash(HashView.of(result));
        result.id = "AR-" + result.contentHash.substring(0, 12);
        return result;
    }

    private String resolveSummation() {
        if (floatSpecId == null) {
            return Summation.DOCUMENT_ORDER;
        }
        for (var fs : snap.floatSpecs) {
            if (fs.id.equals(floatSpecId) && fs.summation != null) {
                return fs.summation;
            }
        }
        diag("MISSING_FLOAT_SPEC", "ERROR", "floatSpec", floatSpecId,
                "Float spec " + floatSpecId + " not found; assembly cannot be reproduced");
        return Summation.DOCUMENT_ORDER;
    }

    private void buildUniverse() {
        DofMap dm = snap.dofMapOf(dofMapId);
        if (dm == null) {
            diag("MISSING_DOF_MAP", "ERROR", "dofMap", String.valueOf(dofMapId),
                    "Degree of freedom map not found");
            return;
        }
        for (DofMapping m : dm.mappings) {
            if (!universe.contains(m.globalDof)) {
                universe.add(m.globalDof);
            }
            dofOf.put(m.elementId + "|" + m.localDof, m.globalDof);
        }
        ConstraintSet bc = snap.constraintSetOf(constraintSetId);
        if (bc != null) {
            for (Constraint c : bc.constraints) {
                if (!universe.contains(c.dofId)) {
                    universe.add(c.dofId);
                }
            }
        }
        result.allDofIds.addAll(universe);
    }

    private void diagnoseNodesAndElements() {
        for (Element e : snap.elements) {
            for (String nid : e.nodeIds) {
                if (snap.nodeOf(nid) == null) {
                    diag("MISSING_NODE", "WARN", "element", e.id,
                            "Element " + e.id + " references missing node " + nid);
                }
            }
            if (hasDuplicates(e.nodeIds)) {
                diag("DEGENERATE_ELEMENT", "WARN", "element", e.id,
                        "Element " + e.id + " has repeated node ids in connectivity: " + e.nodeIds);
            } else if (zeroArea(e)) {
                diag("DEGENERATE_ELEMENT", "WARN", "element", e.id,
                        "Element " + e.id + " has zero area under frame "
                                + (snap.nodeOf(e.nodeIds.get(0)) == null
                                ? "?" : snap.nodeOf(e.nodeIds.get(0)).frame));
            }
        }
        for (Node n : snap.nodes) {
            boolean used = false;
            for (Element e : snap.elements) {
                if (e.nodeIds.contains(n.id)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                diag("ISOLATED_NODE", "WARN", "node", n.id,
                        "Node " + n.id + " is not referenced by any element connectivity");
            }
        }
    }

    private boolean zeroArea(Element e) {
        if (!"tri3".equals(e.structuralType) || e.nodeIds.size() != 3) {
            return false;
        }
        Node a = snap.nodeOf(e.nodeIds.get(0));
        Node b = snap.nodeOf(e.nodeIds.get(1));
        Node c = snap.nodeOf(e.nodeIds.get(2));
        if (a == null || b == null || c == null
                || a.x == null || b.x == null || c.x == null
                || a.y == null || b.y == null || c.y == null) {
            return false;
        }
        UnitRegistry.normalize(a.x);
        UnitRegistry.normalize(a.y);
        UnitRegistry.normalize(b.x);
        UnitRegistry.normalize(b.y);
        UnitRegistry.normalize(c.x);
        UnitRegistry.normalize(c.y);
        if (a.x.value == null || b.x.value == null || c.x.value == null
                || a.y.value == null || b.y.value == null || c.y.value == null) {
            return false;
        }
        double area = Math.abs((b.x.value - a.x.value) * (c.y.value - a.y.value)
                - (c.x.value - a.x.value) * (b.y.value - a.y.value)) / 2.0;
        return area < 1.0e-12;
    }

    private void scatterElements() {
        for (Element e : snap.elements) {
            LocalMatrix lm = snap.matrixOf(e.matrixId);
            if (lm == null) {
                diag("MISSING_LOCAL_MATRIX", "ERROR", "element", e.id,
                        "Element " + e.id + " references missing local matrix " + e.matrixId);
                continue;
            }
            checkLocalAsymmetry(e, lm);
            String firstUnit = firstConvertibleUnit(lm);
            if (firstUnit != null) {
                elementUnit.put(e.id, firstUnit);
            }
            int cell = 0;
            for (LocalEntry le : lm.entries) {
                cell++;
                String lr = dofName(lm, le.row);
                String lc = dofName(lm, le.col);
                String gr = dofOf.get(e.id + "|" + lr);
                String gc = dofOf.get(e.id + "|" + lc);
                String cid = "CON-" + e.id + "-L" + le.row + "-L" + le.col;
                if (gr == null || gc == null) {
                    Diagnostic d = new Diagnostic("MAPPING_MISSING", "ERROR", "contribution", cid,
                            "No global dof mapping for element " + e.id + " local dof "
                                    + (gr == null ? lr : lc));
                    result.diagnostics.add(d);
                    continue;
                }
                if (gr.equals(gc)) {
                    diag("DUPLICATE_DOF_MAPPING", "WARN", "element", e.id,
                            "Local dofs map to the same global dof " + gr
                                    + " in element " + e.id + " at cell (" + lr + "," + lc + ")")
                            .related(cid);
                }
                Contribution con = new Contribution();
                con.id = cid;
                con.elementId = e.id;
                con.localDofRow = lr;
                con.localDofCol = lc;
                con.globalRowId = gr;
                con.globalColId = gc;
                con.rawText = le.value == null ? "" : le.value.raw;
                con.rawUnit = le.value == null ? "" : le.value.unit;
                con.valueFrame = le.value == null ? "" : le.value.frame;
                con.orderIndex = contributions.size();
                RawValue normalized = le.value == null ? null : copyNormalize(le.value);
                if (normalized != null) {
                    con.factor = factorOf(le.value);
                    if (normalized.value == null || normalized.error != null) {
                        con.normalizedValue = null;
                        diag("UNCONVERTIBLE_VALUE", "WARN", "contribution", cid,
                                "Value '" + con.rawText + " " + con.rawUnit
                                        + "' cannot be normalized; kept at source")
                                .related(gr).related(gc);
                    } else {
                        con.normalizedValue = normalized.value;
                        con.valueUnit = normalized.baseUnit;
                        checkDimension(cid, normalized, firstUnit);
                    }
                }
                contributions.add(con);
                cell++;
            }
        }
    }

    private void checkDimension(String cid, RawValue normalized, String firstUnit) {
        if (firstUnit == null || normalized.baseUnit == null || normalized.baseUnit.isEmpty()) {
            return;
        }
        String dimA = UnitRegistry.dimensionOf(firstUnit);
        String dimB = UnitRegistry.dimensionOf(normalized.baseUnit.equals(firstUnit)
                ? firstUnit : baseSymbolFor(normalized.baseUnit));
        if (dimA != null && dimB != null && !dimA.equals(dimB)) {
            diag("DIMENSION_MISMATCH", "ERROR", "contribution", cid,
                    "Contribution unit dimension " + dimB + " conflicts with matrix dimension " + dimA)
                    .related(cid);
        }
    }

    private String baseSymbolFor(String base) {
        for (String symbol : new String[]{"N/mm", "N/m", "kN/mm", "kN/m", "m", "mm", "N", "kN", "Pa", "MPa"}) {
            var u = UnitRegistry.of(symbol);
            if (u != null && u.base().equals(base)) {
                return symbol;
            }
        }
        return base;
    }

    private double factorOf(RawValue rv) {
        var u = UnitRegistry.of(rv.unit);
        return u == null ? 1.0 : u.factorToBase();
    }

    private RawValue copyNormalize(RawValue rv) {
        RawValue copy = new RawValue(rv.raw, rv.unit, rv.frame);
        UnitRegistry.normalize(copy);
        return copy;
    }

    private String firstConvertibleUnit(LocalMatrix lm) {
        for (LocalEntry le : lm.entries) {
            if (le.value != null && le.value.unit != null && !le.value.unit.isEmpty()) {
                var u = UnitRegistry.of(le.value.unit);
                if (u != null) {
                    return le.value.unit;
                }
            }
        }
        return null;
    }

    private String dofName(LocalMatrix lm, int index) {
        if (index >= 0 && index < lm.dofOrder.size()) {
            return lm.dofOrder.get(index);
        }
        return "L" + index;
    }

    private void checkLocalAsymmetry(Element e, LocalMatrix lm) {
        Map<String, Double> cells = new HashMap<>();
        Map<String, RawValue> raw = new HashMap<>();
        for (LocalEntry le : lm.entries) {
            RawValue rv = copyNormalize(le.value);
            if (rv.value != null) {
                cells.put(le.row + ":" + le.col, rv.value);
                raw.put(le.row + ":" + le.col, le.value);
            }
        }
        List<String> reported = new ArrayList<>();
        for (LocalEntry le : lm.entries) {
            if (le.row == le.col) {
                continue;
            }
            String key = le.row + ":" + le.col;
            String mirror = le.col + ":" + le.row;
            if (reported.contains(key) || reported.contains(mirror) || !cells.containsKey(mirror)) {
                continue;
            }
            double a = cells.get(key);
            double b = cells.get(mirror);
            double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
            if (Math.abs(a - b) > SYM_TOL * scale) {
                diag("LOCAL_MATRIX_ASYMMETRIC", "WARN", "localMatrix", lm.id,
                        "Local matrix " + lm.id + " of element " + e.id
                                + " is asymmetric at (" + dofName(lm, le.row) + ","
                                + dofName(lm, le.col) + "): " + a + " vs " + b
                                + (Boolean.TRUE.equals(lm.declaredSymmetric)
                                ? " although declared symmetric" : ""))
                        .related(e.id);
                reported.add(key);
            }
        }
    }
