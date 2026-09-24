package org.gsb.fem.domain;

/**
 * A single nonzero contribution: one local matrix cell scattered to one global
 * position, or one penalty diagonal. Provenance back to element / local dof /
 * source value token is mandatory.
 */
public class Contribution {
    public String id;
    public String elementId;
    public String localDofRow;
    public String localDofCol;
    public String globalRowId;
    public String globalColId;

    public String rawText;
    public String rawUnit;
    public double factor = 1.0;
    public Double normalizedValue;
    public String valueUnit;
    public String valueFrame;

    public String sourceKind = "ELEMENT";
    public String sourceConstraintId;
    public int orderIndex;
    /** Rejected candidates are kept; only adopted contributions enter the sum. */
    public boolean adopted = true;
    public String rejectionReason;

    /** Stable ordering key that does not depend on floating values. */
    public String orderKey() {
        String src = sourceKind + "|" + (elementId == null ? "" : elementId)
                + "|" + (sourceConstraintId == null ? "" : sourceConstraintId);
        return globalRowId + "|" + globalColId + "|" + src + "|"
                + (localDofRow == null ? "" : localDofRow) + "|"
                + (localDofCol == null ? "" : localDofCol);
    }
}
