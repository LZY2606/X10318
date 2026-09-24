package gsb.fea.model;

import gsb.fea.util.RawDecimal;

import java.util.ArrayList;
import java.util.List;

/** A revision of boundary constraints. Each constraint carries its own solve strategy. */
public final class ConstraintSet {
    public String revision;
    public List<Constraint> constraints = new ArrayList<>();

    public ConstraintSet() {
    }

    public static final class Constraint {
        public String uid;
        public String globalDofId;
        public Kind kind;
        public RawDecimal value;
        public Strategy strategy;
        public String note = "";

        public Constraint() {
        }
    }

    public enum Kind {
        FIXED,
        PRESCRIBED
    }

    public enum Strategy {
        ELIMINATION,
        PENALTY
    }

    public boolean mixedStrategy() {
        boolean elimination = false;
        boolean penalty = false;
        for (Constraint constraint : constraints) {
            if (constraint.strategy == Strategy.ELIMINATION) {
                elimination = true;
            } else if (constraint.strategy == Strategy.PENALTY) {
                penalty = true;
            }
        }
        return elimination && penalty;
    }

    public Constraint constraintFor(String dofId) {
        for (Constraint constraint : constraints) {
            if (constraint.globalDofId.equals(dofId)) {
                return constraint;
            }
        }
        return null;
    }
}
