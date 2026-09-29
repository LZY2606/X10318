package org.feaudit;

import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public record RawValue(String value, String unit, String basis, String representation, String status, String diagnosis) {}
    public record Node(String id, String originalId, Map<String, RawValue> coordinates) {}
    public record Element(String id, String originalNumber, List<String> nodeIds, Map<String, RawValue> geometry, boolean degenerate) {}
    public record LocalEntry(String localRow, String localColumn, RawValue value) {}
    public record LocalMatrix(String elementId, List<String> localDofOrder, List<LocalEntry> entries, String symmetryMode) {}
    public record DofMapping(String id, String nodeId, String component, String physicalDofId, String globalIndex, RawValue coordinateBasis, String version) {}
    public record Constraint(String id, String physicalDofId, RawValue prescribedValue, String strategy, String version, String basis) {}
    public record RenumberRule(String id, String version, Map<String, String> physicalToGlobal) {}

    public record CaseInput(
            String caseId,
            String sourceVersion,
            String floatingPointMode,
            String unitSystem,
            List<Node> nodes,
            List<Element> elements,
            List<LocalMatrix> localMatrices,
            List<DofMapping> dofMappings,
            List<Constraint> constraints,
            RenumberRule renumberRule
    ) {}

    public record Contribution(
            String id,
            String elementId,
            String elementOriginalNumber,
            String localRowDof,
            String localColumnDof,
            String physicalRowDof,
            String physicalColumnDof,
            int globalRow,
            int globalColumn,
            RawValue rawValue,
            double value,
            int orderIndex,
            String basis
    ) {}

    public record SummationStep(int orderIndex, String contributionId, double addedValue, double runningValue, String floatingPointMode) {}
    public record GlobalEntry(String id, String physicalRowDof, String physicalColumnDof, int row, int column, double value, List<String> contributionIds, List<SummationStep> steps) {}
    public record ConstraintEffect(String constraintId, String physicalDofId, String strategy, double prescribedValue, List<String> removedRows, List<String> modifiedEntries, RawValue rawValue) {}
    public record AssemblyResult(
            String derivationId,
            String parentId,
            String inputHash,
            String status,
            String strategy,
            String floatingPointMode,
            List<GlobalEntry> entries,
            List<Contribution> contributions,
            List<ConstraintEffect> constraintEffects,
            List<Map<String, Object>> diagnostics,
            Map<String, Object> dependencyHashes,
            List<String> warnings
    ) {}

    public record DependencyChange(String nodeId, String dependencyId, String reason, String scope) {}
}
