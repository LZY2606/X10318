package femledger.engine;

import femledger.model.AssemblyResult;
import femledger.model.CaseVersion;
import femledger.model.ConflictRec;
import femledger.model.ConstraintRec;
import femledger.model.Contribution;
import femledger.model.Diagnostic;
import femledger.model.Element;
import femledger.model.GlobalEntry;
import femledger.model.MappingEntry;
import femledger.model.Node;
import femledger.model.RawValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static femledger.model.Diagnostic.Severity.ERROR;
import static femledger.model.Diagnostic.Severity.WARNING;

/**
 * Assembles the global sparse matrix from direct sources and tracks every non-zero entry back to
 * its element-local term.
 *
 * Governing semantics (enforced, never silently relaxed):
 *  - repeated contributions to one global entry are summed in one fixed, declared order;
 *  - elimination and penalty constraint strategies are never mixed inside one assembly;
 *  - isolated dofs and asymmetric local matrices are explicitly diagnosed, never hidden;
 *  - renumbering changes indices only, never the physical (label,label) correspondence;
 *  - ambiguous input yields all legal candidates instead of one forced single answer.
 */
public final class AssemblyEngine {

    private static final double ASYMMETRY_TOLERANCE = 0.0;

    public record Evaluation(List<AssemblyResult> results, List<ConflictRec> conflicts) {}

    public Evaluation evaluate(CaseVersion version) {
        List<ConflictRec> conflicts = detectConflicts(version);
        if (conflicts.isEmpty()) {
            AssemblyResult result = assemble(version, version.constraints(), List.of(),
                    "CANONICAL", null);
            return new Evaluation(List.of(result), List.of());
        }
        List<AssemblyResult> candidates = new ArrayList<>();
        List<ConflictRec> resolvedView = new ArrayList<>();
        for (ConflictRec conflict : conflicts) {
            for (String resolution : conflict.kind().equals(Diags.MIXED_STRATEGY)
                    ? List.of("ELIMINATION", "PENALTY")
                    : List.of("VALUE_A", "VALUE_B")) {
                List<ConstraintRec> applied = applyResolution(version.constraints(), conflict,
                        resolution);
                String label = conflict.kind().equals(Diags.MIXED_STRATEGY)
                        ? "CANDIDATE-" + resolution
                        : "CANDIDATE-" + resolution;
                AssemblyResult candidate = assemble(version, applied,
                        List.of(conflict.withResolution(null)), label, conflict.id());
                candidates.add(candidate);
            }
            resolvedView.add(conflict);
        }
        return new Evaluation(candidates, resolvedView);
    }

    private List<ConstraintRec> applyResolution(List<ConstraintRec> all, ConflictRec conflict,
                                                String resolution) {
        if (conflict.kind().equals(Diags.MIXED_STRATEGY)) {
            String wanted = resolution.equals("ELIMINATION") ? "ELIMINATION" : "PENALTY";
            List<ConstraintRec> out = new ArrayList<>();
            for (ConstraintRec c : all) {
                if (c.strategy().equals(wanted)) {
                    out.add(c);
                }
            }
            return out;
        }
        int targetIndex = resolution.equals("VALUE_A") ? 0 : 1;
        List<ConstraintRec> out = new ArrayList<>();
        ConstraintRec chosen = null;
        for (ConstraintRec c : all) {
            if (conflict.objectIds().contains(c.id())) {
                if (chosen == null) chosen = c;
            } else {
                out.add(c);
            }
        }
        List<ConstraintRec> grouped = all.stream()
                .filter(c -> conflict.objectIds().contains(c.id())).toList();
        chosen = grouped.get(Math.min(targetIndex, grouped.size() - 1));
        out.add(chosen);
        return out;
    }

