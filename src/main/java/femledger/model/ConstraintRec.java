package femledger.model;

import java.util.Objects;

/**
 * A boundary constraint at a global DOF.
 * strategy is ELIMINATION or PENALTY; both strategies must never be mixed inside one assembly.
 */
public final class ConstraintRec {
    private final String id;
    private final String constraintVersion;
    private final String globalDof;
    private final String strategy;
    private final RawValue value;

    public ConstraintRec(String id, String constraintVersion, String globalDof,
                         String strategy, RawValue value) {
        this.id = id;
        this.constraintVersion = constraintVersion;
        this.globalDof = globalDof;
        this.strategy = strategy;
        this.value = value;
    }

    public String id() { return id; }
    public String constraintVersion() { return constraintVersion; }
    public String globalDof() { return globalDof; }
    public String strategy() { return strategy; }
    public RawValue value() { return value; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ConstraintRec c)) return false;
        return Objects.equals(id, c.id) && Objects.equals(constraintVersion, c.constraintVersion)
                && Objects.equals(globalDof, c.globalDof) && Objects.equals(strategy, c.strategy)
                && Objects.equals(value, c.value);
    }
    @Override public int hashCode() {
        return Objects.hash(id, constraintVersion, globalDof, strategy, value);
    }
}
