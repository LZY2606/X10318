package fealedger.model;

import java.util.List;

public final class ConstraintSet {
    public final String versionId;
    public final String strategy; // "elimination" | "penalty"
    public final Quantity penaltyStiffness;
    public final List<BoundaryConstraint> constraints;

    public ConstraintSet(String versionId, String strategy, Quantity penaltyStiffness,
                         List<BoundaryConstraint> constraints) {
        this.versionId = versionId;
        this.strategy = strategy;
        this.penaltyStiffness = penaltyStiffness;
        this.constraints = constraints;
    }
}
