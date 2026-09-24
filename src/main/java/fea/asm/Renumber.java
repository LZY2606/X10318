package fea.asm;

import java.util.*;

/** Renumbering changes display indices only; physical dof identity is untouched. */
public final class Renumber {
    /** Reverse-Cuthill-McKee-ish deterministic permutation: BFS over the adjacency
     *  of the sparsity graph starting from the lowest-id dof. */
    public static Map<String, Integer> permutation(Assembly.Result asm) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String d : asm.dofOrder) adj.put(d, new ArrayList<>());
        for (Assembly.Entry e : asm.entries) {
            adj.get(e.rowDof).add(e.colDof);
            adj.get(e.colDof).add(e.rowDof);
        }
        adj.values().forEach(Collections::sort);
        Map<String, Integer> perm = new LinkedHashMap<>();
        Deque<String> q = new ArrayDeque<>();
        for (String start : asm.dofOrder) {
            if (perm.containsKey(start)) continue;
            q.add(start);
            while (!q.isEmpty()) {
                String d = q.poll();
                if (perm.containsKey(d)) continue;
                perm.put(d, perm.size());
                for (String nb : adj.get(d)) if (!perm.containsKey(nb)) q.add(nb);
            }
        }
        return perm;
    }

    /** Verify that renumbering preserves physical correspondence. */
    public static boolean preservesPhysics(Assembly.Result asm, Map<String, Integer> perm) {
        for (Assembly.Entry e : asm.entries) {
            Integer r = perm.get(e.rowDof), c = perm.get(e.colDof);
            if (r == null || c == null) return false;
            // physical identity check: entry id is independent of indices
            if (!e.id.equals("entry:" + e.rowDof + ":" + e.colDof)) return false;
        }
        return true;
    }
}
