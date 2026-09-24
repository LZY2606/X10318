package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/** Record of what one boundary constraint did (or why it was rejected). */
public class ConstraintEffect {
    public String id;
    public String constraintId;
    public String dofId;
    /**
     * ELIMINATE_ROW_COL, RHS_MOVE, PENALTY_DIAGONAL,
     * REJECTED_CONFLICT, REJECTED_MIXED_STRATEGY.
     */
    public String action;
    public boolean adopted;
    public String otherCandidateId;
    public String detail;
    public List<Touch> touches = new ArrayList<>();

    public static class Touch {
        public String entryId;
        public String kind;
        public double delta;

        public Touch() {
        }

        public Touch(String entryId, String kind, double delta) {
            this.entryId = entryId;
            this.kind = kind;
            this.delta = delta;
        }
    }
}
