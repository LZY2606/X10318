package gsb.fea.model;

import java.util.List;
import java.util.Map;

/**
 * The complete derived artifact for one version: numbering, the per-contribution
 * ledger, the full sparse matrix (all dofs, before constraint reduction), the
 * reduced system after the single chosen constraint strategy, constraint
 * effects and diagnostics. Everything references stable physical ids so a
 * display value can always be traced back to a source.
 */
public record AssemblyResult(String id,
                             String sourceHash,
                             String constraintVersion,
                             String strategy,
                             Numbering numbering,
                             List<Contribution> contributions,
                             List<SparseEntry> fullEntries,
                             List<SparseEntry> reducedEntries,
                             List<Integer> freeIndices,
                             List<ConstraintEffect> constraintEffects,
                             List<Diagnostic> diagnostics,
                             List<String> isolatedDofKeys,
                             Map<String, String> validityReasons,
                             String floatModeLabel) {

    public boolean hasBlockingDiagnostics() {
        return diagnostics.stream().anyMatch(d -> "BLOCK".equals(d.severity()) || "ERROR".equals(d.severity()));
    }
}
