package gsb.fea.model;

/**
 * Trace of how one boundary constraint touched the assembled system. For
 * elimination a fixed dof's row/col are removed; for penalty a large diagonal
 * coefficient and prescribed rhs are added. The two strategies are never mixed
 * inside one candidate.
 */
public record ConstraintEffect(String constraintId, String globalDofKey, int index,
                               String strategy, String rawValue, double prescribedValue,
                               double penaltyCoefficient, String unit) {
    public static final String ELIMINATION = "ELIMINATION";
    public static final String PENALTY = "PENALTY";
}
