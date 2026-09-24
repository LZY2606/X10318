package org.gsb.fem.domain;

/** Boundary constraint on one physical global degree of freedom. */
public class Constraint {
    public String id;
    public String dofId;
    /** FIXED or PRESCRIBED. */
    public String kind;
    public RawValue value;
    /** Optional per-constraint strategy override (ELIMINATION / PENALTY). */
    public String strategyOverride;

    public Constraint() {
    }

    public Constraint(String id, String dofId, String kind, RawValue value) {
        this.id = id;
        this.dofId = dofId;
        this.kind = kind;
        this.value = value;
    }
}
