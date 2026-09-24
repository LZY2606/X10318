package fealedger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The derived assembly artifact for one input version: free (unconstrained)
 * sparse matrix, constrained matrix, constraint effect trace, diagnostics and
 * candidate alternatives. Candidates preserve legal outcomes that could not be
 * uniquely decided — accepting one never deletes the others.
 */
public final class AssemblyResult {
    public String resultId;
    public String inputHash;
    public String floatMode;
    public String summationOrder;

    public final List<SparseEntry> freeEntries = new ArrayList<>();
    public final List<SparseEntry> constrainedEntries = new ArrayList<>();
    public final Map<String, SparseEntry> freeIndex = new LinkedHashMap<>();
    public final Map<String, SparseEntry> constrainedIndex = new LinkedHashMap<>();
    public final ConstraintEffect constraintEffect = new ConstraintEffect();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    /** dof id -> number of times it appeared inside a single element mapping. */
    public final Map<String, Integer> duplicateDofHits = new LinkedHashMap<>();
    /** alternative legal results (e.g. conflict sides, reverse summation). */
    public final Map<String, AssemblyResult> candidates = new LinkedHashMap<>();
    public String acceptedCandidateId;
    /** final ordered dof list actually used for indices, after renumbering. */
    public final List<String> dofOrder = new ArrayList<>();
}
