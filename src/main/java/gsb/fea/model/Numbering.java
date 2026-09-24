package gsb.fea.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps physical dof keys to compact global matrix indices. A renumbering only
 * changes the index assigned to a physical dof; it never changes the physical
 * correspondence, and the physical key remains attached to every contribution.
 */
public record Numbering(String id, String description, Map<String, Integer> dofKeyToIndex) {

    public int size() {
        return dofKeyToIndex.size();
    }

    public static Numbering natural(String id, String description, java.util.Collection<String> dofKeys) {
        Map<String, Integer> m = new LinkedHashMap<>();
        int idx = 0;
        for (String key : dofKeys) {
            m.putIfAbsent(key, idx++);
        }
        return new Numbering(id, description, m);
    }
}
