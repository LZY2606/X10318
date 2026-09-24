package fealedger.model;

/** Enumerations shared by ingestion, derivation and storage. */
public final class Enums {
    private Enums() {}

    /**
     * Deterministic floating-point accumulation modes. No mode relies on
     * iteration order of a hash structure; every sum is performed over a
     * fixed contribution order.
     */
    public enum FloatMode {
        /** IEEE-754 binary64 left-to-right fold over the fixed order. */
        STRICT_F64_LEFT_TO_RIGHT,
        /** Exact rational accumulation, rounded once to binary64 at the end. */
        RATIONAL_ONCE
    }

    /** Boundary constraint kinds. */
    public enum ConstraintKind {
        /** u_dof = value (prescribed displacement / essential BC). */
        PRESCRIBED,
        /** dof_a == dof_b (repeated DOF / tying). */
        TIED
    }

    /** Constraint strategy. The two strategies are never mixed in one assembly. */
    public enum ConstraintStrategy {
        /** Row/column elimination: constrained DOFs removed from the active system. */
        ELIMINATION,
        /** Penalty augmentation: K + alpha * C^T C. */
        PENALTY
    }

    /** Decision status of an event or candidate. */
    public enum DecisionStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        CONFLICT
    }
}
