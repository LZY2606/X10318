package fealedger.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Trace of how the constraint version acted on the free system: which dofs
 * were eliminated/preserved, which diagonal penalties were added, and any
 * ties/conflicts with their candidate resolutions.
 */
public final class ConstraintEffect {
    public static final class RowEffect {
        public final String dofId;
        public final String action; // "kept" | "eliminated" | "penalized" | "tied" | "conflict"
        public final double diagonalAdd;
        public final String tiedTo;
        public RowEffect(String dofId, String action, double diagonalAdd, String tiedTo) {
            this.dofId = dofId; this.action = action; this.diagonalAdd = diagonalAdd; this.tiedTo = tiedTo;
        }
    }

    public String strategy;
    public final List<RowEffect> rows = new ArrayList<>();
}
