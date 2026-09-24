package femledger.model;

import java.util.List;

/**
 * The derived layer for one {@link CaseVersion}. Holds the global dof label order (labels and
 * indices after renumbering), the non-zero entries with contribution stacks, the constraint
 * application outcome, diagnostics and any unresolved/resolved conflicts.
 *
 * candidateGroupId ties together the alternative AssemblyResults produced from one ambiguity.
 */
public final class AssemblyResult {
    private final String id;
    private final String versionId;
    private final String candidateGroupId;
    private final String candidateLabel;
    private final String floatMode;
    private final List<String> globalDofLabels;
    private final List<GlobalEntry> entries;
    private final List<String> eliminatedDofs;
    private final List<GlobalEntry> reducedEntries;
    private final List<Diagnostic> diagnostics;
    private final List<ConflictRec> conflicts;
    private final List<String> appliedConstraintIds;
    private final String summary;

    public AssemblyResult(String id, String versionId, String candidateGroupId,
                          String candidateLabel, String floatMode, List<String> globalDofLabels,
                          List<GlobalEntry> entries, List<String> eliminatedDofs,
                          List<GlobalEntry> reducedEntries, List<Diagnostic> diagnostics,
                          List<ConflictRec> conflicts, List<String> appliedConstraintIds,
                          String summary) {
        this.id = id;
        this.versionId = versionId;
        this.candidateGroupId = candidateGroupId;
        this.candidateLabel = candidateLabel;
        this.floatMode = floatMode;
        this.globalDofLabels = List.copyOf(globalDofLabels);
        this.entries = List.copyOf(entries);
        this.eliminatedDofs = List.copyOf(eliminatedDofs);
        this.reducedEntries = List.copyOf(reducedEntries);
        this.diagnostics = List.copyOf(diagnostics);
        this.conflicts = List.copyOf(conflicts);
        this.appliedConstraintIds = List.copyOf(appliedConstraintIds);
        this.summary = summary;
    }

    public String id() { return id; }
    public String versionId() { return versionId; }
    public String candidateGroupId() { return candidateGroupId; }
    public String candidateLabel() { return candidateLabel; }
    public String floatMode() { return floatMode; }
    public List<String> globalDofLabels() { return globalDofLabels; }
    public List<GlobalEntry> entries() { return entries; }
    public List<String> eliminatedDofs() { return eliminatedDofs; }
    public List<GlobalEntry> reducedEntries() { return reducedEntries; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public List<ConflictRec> conflicts() { return conflicts; }
    public List<String> appliedConstraintIds() { return appliedConstraintIds; }
    public String summary() { return summary; }
}
