package fea.asm;

import fea.model.CaseData;
import fea.model.UnitConv;
import java.util.*;

/** Applies boundary constraints. Elimination and penalty are never mixed:
 *  a "mixed" strategy yields two candidate results, both retained. */
public final class Constrained {

    public static final class EliminatedEntry {
        public String id;          // elim:<entryId>
        public String entryId;     // source entry identity
        public String rowDof, colDof;
        public double value;
        public String reason;      // which constraint caused removal
    }
    public static final class RhsTerm {
        public String id;          // rhs:<dof>:<entryId>
        public String dof;
        public double value;
        public String fromEntry;
    }
    public static final class Result {
        public String strategy;
        public String constraintVersion;
        public final List<Assembly.Entry> freeEntries = new ArrayList<>();
        public final List<EliminatedEntry> eliminated = new ArrayList<>();
        public final List<RhsTerm> rhs = new ArrayList<>();
        public final List<String> penaltyTerms = new ArrayList<>(); // contribution ids added
        public boolean conflict;    // constraint conflict detected -> kept as diagnostic state
    }

    /** Returns one result for a clean strategy, or two candidates for "mixed". */
    public static List<Result> apply(CaseData c, Assembly.Result asm) {
        if (c.constraints.strategy.equals("mixed")) {
            List<Result> both = new ArrayList<>();
            both.add(applyOne(c, asm, "elimination"));
            both.add(applyOne(c, asm, "penalty"));
            return both; // multi-solution state preserved, never swallowed
        }
        return List.of(applyOne(c, asm, c.constraints.strategy));
    }

    static Result applyOne(CaseData c, Assembly.Result asm, String strategy) {
        Result r = new Result();
        r.strategy = strategy;
        r.constraintVersion = c.constraints.version;
        Map<String, Double> prescribed = new LinkedHashMap<>();
        Set<String> conflictDofs = new HashSet<>();
        for (CaseData.ConstraintSet.Entry ce : c.constraints.entries) {
            double v;
            try { v = UnitConv.toMm(ce.value, ce.unit); }
            catch (RuntimeException ex) { continue; }
            if (preservedConflict(prescribed, ce.dof, v)) conflictDofs.add(ce.dof);
            prescribed.putIfAbsent(ce.dof, v);
        }
        r.conflict = !conflictDofs.isEmpty();
        if (strategy.equals("elimination")) {
            for (Assembly.Entry en : asm.entries) {
                boolean rowC = prescribed.containsKey(en.rowDof);
                boolean colC = prescribed.containsKey(en.colDof);
                if (!rowC && !colC) { r.freeEntries.add(en); continue; }
                EliminatedEntry el = new EliminatedEntry();
                el.id = "elim:" + en.id;
                el.entryId = en.id;
                el.rowDof = en.rowDof; el.colDof = en.colDof;
                el.value = en.value;
                el.reason = "constraint-set:" + c.constraints.version;
                r.eliminated.add(el);
                if (colC && !rowC) { // RHS move: F[row] -= K[row][col] * u[col]
                    RhsTerm t = new RhsTerm();
                    t.id = "rhs:" + en.rowDof + ":" + en.id;
                    t.dof = en.rowDof;
                    t.value = -en.value * prescribed.get(en.colDof);
                    t.fromEntry = en.id;
                    r.rhs.add(t);
                }
            }
        } else { // penalty: diagonal of constrained dof gets + penalty, stack records it
            double penalty = Double.parseDouble(c.constraints.penaltyFactor);
            double scale = 1.0;
            for (Assembly.Entry en : asm.entries)
                if (en.rowDof.equals(en.colDof)) scale = Math.max(scale, Math.abs(en.value));
            for (Assembly.Entry en : asm.entries) {
                if (prescribed.containsKey(en.rowDof) && en.rowDof.equals(en.colDof)) {
                    Assembly.Contribution pc = new Assembly.Contribution();
                    pc.id = "penalty:" + en.id;
                    pc.element = "<constraint:" + c.constraints.version + ">";
                    pc.rawValue = String.valueOf(penalty * scale);
                    pc.normalized = penalty * scale;
                    pc.unit = "N/mm";
                    en.stack.add(pc);
                    en.value += pc.normalized;
                    r.penaltyTerms.add(pc.id);
                }
                r.freeEntries.add(en);
            }
        }
        return r;
    }

    private static boolean preservedConflict(Map<String, Double> p, String dof, double v) {
        Double old = p.get(dof);
        return old != null && old.doubleValue() != v;
    }
}
