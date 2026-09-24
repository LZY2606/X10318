package fealedger.model;

/**
 * One term in the per-nonzero contribution stack: a single local matrix entry
 * that landed on a global position. The ordinal encodes the deterministic order
 * in which coincident contributions are summed (element id, then local row,
 * then local column — or the reverse when the scenario requests it).
 */
public final class EntryContribution {
    public final String contributionId;
    public final int ordinal;
    public final String elementId;
    public final String localRowLabel;
    public final String localColLabel;
    public final String globalRow;
    public final String globalCol;
    public final double term;
    public final String termRaw;
    public final String unit;

    public EntryContribution(String contributionId, int ordinal, String elementId,
                             String localRowLabel, String localColLabel,
                             String globalRow, String globalCol,
                             double term, String termRaw, String unit) {
        this.contributionId = contributionId;
        this.ordinal = ordinal;
        this.elementId = elementId;
        this.localRowLabel = localRowLabel;
        this.localColLabel = localColLabel;
        this.globalRow = globalRow;
        this.globalCol = globalCol;
        this.term = term;
        this.termRaw = termRaw;
        this.unit = unit;
    }
}
