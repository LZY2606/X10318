package femledger.model;

import java.util.List;

/** Why a derived result is still valid or must be recomputed, derived from the dependency graph. */
public final class RecomputeExplanation {
    private final boolean stillValid;
    private final String resultId;
    private final List<String> unchangedFingerprints;
    private final List<String> changedSources;
    private final List<String> recomputedResults;
    private final String reason;

    public RecomputeExplanation(boolean stillValid, String resultId,
                                List<String> unchangedFingerprints, List<String> changedSources,
                                List<String> recomputedResults, String reason) {
        this.stillValid = stillValid;
        this.resultId = resultId;
        this.unchangedFingerprints = List.copyOf(unchangedFingerprints);
        this.changedSources = List.copyOf(changedSources);
        this.recomputedResults = List.copyOf(recomputedResults);
        this.reason = reason;
    }

    public boolean stillValid() { return stillValid; }
    public String resultId() { return resultId; }
    public List<String> unchangedFingerprints() { return unchangedFingerprints; }
    public List<String> changedSources() { return changedSources; }
    public List<String> recomputedResults() { return recomputedResults; }
    public String reason() { return reason; }
}