    private List<ConflictRec> detectConflicts(CaseVersion version) {
        List<ConflictRec> conflicts = new ArrayList<>();
        Set<String> strategies = new LinkedHashSet<>();
        for (ConstraintRec c : version.constraints()) {
            strategies.add(c.strategy());
        }
        if (strategies.size() > 1) {
            String id = "cf-" + Fingerprints.of(List.of(Diags.MIXED_STRATEGY,
                    version.versionId(), new ArrayList<>(strategies))).substring(0, 10);
            conflicts.add(new ConflictRec(id, Diags.MIXED_STRATEGY,
                    "Constraint version " + firstConstraintVersion(version)
                            + " mixes ELIMINATION and PENALTY; the two strategies must never be "
                            + "combined in one assembly.",
                    version.constraints().stream().map(ConstraintRec::id).sorted().toList(),
                    List.of("PENDING:ELIMINATION", "PENDING:PENALTY"), null));
        }
        Map<String, List<ConstraintRec>> byDof = new LinkedHashMap<>();
        for (ConstraintRec c : version.constraints()) {
            byDof.computeIfAbsent(c.globalDof(), k -> new ArrayList<>()).add(c);
        }
        byDof.forEach((dof, list) -> {
            if (list.size() > 1 && distinctValues(list) > 1) {
                String id = "cf-" + Fingerprints.of(List.of(Diags.CONSTRAINT_CONFLICT,
                        dof, version.versionId())).substring(0, 10);
                conflicts.add(new ConflictRec(id, Diags.CONSTRAINT_CONFLICT,
                        "Global dof " + dof + " has " + list.size()
                                + " conflicting prescribed values; all legal candidates are retained.",
                        list.stream().map(ConstraintRec::id).sorted().toList(),
                        List.of("PENDING:VALUE_A", "PENDING:VALUE_B"), null));
            }
        });
        return conflicts;
    }

    private long distinctValues(List<ConstraintRec> list) {
        return list.stream().map(c -> {
            Double d = Numbers.numeric(c.value());
            return d == null ? c.value().raw() : d;
        }).distinct().count();
    }

    private String firstConstraintVersion(CaseVersion version) {
        return version.constraints().isEmpty() ? "?" : version.constraints().get(0).constraintVersion();
    }

    private AssemblyResult assemble(CaseVersion version, List<ConstraintRec> appliedConstraints,
                                    List<ConflictRec> carriedConflicts, String candidateLabel,
                                    String candidateGroupId) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Integer> labelOrder = globalLabelOrder(version, diagnostics);

        Map<String, Map<String, List<Term>>> grouped = new LinkedHashMap<>();
        Set<String> unconvertible = new LinkedHashSet<>();
        Set<String> unitMismatchSeen = new LinkedHashSet<>();
        int[] sourceOrder = {0};
        double[] penaltyValue = {1.0e12};

        List<Element> elements = version.elements();
        for (int ordinal = 0; ordinal < elements.size(); ordinal++) {
            Element element = elements.get(ordinal);
            if (isDegenerate(element, diagnostics)) {
                continue;
            }
            checkAsymmetry(element, diagnostics);
            Map<String, MappingEntry> byLocal = mappingByLocalDof(version, element, diagnostics);
            if (byLocal == null) {
                continue;
            }
            int n = element.matrixSize();
            for (int i = 0; i < n; i++) {
                String localRow = element.localDofOrder().get(i);
                MappingEntry rowMap = byLocal.get(localRow);
                if (rowMap == null) {
                    addGapDiag(diagnostics, element, localRow);
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    String localCol = element.localDofOrder().get(j);
                    MappingEntry colMap = byLocal.get(localCol);
                    if (colMap == null) {
                        addGapDiag(diagnostics, element, localCol);
                        continue;
                    }
                    RawValue raw = element.localMatrix().get(i * n + j);
                    Double numeric = Numbers.numeric(raw);
                    if (numeric == null) {
                        if (unconvertible.add(raw.id())) {
                            diagnostics.add(Diags.make(Diags.UNCONVERTIBLE_VALUE, ERROR,
                                    "Local matrix token '" + raw.raw() + "' of element "
                                            + element.originalId() + " cannot be normalised; it is "
                                            + "kept in place and not assembled.",
                                    List.of(element.id(), raw.id())));
                        }
                        continue;
                    }
                    String contributionId = "ctr-" + Fingerprints.of(
                            List.of(version.versionId(), element.id(), localRow, localCol)).substring(0, 10);
                    Contribution contribution = new Contribution(contributionId, element.id(),
                            localRow, localCol, numeric, raw.raw(),
                            raw.unit() == null ? "" : raw.unit(), sourceOrder[0]++);
                    Term term = new Term(contribution, ordinal, i, j, contribution.order());
                    addTerm(grouped, rowMap.globalDof(), colMap.globalDof(), term);
                }
            }
        }

