package gsb.fea.assembly;

import gsb.fea.model.ConstraintSet;

/** Fully-resolved assembly inputs: a material version plus the chosen rule revisions. */
public final class AssemblyConfig {
    public String mappingRevision;
    public String constraintRevision;
    public String renumberRevision;
    public SummationSpec summation = SummationSpec.DOUBLE_CANONICAL;
    /** Strategy chosen by a human decision when a constraint set mixes strategies. */
    public ConstraintSet.Strategy resolvedStrategy;
    public String decisionEventId;
    public double asymmetryTolerance = 1.0e-10;
    public double penaltyFactor = 1.0e12;

    public AssemblyConfig() {
    }

    public AssemblyConfig copy() {
        AssemblyConfig copy = new AssemblyConfig();
        copy.mappingRevision = mappingRevision;
        copy.constraintRevision = constraintRevision;
        copy.renumberRevision = renumberRevision;
        copy.summation = summation;
        copy.resolvedStrategy = resolvedStrategy;
        copy.decisionEventId = decisionEventId;
        copy.asymmetryTolerance = asymmetryTolerance;
        copy.penaltyFactor = penaltyFactor;
        return copy;
    }

    public String signature() {
        return mappingRevision + "|" + constraintRevision + "|"
                + (renumberRevision == null ? "-" : renumberRevision) + "|"
                + summation.name() + "|"
                + (resolvedStrategy == null ? "-" : resolvedStrategy.name()) + "|"
                + asymmetryTolerance + "|" + penaltyFactor;
    }
}
