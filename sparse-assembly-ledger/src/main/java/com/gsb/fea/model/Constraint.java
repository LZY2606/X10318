package com.gsb.fea.model;

/**
 * Boundary constraint on one physical dof.
 * kind = FIX (prescribed value, handled by elimination) or PENALTY (spring).
 * Mixing the two strategies in one assembly is rejected; conflicting
 * constraints on the same dof produce all legal candidates, never a guess.
 */
public record Constraint(String constraintId, String dofId, String kind,
                         RawValue value, String version, String rationale) {
    public static final String FIX = "FIX";
    public static final String PENALTY = "PENALTY";
}
