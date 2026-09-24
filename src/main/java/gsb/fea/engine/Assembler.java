package gsb.fea.engine;

import gsb.fea.model.AssemblyResult;
import gsb.fea.model.CandidateSet;
import gsb.fea.model.ConstraintDef;
import gsb.fea.model.ConstraintEffect;
import gsb.fea.model.Contribution;
import gsb.fea.model.Diagnostic;
import gsb.fea.model.DofMap;
import gsb.fea.model.Element;
import gsb.fea.model.FloatMode;
import gsb.fea.model.LocalMatrix;
import gsb.fea.model.Node;
import gsb.fea.model.Numbering;
import gsb.fea.model.Quantity;
import gsb.fea.model.SparseEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assembles the global sparse matrix from the imported sources and records the
 * provenance of every nonzero. Order is fully deterministic: elements are
 * processed by original id (ascending or descending per the float mode), and
 * contributions within an element follow the declared local order.
 */
public final class Assembler {

    private static final double SYMMETRY_TOLERANCE = 1.0e-12;
    private static final double DEGENERATE_AREA = 1.0e-9;
    private static final double DEFAULT_PENALTY = 1.0e12;

    private Assembler() {
    }

    public static CandidateSet assemble(CaseBundle bundle) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Numbering numbering = buildNumbering(bundle);
        List<Contribution> contributions = buildContributions(bundle, numbering, diagnostics);
        List<SparseEntry> fullEntries = aggregate(contributions, bundle.input().floatMode(), diagnostics);
        detectIsolatedDofs(bundle, numbering, contributions, diagnostics);

        List<String> constraintVersions = collectConstraintVersions(bundle.input().constraints());
        List<AssemblyResult> candidates = new ArrayList<>();
        for (String version : constraintVersions) {
            candidates.addAll(buildConstraintCandidates(bundle, numbering, contributions,
                    fullEntries, diagnostics, version));
        }
        if (constraintVersions.isEmpty()) {
            candidates.add(buildResult(bundle, numbering, contributions, fullEntries,
                    diagnostics, ConstraintEffect.ELIMINATION, "NONE", new ArrayList<>()));
        }

