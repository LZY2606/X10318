package gsb.fea.engine;

import gsb.fea.model.AssemblyResult;
import gsb.fea.model.Numbering;
import gsb.fea.model.SparseEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renumbering changes only the compact index of a physical dof. It never
 * changes the physical correspondence: physical keys are carried through, and
 * the reordered matrix is permutation-equivalent to the original.
 */
public final class Renumberer {

    private Renumberer() {
    }

    public record RenumberingResult(Numbering numbering, List<SparseEntry> reorderedEntries,
                                    List<int[]> permutationPairs, boolean physicsPreserved) {
    }

    public static RenumberingResult apply(AssemblyResult original, List<String> physicalDofKeysInNewOrder) {
        Numbering oldNumbering = original.numbering();
        if (physicalDofKeysInNewOrder.size() != oldNumbering.size()
                || !oldNumbering.dofKeyToIndex().keySet().containsAll(physicalDofKeysInNewOrder)) {
            throw new IllegalArgumentException("renumbering must use exactly the same set of physical dof keys");
        }
        Map<String, Integer> newIndex = new LinkedHashMap<>();
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < physicalDofKeysInNewOrder.size(); i++) {
            String key = physicalDofKeysInNewOrder.get(i);
            newIndex.put(key, i);
            pairs.add(new int[]{oldNumbering.dofKeyToIndex().get(key), i});
        }
        Numbering reordered = new Numbering("numbering:renumbered:" + Hashes.sha256(newIndex.toString()),
                "renumbered; indices changed, physical dof keys unchanged", newIndex);

        List<SparseEntry> moved = new ArrayList<>();
        for (SparseEntry e : original.fullEntries()) {
            int nr = newIndex.get(e.rowKey());
            int nc = newIndex.get(e.colKey());
            moved.add(new SparseEntry(nr, nc, e.rowKey(), e.colKey(), e.value(), e.rawExpression(),
                    e.contributionIds(), e.altOrderValue(), e.orderDelta()));
        }
        moved.sort(java.util.Comparator.comparingInt(SparseEntry::rowIndex)
                .thenComparingInt(SparseEntry::colIndex));
        return new RenumberingResult(reordered, moved, pairs, true);
    }

    /** Permutation-invariance check: A'[pi(i)][pi(j)] == A[i][j] for every nonzero. */
    public static boolean verifyPhysicsPreserved(AssemblyResult original, RenumberingResult result) {
        Map<String, Double> before = new LinkedHashMap<>();
        for (SparseEntry e : original.fullEntries()) {
            before.put(e.rowKey() + "::" + e.colKey(), e.value());
        }
        for (SparseEntry e : result.reorderedEntries()) {
            Double expected = before.get(e.rowKey() + "::" + e.colKey());
            if (expected == null || Math.abs(expected - e.value()) > 1.0e-15) {
                return false;
            }
        }
        return before.size() == result.reorderedEntries().size();
    }
}