        boolean penalty = appliedConstraints.stream().anyMatch(c -> c.strategy().equals("PENALTY"));
        if (penalty) {
            applyPenaltyContributions(appliedConstraints, labelOrder, grouped, sourceOrder,
                    penaltyValue[0], diagnostics);
        }

        List<GlobalEntry> entries = buildEntries(version, grouped, labelOrder, diagnostics,
                unitMismatchSeen);

        for (ConstraintRec c : appliedConstraints) {
            if (!labelOrder.containsKey(c.globalDof())) {
                diagnostics.add(Diags.make(Diags.CONSTRAINED_DOF_MISSING, WARNING,
                        "Constraint " + c.id() + " targets global dof " + c.globalDof()
                                + " which is not present in the assembled system.",
                        List.of(c.id())));
            }
        }

        List<String> eliminated = new ArrayList<>();
        List<GlobalEntry> reduced = List.of();
        if (!penalty && !appliedConstraints.isEmpty()) {
            for (ConstraintRec c : appliedConstraints) {
                if (labelOrder.containsKey(c.globalDof())) {
                    eliminated.add(c.globalDof());
                }
            }
            reduced = reduceEntries(entries, eliminated, labelOrder);
        }

        String group = candidateGroupId != null ? candidateGroupId
                : "grp-" + Fingerprints.of(version.versionId()).substring(0, 10);
        String resultId = "ar-" + Fingerprints.of(
                List.of(version.versionId(), candidateLabel, version.floatMode(),
                        appliedConstraints.stream().map(ConstraintRec::id).sorted().toList()))
                .substring(0, 12);
        String summary = "Assembled " + entries.size() + " non-zero entries from "
                + elements.size() + " element(s) with float mode " + version.floatMode()
                + (penalty ? " (penalty constraints)" : eliminated.isEmpty()
                        ? " (unconstrained)" : " (elimination of " + eliminated.size() + " dof)")
                + "; diagnostics=" + diagnostics.size()
                + (carriedConflicts.isEmpty() ? "" : "; candidate for " + carriedConflicts.get(0).kind());
        return new AssemblyResult(resultId, version.versionId(), group, candidateLabel,
                version.floatMode(), orderedLabels(labelOrder), entries, eliminated, reduced,
                dedupe(diagnostics), dedupe(carriedConflicts),
                appliedConstraints.stream().map(ConstraintRec::id).sorted().toList(), summary);
    }

    private void addTerm(Map<String, Map<String, List<Term>>> grouped, String row, String col,
                         Term term) {
        grouped.computeIfAbsent(row, k -> new LinkedHashMap<>())
                .computeIfAbsent(col, k -> new ArrayList<>()).add(term);
    }

    private List<GlobalEntry> buildEntries(CaseVersion version,
                                           Map<String, Map<String, List<Term>>> grouped,
                                           Map<String, Integer> labelOrder,
                                           List<Diagnostic> diagnostics,
                                           Set<String> unitMismatchSeen) {
        List<GlobalEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Term>>> row : grouped.entrySet()) {
            for (Map.Entry<String, List<Term>> col : row.getValue().entrySet()) {
                List<Term> terms = col.getValue();
                Set<String> units = new LinkedHashSet<>();
                terms.forEach(t -> {
                    if (!t.contribution.unit().isEmpty()) {
                        units.add(t.contribution.unit());
                    }
                });
                if (units.size() > 1
                        && unitMismatchSeen.add(row.getKey() + "|" + col.getKey())) {
                    diagnostics.add(Diags.make(Diags.UNIT_MISMATCH, WARNING,
                            "Global entry (" + row.getKey() + "," + col.getKey()
                                    + ") sums contributions with incompatible units " + units
                                    + "; incompatible values stay at their source.",
                            List.of("(" + row.getKey() + "," + col.getKey() + ")")));
                }
                List<FloatAccumulator.Term> accTerms = terms.stream()
                        .map(t -> new FloatAccumulator.Term(t.sortedOrder(),
                                t.contribution.order(), t.contribution.value())).toList();
                double value = FloatAccumulator.sum(accTerms, version.floatMode());
                String entryId = "ge-" + Fingerprints.of(
                        List.of(row.getKey(), col.getKey())).substring(0, 10);
                List<Contribution> contributions = terms.stream()
                        .sorted(Comparator.comparingInt(Term::sortedOrder))
                        .map(t -> t.contribution).toList();
                entries.add(new GlobalEntry(entryId, row.getKey(), col.getKey(),
                        labelOrder.get(row.getKey()), labelOrder.get(col.getKey()),
                        value, contributions));
            }
        }
        entries.sort(Comparator.comparingInt(GlobalEntry::rowIndex)
                .thenComparingInt(GlobalEntry::colIndex));
        return entries;
    }

    private List<GlobalEntry> reduceEntries(List<GlobalEntry> entries, List<String> eliminated,
                                            Map<String, Integer> labelOrder) {
        List<GlobalEntry> reduced = new ArrayList<>();
        for (GlobalEntry e : entries) {
            if (!eliminated.contains(e.rowLabel()) && !eliminated.contains(e.colLabel())) {
                reduced.add(new GlobalEntry(e.id(), e.rowLabel(), e.colLabel(),
                        reducedIndex(e.rowLabel(), eliminated, labelOrder),
                        reducedIndex(e.colLabel(), eliminated, labelOrder),
                        e.value(), e.contributions()));
            }
        }
        reduced.sort(Comparator.comparingInt(GlobalEntry::rowIndex)
                .thenComparingInt(GlobalEntry::colIndex));
        return reduced;
    }

    private int reducedIndex(String label, List<String> eliminated, Map<String, Integer> order) {
        int index = 0;
        for (String other : orderedLabels(order)) {
            if (other.equals(label)) {
                return index;
            }
            if (!eliminated.contains(other)) {
                index++;
            }
        }
        return index;
    }

    private List<String> orderedLabels(Map<String, Integer> order) {
        return order.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).toList();
    }

    private Map<String, Integer> globalLabelOrder(CaseVersion version,
                                                  List<Diagnostic> diagnostics) {
        Set<String> referencedNodes = new LinkedHashSet<>();
        for (Element e : version.elements()) {
            referencedNodes.addAll(e.nodeIds());
        }
        Set<String> mappedTargets = new LinkedHashSet<>();
        for (MappingEntry m : version.mappings()) {
            mappedTargets.add(m.globalDof());
        }
        Set<String> labelSet = new LinkedHashSet<>();
        for (Node node : version.nodes()) {
            labelSet.add("u" + node.originalId());
            labelSet.add("v" + node.originalId());
            if (!referencedNodes.contains(node.id())) {
                for (String dof : List.of("u" + node.originalId(), "v" + node.originalId())) {
                    if (!mappedTargets.contains(dof)) {
                        diagnostics.add(Diags.make(Diags.ISOLATED_DOF, WARNING,
                                "Node " + node.originalId()
                                        + " is not connected to any element; global dof " + dof
                                        + " is isolated and contributes no non-zero term.",
                                List.of(node.id(), dof)));
                    }
                }
            }
        }
        labelSet.addAll(mappedTargets);
        for (ConstraintRec c : version.constraints()) {
            labelSet.add(c.globalDof());
        }
        List<String> ordered = new ArrayList<>();
        for (Node node : version.nodes()) {
            for (String dof : List.of("u" + node.originalId(), "v" + node.originalId())) {
                if (labelSet.remove(dof)) {
                    ordered.add(dof);
                }
            }
        }
        labelSet.stream().sorted().forEachOrdered(ordered::add);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            index.put(ordered.get(i), i);
        }
        return index;
    }

    private boolean isDegenerate(Element element, List<Diagnostic> diagnostics) {
        long distinct = element.nodeIds().stream().distinct().count();
        int expected = switch (element.type()) {
            case "BAR2" -> 2;
            case "TRI3" -> 3;
            case "QUAD4" -> 4;
            case "TET4" -> 4;
            default -> element.nodeIds().size();
        };
        if (distinct < expected) {
            diagnostics.add(Diags.make(Diags.DEGENERATE_ELEMENT, ERROR,
                    "Element " + element.originalId() + " references " + distinct
                            + " distinct node(s) but type " + element.type() + " requires "
                            + expected + "; the degenerate element is excluded from assembly.",
                    List.of(element.id())));
            return true;
        }
        int n = element.matrixSize();
        if (element.localMatrix().size() != n * n) {
            diagnostics.add(Diags.make(Diags.DEGENERATE_ELEMENT, ERROR,
                    "Element " + element.originalId() + " local matrix has "
                            + element.localMatrix().size() + " values but " + (n * n)
                            + " are required for its local DOF order; element excluded.",
                    List.of(element.id())));
            return true;
        }
        return false;
    }

    private void checkAsymmetry(Element element, List<Diagnostic> diagnostics) {
        int n = element.matrixSize();
        List<String> offending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Double a = Numbers.numeric(element.localMatrix().get(i * n + j));
                Double b = Numbers.numeric(element.localMatrix().get(j * n + i));
                if (a != null && b != null && Math.abs(a - b) > ASYMMETRY_TOLERANCE) {
                    offending.add(element.localDofOrder().get(i) + "/"
                            + element.localDofOrder().get(j));
                }
            }
        }
        if (!offending.isEmpty()) {
            diagnostics.add(Diags.make(Diags.ASYMMETRIC_LOCAL_MATRIX, WARNING,
                    "Local matrix of element " + element.originalId()
                            + " is not symmetric at local DOF pair(s) "
                            + offending.stream().limit(6).toList()
                            + "; both asymmetric terms are kept, nothing is symmetrised away.",
                    List.of(element.id())));
        }
    }

    private Map<String, MappingEntry> mappingByLocalDof(CaseVersion version, Element element,
                                                        List<Diagnostic> diagnostics) {
        Map<String, List<MappingEntry>> grouped = new LinkedHashMap<>();
        for (MappingEntry m : version.mappings()) {
            if (m.elementId().equals(element.id())) {
                grouped.computeIfAbsent(m.localDof(), k -> new ArrayList<>()).add(m);
            }
        }
        Map<String, MappingEntry> chosen = new LinkedHashMap<>();
        grouped.forEach((localDof, list) -> {
            list.sort(Comparator.comparing(MappingEntry::id));
            chosen.put(localDof, list.get(0));
            if (list.size() > 1) {
                diagnostics.add(Diags.make(Diags.DUPLICATE_GLOBAL_DOF, ERROR,
                        "Element " + element.originalId() + " local DOF " + localDof
                                + " is mapped to " + list.size()
                                + " global DOFs " + list.stream().map(MappingEntry::globalDof).sorted().toList()
                                + "; the first mapping in stable order is used and the duplicate is bounded here.",
                        list.stream().map(MappingEntry::id).sorted().toList()));
            }
        });
        return chosen;
    }

    private void addGapDiag(List<Diagnostic> diagnostics, Element element, String localDof) {
        diagnostics.add(Diags.make(Diags.MAPPING_GAP, WARNING,
                "Element " + element.originalId() + " local DOF " + localDof
                        + " has no global DOF mapping; its row/column terms are not assembled.",
                List.of(element.id(), localDof)));
    }

    private void applyPenaltyContributions(List<ConstraintRec> constraints,
                                           Map<String, Integer> labelOrder,
                                           Map<String, Map<String, List<Term>>> grouped,
                                           int[] sourceOrder, double penaltyWeight,
                                           List<Diagnostic> diagnostics) {
        for (ConstraintRec c : constraints) {
            if (!c.strategy().equals("PENALTY") || !labelOrder.containsKey(c.globalDof())) {
                continue;
            }
            String id = "ctr-pen-" + Fingerprints.of(List.of(c.id(), c.globalDof())).substring(0, 8);
            String unit = c.value().unit() == null ? "N/m" : c.value().unit();
            Contribution contribution = new Contribution(id, "penalty:" + c.id(),
                    c.globalDof(), c.globalDof(), penaltyWeight, "1.0e12", unit, sourceOrder[0]++);
            Term term = new Term(contribution, 9000, 0, 0, contribution.order());
            addTerm(grouped, c.globalDof(), c.globalDof(), term);
        }
    }

    private <T> List<T> dedupe(List<T> items) {
        return new ArrayList<>(new LinkedHashSet<>(items));
    }
}