        boolean ambiguous = candidates.size() > 1
                || diagnostics.stream().anyMatch(d -> Diagnostic.CONSTRAINT_CONFLICT.equals(d.code()));
        return new CandidateSet(bundle.sourceHash(), candidates, diagnostics, ambiguous);
    }

    private static Numbering buildNumbering(CaseBundle bundle) {
        Set<String> keys = new LinkedHashSet<>();
        for (Node node : bundle.input().nodes()) {
            keys.add(bundle.input().dofKind() + "@" + node.physicalKey());
        }
        for (DofMap map : bundle.input().dofMaps()) {
            keys.addAll(map.localToGlobal().values());
        }
        return Numbering.natural("numbering:natural",
                "natural import order of physical dof keys", keys);
    }

    private static List<Contribution> buildContributions(CaseBundle bundle, Numbering numbering,
                                                         List<Diagnostic> diagnostics) {
        List<Element> elements = new ArrayList<>(bundle.input().elements());
        boolean ascending = bundle.input().floatMode().summationOrder()
                .startsWith(FloatMode.ORDER_ELEM_ASC_IJ.split(";")[0]);
        elements.sort(Comparator.comparing(Element::originalId));
        if (!ascending) {
            elements.sort(Comparator.comparing(Element::originalId).reversed());
        }

        List<Contribution> contributions = new ArrayList<>();
        int order = 0;
        for (Element element : elements) {
            checkElement(bundle, element, diagnostics);
            LocalMatrix matrix = bundle.input().localMatrix(element.originalId());
            DofMap dofMap = bundle.input().dofMap(element.originalId());
            if (matrix == null) {
                diagnostics.add(new Diagnostic(Diagnostic.MISSING_LOCAL_MATRIX, "ERROR",
                        "element " + element.originalId() + " has no local matrix",
                        List.of("element:" + element.originalId()), List.of()));
                continue;
            }
            checkSymmetry(element, matrix, diagnostics);
            List<String> labels = element.localDofOrder();
            List<String> globalKeys = labels.stream().map(lbl -> dofMap.localToGlobal().get(lbl)).toList();
            for (int i = 0; i < labels.size(); i++) {
                for (int j = 0; j < labels.size(); j++) {
                    Quantity q = matrix.rows().get(i).get(j);
                    if (!q.isUsable()) {
                        diagnostics.add(new Diagnostic(Diagnostic.UNCONVERTIBLE_VALUE, "WARN",
                                "local matrix value at (" + i + "," + j + ") of element "
                                        + element.originalId() + " is not numeric; kept at source",
                                List.of("element:" + element.originalId()), List.of()));
                        continue;
                    }
                    String gRow = globalKeys.get(i);
                    String gCol = globalKeys.get(j);
                    Integer ri = numbering.dofKeyToIndex().get(gRow);
                    Integer ci = numbering.dofKeyToIndex().get(gCol);
                    if (ri == null || ci == null) {
                        diagnostics.add(new Diagnostic(Diagnostic.ISOLATED_DOF, "ERROR",
                                "global dof missing from numbering: " + (ri == null ? gRow : gCol),
                                List.of(ri == null ? gRow : gCol), List.of()));
                        continue;
                    }
                    String id = Contribution.makeId(element.originalId(), labels.get(i), labels.get(j));
                    contributions.add(new Contribution(id, element.originalId(), labels.get(i), labels.get(j),
                            gRow, gCol, ri, ci, q.raw(), q.value(), q.unit(), order++,
                            Hashes.sha256(element.originalId() + "|" + q.raw() + "|" + gRow + "|" + gCol)));
                }
            }
        }
        return contributions;
    }

    private static void checkElement(CaseBundle bundle, Element element, List<Diagnostic> diagnostics) {
        List<String> affected = List.of("element:" + element.originalId());
        if (Geometry.hasRepeatedNode(element.nodeIds())) {
            diagnostics.add(new Diagnostic(Diagnostic.DEGENERATE_ELEMENT, "WARN",
                    "element " + element.originalId() + " references the same node more than once",
                    affected, List.of()));
            return;
        }
        if (element.nodeCount() == 3) {
            Node a = findNode(bundle, element.nodeIds().get(0));
            Node b = findNode(bundle, element.nodeIds().get(1));
            Node c = findNode(bundle, element.nodeIds().get(2));
            if (a != null && b != null && c != null) {
                Double area = Geometry.triangleArea(a, b, c);
                if (area != null && Math.abs(area) <= DEGENERATE_AREA) {
                    diagnostics.add(new Diagnostic(Diagnostic.DEGENERATE_ELEMENT, "WARN",
                            "element " + element.originalId() + " has zero/near-zero area ("
                                    + area + " " + areaUnit(a) + ")",
                            affected, List.of()));
                }
            }
        }
        List<String> globalKeys = bundle.input().dofMap(element.originalId()).localToGlobal().values().stream().toList();
        Set<String> unique = new LinkedHashSet<>(globalKeys);
        if (unique.size() < globalKeys.size()) {
            diagnostics.add(new Diagnostic(Diagnostic.REPEATED_DOF, "WARN",
                    "element " + element.originalId() + " maps multiple local dofs onto the same global dof",
                    affected, List.of()));
        }
    }

    private static String areaUnit(Node node) {
        String u = node.x() == null ? "" : node.x().unit();
        return (u == null || u.isEmpty()) ? "" : u + "^2";
    }

    private static Node findNode(CaseBundle bundle, String id) {
        for (Node node : bundle.input().nodes()) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    private static void checkSymmetry(Element element, LocalMatrix matrix, List<Diagnostic> diagnostics) {
        int n = matrix.size();
        double maxDiff = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Quantity a = matrix.rows().get(i).get(j);
                Quantity b = matrix.rows().get(j).get(i);
                if (a.isUsable() && b.isUsable()) {
                    maxDiff = Math.max(maxDiff, Math.abs(a.value() - b.value()));
                }
            }
        }
        if (maxDiff > SYMMETRY_TOLERANCE) {
            diagnostics.add(new Diagnostic(Diagnostic.ASYMMETRIC_LOCAL, "WARN",
                    "local matrix of element " + element.originalId()
                            + " is asymmetric (max |Kij-Kji|=" + maxDiff + "); assembled as-is, not symmetrized",
                    List.of("element:" + element.originalId()), List.of()));
        }
    }

    private static List<SparseEntry> aggregate(List<Contribution> contributions, FloatMode floatMode,
                                               List<Diagnostic> diagnostics) {
        Map<String, List<Contribution>> byEntry = new TreeMap<>(
                Comparator.comparingInt((String k) -> Integer.parseInt(k.substring(0, k.indexOf(':'))))
                        .thenComparingInt(k -> Integer.parseInt(k.substring(k.indexOf(':') + 1))));
        for (Contribution c : contributions) {
            byEntry.computeIfAbsent(c.globalRowIndex() + ":" + c.globalColIndex(), k -> new ArrayList<>()).add(c);
        }
        List<SparseEntry> entries = new ArrayList<>();
        boolean forward = floatMode.summationOrder().contains("ROW_MAJOR");
        for (List<Contribution> stack : byEntry.values()) {
            stack.sort(Comparator.comparingInt(Contribution::orderIndex));
            double fwd = sum(stack, true);
            double rev = sum(stack, false);
            double delta = Math.abs(fwd - rev);
            if (delta > 0.0 && forward) {
                String key = SparseEntry.entryId(stack.get(0).globalRowKey(), stack.get(0).globalColKey());
                diagnostics.add(new Diagnostic(Diagnostic.FLOAT_ORDER_DELTA, "INFO",
                        "entry " + key + " changes by " + delta + " when summation order is reversed",
                        List.of(key), List.of()));
            }
            double chosen = forward ? fwd : rev;
            double other = forward ? rev : fwd;
            StringBuilder expr = new StringBuilder();
            for (int k = 0; k < stack.size(); k++) {
                if (k > 0) {
                    expr.append(" + ");
                }
                expr.append(stack.get(k).rawValue());
                expr.append("[e").append(stack.get(k).elementOriginalId()).append("]");
            }
            entries.add(new SparseEntry(stack.get(0).globalRowIndex(), stack.get(0).globalColIndex(),
                    stack.get(0).globalRowKey(), stack.get(0).globalColKey(), chosen, expr.toString(),
                    stack.stream().map(Contribution::id).toList(), other, delta));
        }
        return entries;
    }

    private static double sum(List<Contribution> stack, boolean forward) {
        double acc = 0.0;
        if (forward) {
            for (Contribution c : stack) {
                acc += c.value();
            }
        } else {
            for (int k = stack.size() - 1; k >= 0; k--) {
                acc += stack.get(k).value();
            }
        }
        return acc;
    }

    private static void detectIsolatedDofs(CaseBundle bundle, Numbering numbering,
                                           List<Contribution> contributions, List<Diagnostic> diagnostics) {
        Set<String> touched = new LinkedHashSet<>();
        for (Contribution c : contributions) {
            touched.add(c.globalRowKey());
            touched.add(c.globalColKey());
        }
        List<String> isolated = new ArrayList<>();
        for (String key : numbering.dofKeyToIndex().keySet()) {
            if (!touched.contains(key)) {
                isolated.add(key);
            }
        }
        for (String key : isolated) {
            diagnostics.add(new Diagnostic(Diagnostic.ISOLATED_DOF, "WARN",
                    "dof " + key + " receives no contribution (isolated node)",
                    List.of(key), List.of()));
        }
    }

    private static List<String> collectConstraintVersions(List<ConstraintDef> constraints) {
        Set<String> versions = new LinkedHashSet<>();
        for (ConstraintDef c : constraints) {
            versions.add(c.version());
        }
        return new ArrayList<>(versions);
    }

    private static List<AssemblyResult> buildConstraintCandidates(CaseBundle bundle, Numbering numbering,
                                                                  List<Contribution> contributions,
                                                                  List<SparseEntry> fullEntries,
                                                                  List<Diagnostic> sharedDiagnostics,
                                                                  String version) {
        List<ConstraintDef> constraints = bundle.input().constraints().stream()
                .filter(c -> c.version().equals(version)).toList();
        Map<String, List<ConstraintDef>> byDof = new LinkedHashMap<>();
        for (ConstraintDef c : constraints) {
            byDof.computeIfAbsent(c.globalDofKey(), k -> new ArrayList<>()).add(c);
        }
        List<List<ConstraintDef>> choices = new ArrayList<>();
        List<String> conflictDofs = new ArrayList<>();
        for (Map.Entry<String, List<ConstraintDef>> e : byDof.entrySet()) {
            List<ConstraintDef> distinct = new ArrayList<>();
            for (ConstraintDef d : e.getValue()) {
                if (distinct.stream().noneMatch(x -> x.value().raw().equals(d.value().raw()))) {
                    distinct.add(d);
                }
            }
            if (distinct.size() > 1) {
                conflictDofs.add(e.getKey());
            }
            choices.add(distinct);
        }
        List<List<ConstraintDef>> combos = cartesian(choices);

        List<AssemblyResult> results = new ArrayList<>();
        for (List<ConstraintDef> resolved : combos) {
            for (String strategy : List.of(ConstraintEffect.ELIMINATION, ConstraintEffect.PENALTY)) {
                List<Diagnostic> local = new ArrayList<>(sharedDiagnostics);
                if (!conflictDofs.isEmpty()) {
                    String candidateId = candidateId(bundle, version, strategy, resolved);
                    local.add(new Diagnostic(Diagnostic.CONSTRAINT_CONFLICT, "WARN",
                            "constraint version " + version
                                    + " prescribes more than one value to the same dof; legal candidates retained",
                            new ArrayList<>(conflictDofs), List.of(candidateId)));
                }
                results.add(buildResult(bundle, numbering, contributions, fullEntries, local,
                        strategy, version, resolved));
            }
        }
        return results;
    }

    private static List<List<ConstraintDef>> cartesian(List<List<ConstraintDef>> choices) {
        List<List<ConstraintDef>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (List<ConstraintDef> alternatives : choices) {
            List<List<ConstraintDef>> next = new ArrayList<>();
            for (List<ConstraintDef> prefix : combos) {
                for (ConstraintDef alt : alternatives) {
                    List<ConstraintDef> grown = new ArrayList<>(prefix);
                    grown.add(alt);
                    next.add(grown);
                }
            }
            combos = next;
        }
        return combos;
    }

    private static AssemblyResult buildResult(CaseBundle bundle, Numbering numbering,
                                              List<Contribution> contributions, List<SparseEntry> fullEntries,
                                              List<Diagnostic> diagnostics, String strategy,
                                              String version, List<ConstraintDef> resolved) {
        Map<String, ConstraintEffect> effects = new LinkedHashMap<>();
        for (ConstraintDef c : resolved) {
            Integer idx = numbering.dofKeyToIndex().get(c.globalDofKey());
            double penalty = ConstraintEffect.PENALTY.equals(strategy) ? DEFAULT_PENALTY : 0.0;
            effects.put(c.globalDofKey(), new ConstraintEffect(c.id(), c.globalDofKey(),
                    idx == null ? -1 : idx, strategy, c.value().raw(),
                    c.value().isUsable() ? c.value().value() : Double.NaN, penalty, c.value().unit()));
        }

        Set<Integer> fixed = new LinkedHashSet<>();
        for (ConstraintEffect e : effects.values()) {
            if (e.index() >= 0) {
                fixed.add(e.index());
            }
        }
        List<SparseEntry> reduced = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (int idx : numbering.dofKeyToIndex().values()) {
            if (!fixed.contains(idx)) {
                free.add(idx);
            }
        }
        for (SparseEntry entry : fullEntries) {
            boolean rowFree = !fixed.contains(entry.rowIndex());
            boolean colFree = !fixed.contains(entry.colIndex());
            if (ConstraintEffect.ELIMINATION.equals(strategy)) {
                if (rowFree && colFree) {
                    reduced.add(entry);
                }
            } else {
                if (rowFree && colFree) {
                    reduced.add(entry);
                } else if (!rowFree || !colFree) {
                    if (rowFree == colFree) {
                        reduced.add(new SparseEntry(entry.rowIndex(), entry.colIndex(), entry.rowKey(),
                                entry.colKey(), entry.value() + DEFAULT_PENALTY,
                                entry.rawExpression() + " + penalty[" + DEFAULT_PENALTY + "]",
                                entry.contributionIds(), entry.altOrderValue(), entry.orderDelta()));
                    }
                }
            }
        }

        List<String> isolated = numbering.dofKeyToIndex().keySet().stream()
                .filter(k -> contributions.stream().noneMatch(c -> c.globalRowKey().equals(k)
                        || c.globalColKey().equals(k)))
                .toList();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (SparseEntry e : fullEntries) {
            reasons.put(SparseEntry.entryId(e.rowKey(), e.colKey()),
                    "sum of " + e.contributionIds().size() + " contribution(s) in deterministic order "
                            + bundle.input().floatMode().summationOrder());
        }

        String id = candidateId(bundle, version, strategy, resolved);
        return new AssemblyResult(id, bundle.sourceHash(), version, strategy, numbering,
                contributions, fullEntries, reduced, free, new ArrayList<>(effects.values()),
                diagnostics, isolated, reasons,
                bundle.input().floatMode().arithmetic() + " / " + bundle.input().floatMode().summationOrder());
    }

    private static String candidateId(CaseBundle bundle, String version, String strategy,
                                      List<ConstraintDef> resolved) {
        String pick = resolved.stream().map(c -> c.id() + "=" + c.value().raw())
                .reduce("", (a, b) -> a + "|" + b);
        return "assembly:" + bundle.sourceHash() + ":" + version + ":" + strategy + ":" + Hashes.sha256(pick);
    }
}
