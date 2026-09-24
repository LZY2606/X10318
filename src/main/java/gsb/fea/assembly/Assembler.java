package gsb.fea.assembly;

import gsb.fea.model.ConstraintSet;
import gsb.fea.model.DofMapping;
import gsb.fea.model.Element;
import gsb.fea.model.LocalMatrix;
import gsb.fea.model.Node;
import gsb.fea.model.ProjectVersion;
import gsb.fea.model.Renumber;
import gsb.fea.util.Ids;
import gsb.fea.util.RawDecimal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic global sparse-matrix assembly with per-entry provenance.
 *
 * Guarantees:
 *  - repeated contributions are summed in one documented deterministic order per float mode;
 *  - elimination and penalty constraint strategies are never mixed silently;
 *  - duplicate dofs, degenerate elements, isolated dofs and asymmetric local matrices
 *    are diagnosed rather than dropped;
 *  - renumbering changes indices only, never physical correspondence.
 */
public final class Assembler {

    public AssemblyResult assemble(ProjectVersion version, AssemblyConfig config, String derivationId) {
        AssemblyResult result = new AssemblyResult();
        result.versionId = version.id;
        result.config = config.copy();

        DofMapping mapping = resolveMapping(version, config, result);
        ConstraintSet constraints = resolveConstraints(version, config, result);
        Renumber renumber = resolveRenumber(version, config, result);
        if (mapping == null) {
            result.complete = false;
            return result;
        }

        Map<String, Integer> dofToPhysical = buildDofIndex(version, mapping, result);
        result.dofToPhysical = dofToPhysical;
        Map<Integer, String> indexToDof = new TreeMap<>();
        dofToPhysical.forEach((dof, index) -> indexToDof.put(index, dof));
        result.indexToDof = indexToDof;
        result.physicalDimension = dofToPhysical.size();

        diagnoseIsolatedDofs(version, mapping, dofToPhysical, result);

        Map<String, Map<String, ContributionItem>> contributions =
                buildContributions(version, mapping, dofToPhysical, result);

        List<ContributionItem> ordered = new ArrayList<>();
        contributions.values().forEach(byCol -> ordered.addAll(byCol.values()));
        ordered.sort(deterministicOrder(config.summation));
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).sumOrder = i;
        }
        result.contributions = ordered;

        applyConstraintsAndEntries(version, config, constraints, renumber, dofToPhysical,
                indexToDof, derivationId, result);

        checkGlobalSymmetry(result);
        return result;
    }

    private DofMapping resolveMapping(ProjectVersion version, AssemblyConfig config, AssemblyResult result) {
        String revision = config.mappingRevision != null ? config.mappingRevision : version.selectedMappingRevision;
        DofMapping mapping = revision == null ? null : version.mappings.get(revision);
        if (mapping == null) {
            result.diagnostics.add(new Diagnostic("MAPPING_MISSING", Diagnostic.Severity.BLOCKED,
                    "mapping revision not found: " + revision));
        }
        return mapping;
    }

    private ConstraintSet resolveConstraints(ProjectVersion version, AssemblyConfig config,
                                             AssemblyResult result) {
        String revision = config.constraintRevision != null
                ? config.constraintRevision : version.selectedConstraintRevision;
        ConstraintSet constraints = revision == null ? null : version.constraintSets.get(revision);
        if (constraints != null && constraints.mixedStrategy() && config.resolvedStrategy == null) {
            result.diagnostics.add(new Diagnostic("CONSTRAINT_STRATEGY_CONFLICT",
                    Diagnostic.Severity.BLOCKED,
                    "constraint revision " + revision + " mixes ELIMINATION and PENALTY; "
                            + "one strategy must be chosen, the alternatives are retained")
                    .object(Ids.constraints(revision)));
            AssemblyResult.Candidate elimination = new AssemblyResult.Candidate("elimination-only",
                    "apply every constraint with direct elimination/Schur condensation");
            elimination.strategy = ConstraintSet.Strategy.ELIMINATION;
            elimination.summation = config.summation.name();
            elimination.renumberRevision = config.renumberRevision;
            AssemblyResult.Candidate penalty = new AssemblyResult.Candidate("penalty-only",
                    "apply every constraint with a diagonal penalty term");
            penalty.strategy = ConstraintSet.Strategy.PENALTY;
            penalty.summation = config.summation.name();
            penalty.renumberRevision = config.renumberRevision;
            result.legalCandidates.add(elimination);
            result.legalCandidates.add(penalty);
            result.complete = false;
        }
        return constraints;
    }

    private Renumber resolveRenumber(ProjectVersion version, AssemblyConfig config, AssemblyResult result) {
        String revision = config.renumberRevision != null
                ? config.renumberRevision : version.selectedRenumberRevision;
        if (revision == null) {
            return null;
        }
        Renumber renumber = version.renumbers.get(revision);
        if (renumber == null) {
            result.diagnostics.add(new Diagnostic("RENUMBER_MISSING", Diagnostic.Severity.BLOCKED,
                    "renumber revision not found: " + revision));
        }
        return renumber;
    }

    private Map<String, Integer> buildDofIndex(ProjectVersion version, DofMapping mapping,
                                               AssemblyResult result) {
        TreeSet<String> dofs = new TreeSet<>();
        for (DofMapping.MappingEntry entry : mapping.entries) {
            dofs.add(entry.globalDofId);
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        int i = 0;
        for (String dof : dofs) {
            index.put(dof, i++);
        }
        for (DofMapping.MappingEntry entry : mapping.entries) {
            if (!version.elements.containsKey(entry.elementId)) {
                result.diagnostics.add(new Diagnostic("MAPPING_UNKNOWN_ELEMENT",
                        Diagnostic.Severity.BLOCKED,
                        "mapping references unknown element " + entry.elementId)
                        .object(entry.elementId));
            }
        }
        return index;
    }

    private void diagnoseIsolatedDofs(ProjectVersion version, DofMapping mapping,
                                      Map<String, Integer> dofToPhysical, AssemblyResult result) {
        TreeSet<String> mappedDofs = new TreeSet<>();
        for (DofMapping.MappingEntry entry : mapping.entries) {
            mappedDofs.add(entry.globalDofId);
        }
        TreeSet<String> nodeDofs = new TreeSet<>();
        for (Node node : version.nodes.values()) {
            for (String axis : node.dofAxes) {
                nodeDofs.add(Ids.dof(node.uid, axis));
            }
        }
        for (String dof : nodeDofs) {
            if (!mappedDofs.contains(dof)) {
                result.diagnostics.add(new Diagnostic("ISOLATED_DOF", Diagnostic.Severity.WARNING,
                        "dof " + dof + " exists on the mesh but receives no element contribution")
                        .object(dof));
            }
        }
        for (String dof : mappedDofs) {
            String nodeId = dof.contains(".") ? dof.substring(0, dof.indexOf('.')) : dof;
            if (!version.nodes.containsKey(nodeId)) {
                result.diagnostics.add(new Diagnostic("MAPPING_UNKNOWN_DOF", Diagnostic.Severity.WARNING,
                        "mapped dof " + dof + " has no node in connectivity")
                        .object(dof));
            }
        }
    }

    private Map<String, Map<String, ContributionItem>> buildContributions(
            ProjectVersion version, DofMapping mapping, Map<String, Integer> dofToPhysical,
            AssemblyResult result) {
        Map<String, Map<String, ContributionItem>> contributions = new TreeMap<>();
        TreeMap<Integer, String> elementsByNumber = new TreeMap<>();
        for (Element element : version.elements.values()) {
            elementsByNumber.put(element.originalNumber, element.uid);
        }

        for (Map.Entry<Integer, String> elementRef : elementsByNumber.entrySet()) {
            Element element = version.elements.get(elementRef.getValue());
            List<DofMapping.MappingEntry> mapEntries = sortedMapping(mapping.forElement(element.uid));
            LocalMatrix localMatrix = latestMatrix(version, element.uid);

            if (new TreeSet<>(element.nodeUids).size() < element.nodeUids.size()) {
                result.diagnostics.add(new Diagnostic("DEGENERATE_ELEMENT",
                        Diagnostic.Severity.WARNING,
                        "element " + element.uid + " (" + element.elementType
                                + ") repeats a connectivity node: " + element.nodeUids)
                        .object(element.uid));
            }
            if (mapEntries.isEmpty()) {
                result.diagnostics.add(new Diagnostic("ELEMENT_UNMAPPED", Diagnostic.Severity.WARNING,
                        "element " + element.uid + " has no dof mapping and contributes nothing")
                        .object(element.uid));
                continue;
            }
            if (localMatrix == null) {
                result.diagnostics.add(new Diagnostic("LOCAL_MATRIX_MISSING",
                        Diagnostic.Severity.WARNING,
                        "element " + element.uid + " has no local matrix revision")
                        .object(element.uid));
                continue;
            }
            result.matrixUnit = localMatrix.unit;
            result.floatMode = localMatrix.floatMode;

            Map<String, Integer> globalCount = new LinkedHashMap<>();
            for (DofMapping.MappingEntry mapEntry : mapEntries) {
                globalCount.merge(mapEntry.globalDofId, 1, Integer::sum);
            }
            globalCount.forEach((globalDof, count) -> {
                if (count > 1) {
                    result.diagnostics.add(new Diagnostic("DUPLICATE_DOF", Diagnostic.Severity.WARNING,
                            "element " + element.uid + " maps " + count
                                    + " local dofs onto global dof " + globalDof
                                    + "; contributions are stacked, not collapsed")
                            .object(element.uid).object(globalDof));
                }
            });

            checkLocalSymmetry(element, localMatrix, result);

            for (DofMapping.MappingEntry rowMap : mapEntries) {
                for (DofMapping.MappingEntry colMap : mapEntries) {
                    RawDecimal value = localMatrix.at(rowMap.localIndex, colMap.localIndex);
                    if (value == null) {
                        continue;
                    }
                    ContributionItem item = new ContributionItem();
                    item.uid = Ids.contribution(element.uid, rowMap.localIndex, colMap.localIndex);
                    item.elementId = element.uid;
                    item.originalElementNumber = element.originalNumber;
                    item.localMatrixId = localMatrix.uid;
                    item.matrixRevision = localMatrix.revision;
                    item.localRow = rowMap.localIndex;
                    item.localCol = colMap.localIndex;
                    item.localRowDof = rowMap.localDof;
                    item.localColDof = colMap.localDof;
                    item.globalRowDofId = rowMap.globalDofId;
                    item.globalColDofId = colMap.globalDofId;
                    item.physicalRow = dofToPhysical.get(rowMap.globalDofId);
                    item.physicalCol = dofToPhysical.get(colMap.globalDofId);
                    item.rawValue = value == null ? "" : value.raw();
                    item.unit = value == null ? localMatrix.unit : value.unit();
                    item.floatMode = localMatrix.floatMode;
                    if (value == null || !value.convertible()) {
                        item.exactValue = null;
                        item.doubleValue = Double.NaN;
                        result.diagnostics.add(new Diagnostic("UNCONVERTIBLE_VALUE",
                                Diagnostic.Severity.WARNING,
                                "local matrix value at " + element.uid + "("
                                        + rowMap.localIndex + "," + colMap.localIndex
                                        + ") cannot be parsed ('" + (value == null ? "" : value.raw())
                                        + "'); kept at source, no numeric contribution")
                                .object(localMatrix.uid));
                    } else {
                        item.exactValue = value.require();
                        item.doubleValue = value.doubleValue();
                    }
                    contributions
                            .computeIfAbsent(item.globalRowDofId, key -> new TreeMap<>())
                            .put(item.globalColDofId + "#" + element.uid + "#"
                                    + rowMap.localIndex + "#" + colMap.localIndex, item);
                }
            }
        }
        return contributions;
    }

    private List<DofMapping.MappingEntry> sortedMapping(List<DofMapping.MappingEntry> entries) {
        List<DofMapping.MappingEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(entry -> entry.localIndex));
        return sorted;
    }

    private LocalMatrix latestMatrix(ProjectVersion version, String elementId) {
        LocalMatrix found = null;
        for (LocalMatrix matrix : version.matrices.values()) {
            if (matrix.elementId.equals(elementId)
                    && (found == null || matrix.revision.compareTo(found.revision) > 0)) {
                found = matrix;
            }
        }
        return found;
    }

    private void checkLocalSymmetry(Element element, LocalMatrix matrix, AssemblyResult result) {
        if (matrix.declaredSymmetric) {
            return;
        }
        double maxDifference = 0.0;
        int[] location = null;
        for (int r = 0; r < matrix.dimension; r++) {
            for (int c = r + 1; c < matrix.dimension; c++) {
                RawDecimal a = matrix.at(r, c);
                RawDecimal b = matrix.at(c, r);
                if (a == null || b == null || !a.convertible() || !b.convertible()) {
                    continue;
                }
                double difference = Math.abs(a.doubleValue() - b.doubleValue());
                if (difference > maxDifference) {
                    maxDifference = difference;
                    location = new int[]{r, c};
                }
            }
        }
        if (location != null && maxDifference > result.config.asymmetryTolerance) {
            result.diagnostics.add(new Diagnostic("LOCAL_MATRIX_ASYMMETRIC",
                    Diagnostic.Severity.WARNING,
                    "local matrix " + matrix.uid + " is asymmetric by " + maxDifference
                            + " at (" + location[0] + "," + location[1] + "); all positions kept")
                    .object(matrix.uid));
        }
    }

    private Comparator<ContributionItem> deterministicOrder(SummationSpec spec) {
        Comparator<ContributionItem> byEntry = Comparator
                .comparingInt((ContributionItem item) -> item.physicalRow)
                .thenComparingInt(item -> item.physicalCol);
        Comparator<ContributionItem> ascending = byEntry
                .thenComparingInt(item -> item.originalElementNumber)
                .thenComparingInt(item -> item.localRow)
                .thenComparingInt(item -> item.localCol);
        if (spec == SummationSpec.DOUBLE_REVERSED) {
            return byEntry
                    .thenComparing(Comparator.comparingInt((ContributionItem item) ->
                            item.originalElementNumber).reversed())
                    .thenComparing(Comparator.comparingInt((ContributionItem item) ->
                            item.localRow).reversed())
                    .thenComparing(Comparator.comparingInt((ContributionItem item) ->
                            item.localCol).reversed());
        }
        return ascending;
    }

    private void applyConstraintsAndEntries(
            ProjectVersion version, AssemblyConfig config, ConstraintSet constraints,
            Renumber renumber, Map<String, Integer> dofToPhysical,
            Map<Integer, String> indexToDof, String derivationId, AssemblyResult result) {

        if (result.blocked()) {
            result.complete = false;
            return;
        }

        Map<Integer, Map<Integer, List<ContributionItem>>> byPhysical = new TreeMap<>();
        for (ContributionItem item : result.contributions) {
            byPhysical.computeIfAbsent(item.physicalRow, key -> new TreeMap<>())
                    .computeIfAbsent(item.physicalCol, key -> new ArrayList<>())
                    .add(item);
        }

        java.util.Set<String> constrainedDofs = new TreeSet<>();
        ConstraintSet.Strategy strategy = config.resolvedStrategy;
        if (constraints != null) {
            for (ConstraintSet.Constraint constraint : constraints.constraints) {
                constrainedDofs.add(constraint.globalDofId);
                if (strategy == null) {
                    strategy = constraint.strategy;
                }
            }
        }

        // Build unconstrained entries with deterministic summation.
        Map<String, MatrixEntry> entries = new TreeMap<>();
        for (Map.Entry<Integer, Map<Integer, List<ContributionItem>>> rowBlock : byPhysical.entrySet()) {
            for (Map.Entry<Integer, List<ContributionItem>> cell : rowBlock.getValue().entrySet()) {
                int row = rowBlock.getKey();
                int col = cell.getKey();
                List<ContributionItem> items = new ArrayList<>(cell.getValue());
                items.sort(Comparator.comparingInt(item -> item.sumOrder));
                MatrixEntry entry = new MatrixEntry();
                entry.uid = Ids.entry(derivationId, row, col);
                entry.physicalRow = row;
                entry.physicalCol = col;
                entry.globalRowDofId = indexToDof.get(row);
                entry.globalColDofId = indexToDof.get(col);
                entry.summationMode = config.summation.modeLabel;
                entry.numeric = true;
                java.math.BigDecimal exact = java.math.BigDecimal.ZERO;
                double sum = 0.0;
                for (ContributionItem item : items) {
                    entry.contributionUids.add(item.uid);
                    if (item.exactValue == null) {
                        entry.numeric = false;
                        continue;
                    }
                    exact = exact.add(item.exactValue);
                    sum += item.doubleValue;
                }
                entry.exactValue = entry.numeric ? exact : null;
                entry.doubleValue = entry.numeric ? sum : Double.NaN;
                entries.put(row + ":" + col, entry);
            }
        }

        // Apply constraints with the single resolved strategy.
        if (constraints != null && strategy != null) {
            applyConstraints(constraints, strategy, config, dofToPhysical, indexToDof,
                    entries, derivationId, result);
        }

        // Renumbering: display indices only; physical correspondence is untouched.
        Map<Integer, Integer> physicalToDisplayed = new LinkedHashMap<>();
        if (renumber != null) {
            for (Map.Entry<String, Integer> dof : dofToPhysical.entrySet()) {
                Integer newIndex = renumber.dofToNewIndex.get(dof.getKey());
                physicalToDisplayed.put(dof.getValue(),
                        newIndex == null ? dof.getValue() : newIndex);
            }
            result.diagnostics.add(new Diagnostic("RENUMBER_APPLIED", Diagnostic.Severity.INFO,
                    "renumber " + renumber.revision + " permutes display indices only; "
                            + "physical dof correspondence unchanged")
                    .object(Ids.renumber(renumber.revision)));
        } else {
            for (Map.Entry<String, Integer> dof : dofToPhysical.entrySet()) {
                physicalToDisplayed.put(dof.getValue(), dof.getValue());
            }
        }
        result.physicalToDisplayed = physicalToDisplayed;
        result.displayedDimension = result.physicalDimension;

        List<MatrixEntry> orderedEntries = new ArrayList<>(entries.values());
        for (MatrixEntry entry : orderedEntries) {
            entry.displayedRow = physicalToDisplayed.getOrDefault(entry.physicalRow, entry.physicalRow);
            entry.displayedCol = physicalToDisplayed.getOrDefault(entry.physicalCol, entry.physicalCol);
        }
        orderedEntries.sort(Comparator.comparingInt((MatrixEntry entry) -> entry.displayedRow)
                .thenComparingInt(entry -> entry.displayedCol));
        result.entries = orderedEntries;
        result.complete = !result.blocked();
    }

    private void applyConstraints(ConstraintSet constraints, ConstraintSet.Strategy strategy,
                                  AssemblyConfig config, Map<String, Integer> dofToPhysical,
                                  Map<Integer, String> indexToDof,
                                  Map<String, MatrixEntry> entries, String derivationId,
                                  AssemblyResult result) {
        Map<String, ConstraintSet.Constraint> byDof = new LinkedHashMap<>();
        for (ConstraintSet.Constraint constraint : constraints.constraints) {
            ConstraintSet.Constraint previous = byDof.putIfAbsent(constraint.globalDofId, constraint);
            if (previous != null) {
                result.diagnostics.add(new Diagnostic("CONSTRAINT_CONFLICT",
                        Diagnostic.Severity.BLOCKED,
                        "dof " + constraint.globalDofId + " has conflicting constraints "
                                + previous.uid + " and " + constraint.uid
                                + "; both candidates retained, no silent merge")
                        .object(previous.uid).object(constraint.uid));
                AssemblyResult.Candidate keepFirst = new AssemblyResult.Candidate(
                        "keep-" + previous.uid, "drop " + constraint.uid + ", keep " + previous.uid);
                keepFirst.strategy = strategy;
                AssemblyResult.Candidate keepSecond = new AssemblyResult.Candidate(
                        "keep-" + constraint.uid, "drop " + previous.uid + ", keep " + constraint.uid);
                keepSecond.strategy = strategy;
                result.legalCandidates.add(keepFirst);
                result.legalCandidates.add(keepSecond);
                result.complete = false;
                return;
            }
            if (!dofToPhysical.containsKey(constraint.globalDofId)) {
                result.diagnostics.add(new Diagnostic("CONSTRAINT_UNKNOWN_DOF",
                        Diagnostic.Severity.WARNING,
                        "constraint " + constraint.uid + " targets unknown dof "
                                + constraint.globalDofId + "; ignored, kept in source")
                        .object(constraint.uid));
            }
        }

        for (ConstraintSet.Constraint constraint : constraints.constraints) {
            Integer index = dofToPhysical.get(constraint.globalDofId);
            if (index == null) {
                continue;
            }
            ConstraintEffect effect = new ConstraintEffect();
            effect.constraintUid = constraint.uid;
            effect.globalDofId = constraint.globalDofId;
            effect.physicalIndex = index;
            effect.kind = constraint.kind.name();
            effect.strategy = strategy.name();
            effect.prescribedRaw = constraint.value == null ? "0" : constraint.value.raw();
            java.math.BigDecimal prescribed = constraint.value == null
                    ? java.math.BigDecimal.ZERO : constraint.value.require();

            if (strategy == ConstraintSet.Strategy.ELIMINATION) {
                effect.removedDofIds.add(constraint.globalDofId);
                for (MatrixEntry entry : new ArrayList<>(entries.values())) {
                    if (entry.physicalRow == index || entry.physicalCol == index) {
                        if (entry.physicalRow == index && entry.physicalCol == index) {
                            continue;
                        }
                        effect.modifiedEntryUids.add(entry.uid);
                        entries.remove(entry.physicalRow + ":" + entry.physicalCol);
                    }
                }
                MatrixEntry identity = new MatrixEntry();
                identity.uid = Ids.entry(derivationId, index, index);
                identity.physicalRow = index;
                identity.physicalCol = index;
                identity.globalRowDofId = constraint.globalDofId;
                identity.globalColDofId = constraint.globalDofId;
                identity.exactValue = java.math.BigDecimal.ONE;
                identity.doubleValue = 1.0;
                identity.numeric = true;
                identity.summationMode = "CONSTRAINT-ELIMINATION";
                identity.contributionUids.add("CONSTRAINT:" + constraint.uid);
                entries.put(index + ":" + index, identity);
            } else {
                String diagonalKey = index + ":" + index;
                MatrixEntry diagonal = entries.get(diagonalKey);
                if (diagonal == null) {
                    diagonal = new MatrixEntry();
                    diagonal.uid = Ids.entry(derivationId, index, index);
                    diagonal.physicalRow = index;
                    diagonal.physicalCol = index;
                    diagonal.globalRowDofId = constraint.globalDofId;
                    diagonal.globalColDofId = constraint.globalDofId;
                    diagonal.exactValue = java.math.BigDecimal.ZERO;
                    diagonal.doubleValue = 0.0;
                    diagonal.numeric = true;
                    diagonal.summationMode = config.summation.modeLabel;
                    entries.put(diagonalKey, diagonal);
                }
                java.math.BigDecimal penalty = java.math.BigDecimal.valueOf(config.penaltyFactor);
                diagonal.exactValue = diagonal.exactValue.add(penalty);
                diagonal.doubleValue = diagonal.doubleValue + config.penaltyFactor;
                diagonal.contributionUids.add("CONSTRAINT:" + constraint.uid);
                effect.modifiedEntryUids.add(diagonal.uid);
            }
            result.constraintEffects.add(effect);
        }
    }

    private void checkGlobalSymmetry(AssemblyResult result) {
        Map<String, MatrixEntry> byPosition = new TreeMap<>();
        for (MatrixEntry entry : result.entries) {
            byPosition.put(entry.physicalRow + ":" + entry.physicalCol, entry);
        }
        for (MatrixEntry entry : result.entries) {
            if (entry.physicalRow >= entry.physicalCol || !entry.numeric) {
                continue;
            }
            MatrixEntry mirror = byPosition.get(entry.physicalCol + ":" + entry.physicalRow);
            if (mirror == null || !mirror.numeric) {
                result.diagnostics.add(new Diagnostic("GLOBAL_MATRIX_ASYMMETRIC",
                        Diagnostic.Severity.INFO,
                        "entry (" + entry.physicalRow + "," + entry.physicalCol
                                + ") has no numeric mirror; matrix is structurally asymmetric")
                        .object(entry.uid));
                continue;
            }
            double difference = Math.abs(entry.doubleValue - mirror.doubleValue);
            if (difference > result.config.asymmetryTolerance) {
                result.diagnostics.add(new Diagnostic("GLOBAL_MATRIX_ASYMMETRIC",
                        Diagnostic.Severity.INFO,
                        "entries (" + entry.physicalRow + "," + entry.physicalCol + ") and ("
                                + entry.physicalCol + "," + entry.physicalRow + ") differ by "
                                + difference)
                        .object(entry.uid).object(mirror.uid));
            }
        }
    }
}
