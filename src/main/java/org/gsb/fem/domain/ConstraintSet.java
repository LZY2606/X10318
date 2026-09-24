package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/** A boundary-condition revision with exactly one constraint strategy. */
public class ConstraintSet {
    public String id;
    /** ELIMINATION or PENALTY — the two strategies must never be mixed in one run. */
    public String strategy;
    public RawValue penalty;
    public List<Constraint> constraints = new ArrayList<>();

    public ConstraintSet() {
    }
}
