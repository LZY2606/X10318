package fe.domain;

import java.util.Objects;

/**
 * An essential boundary condition applied to a physical dof.
 *
 * <p>{@code strategy} selects how the constraint enters assembly:
 * {@code ELIMINATION} removes the dof and applies the prescribed value;
 * {@code PENALTY} instead adds a large diagonal stiffness. The two strategies
 * must never be mixed inside one assembled result — doing so is diagnosed and
 * leaves multiple candidate results rather than one blended answer.
 *
 * <p>Constraints carry a {@code constraintVersion} so different boundary
 * condition revisions are independently traceable.
 */
public final class Constraint {
    public enum Strategy { ELIMINATION, PENALTY }

    private final String constraintId;
    private final String constraintVersion;
    private final String physicalDoF;
    private final Quantity prescribed;
    private final Strategy strategy;
    private final Double penalty;

    public Constraint(String constraintId, String constraintVersion, String physicalDoF,
                      Quantity prescribed, Strategy strategy, Double penalty) {
        this.constraintId = constraintId;
        this.constraintVersion = constraintVersion;
        this.physicalDoF = physicalDoF;
        this.prescribed = prescribed;
        this.strategy = strategy;
        this.penalty = penalty;
    }

    public String constraintId() { return constraintId; }
    public String constraintVersion() { return constraintVersion; }
    public String physicalDoF() { return physicalDoF; }
    public Quantity prescribed() { return prescribed; }
    public Strategy strategy() { return strategy; }
    public Double penalty() { return penalty; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Constraint c)) return false;
        return Objects.equals(constraintId, c.constraintId)
                && Objects.equals(constraintVersion, c.constraintVersion)
                && Objects.equals(physicalDoF, c.physicalDoF)
                && Objects.equals(prescribed, c.prescribed)
                && strategy == c.strategy && Objects.equals(penalty, c.penalty);
    }
    @Override public int hashCode() {
        return Objects.hash(constraintId, constraintVersion, physicalDoF, prescribed, strategy, penalty);
    }
}
