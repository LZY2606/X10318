package femprov;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency graph for recomputation. Derived nodes declare their inputs;
 * when a source changes, only transitive dependents are invalidated and
 * recomputed. Every node can explain why it is still valid or why it was
 * (or must be) recomputed.
 */
public final class DepGraph {
    public record Node(String key, List<String> deps, boolean dirty, String reason) {}

    private final Map<String, List<String>> deps = new LinkedHashMap<>();
    private final Map<String, Boolean> dirty = new LinkedHashMap<>();
    private final Map<String, String> reason = new LinkedHashMap<>();
    private final Map<String, Long> computedAtSeq = new LinkedHashMap<>();

    public void register(String key, List<String> inputs) {
        deps.put(key, List.copyOf(inputs));
        dirty.put(key, true);
        reason.put(key, "never computed");
    }

    /** Mark a source key changed at a seq; invalidate transitive dependents. */
    public List<String> sourceChanged(String sourceKey, long seq) {
        List<String> affected = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(sourceKey);
        dirty.put(sourceKey, true);
        reason.put(sourceKey, "source changed at seq " + seq);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (var e : deps.entrySet()) {
                if (e.getValue().contains(cur) && !Boolean.TRUE.equals(dirty.get(e.getKey()))) {
                    dirty.put(e.getKey(), true);
                    reason.put(e.getKey(), "dependency '" + cur + "' changed at seq " + seq);
                    affected.add(e.getKey());
                    queue.add(e.getKey());
                }
            }
        }
        return affected;
    }

    public boolean isDirty(String key) { return Boolean.TRUE.equals(dirty.get(key)); }

    public void markComputed(String key, long seq) {
        dirty.put(key, false);
        computedAtSeq.put(key, seq);
        reason.put(key, "computed at seq " + seq + " from " + deps.getOrDefault(key, List.of()));
    }

    /** Why is this node still valid, or why does it need recompute? */
    public String explain(String key) {
        if (!deps.containsKey(key)) return "unknown node " + key;
        if (isDirty(key)) return "NEEDS RECOMPUTE: " + reason.get(key);
        return "VALID: " + reason.get(key) + "; inputs unchanged since seq " + computedAtSeq.get(key);
    }

    public List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        for (String k : deps.keySet())
            out.add(new Node(k, deps.get(k), isDirty(k), explain(k)));
        return out;
    }
}
