package fe.assembly;

import fe.domain.Constraint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fully traced result of one assembly derivation. Holds the unconstrained
 * global matrix, the constraint view actually applied, and all diagnostics.
 *
 * <p>When the constraint set is ambiguous (mixed strategies or conflicting
 * versions on the same dof), every legal candidate is retained in
 * {@link #candidates} instead of collapsing to one answer.
 */
public final class AssemblyResult {
    public final SparseMatrix freeMatrix;
    public final SparseMatrix constrainedMatrix;
    public final List<Constraint> appliedConstraints;
    public final Constraint.Strategy appliedStrategy;
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    public final List<AssemblyResult> candidates = new ArrayList<>();
    public final String candidateLabel;
    public final Map<Integer, Integer> constrainedToFree;
    public final List<Integer> constrainedDofs;

    public AssemblyResult(SparseMatrix freeMatrix, SparseMatrix constrainedMatrix,
                          List<Constraint> appliedConstraints, Constraint.Strategy appliedStrategy,
                          String candidateLabel, List<Integer> constrainedDofs,
                          Map<Integer, Integer> constrainedToFree) {
        this.freeMatrix = freeMatrix;
        this.constrainedMatrix = constrainedMatrix;
        this.appliedConstraints = appliedConstraints;
        this.appliedStrategy = appliedStrategy;
        this.candidateLabel = candidateLabel;
        this.constrainedDofs = constrainedDofs;
        this.constrainedToFree = constrainedToFree;
    }

    public boolean hasError() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    public boolean ambiguous() { return !candidates.isEmpty(); }

    public List<Diagnostic> diagnosticsByCode(String code) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) if (d.code().equals(code)) out.add(d);
        return out;
    }
}
