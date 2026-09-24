package gsb.fea.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit global degree-of-freedom mapping for one element. The key is the
 * local dof label (same text as {@link Element#localDofOrder()}) and the value
 * is the physical global dof key, e.g. {@code "T@node:3"}. A key appearing more
 * than once inside one element is the repeated-dof situation and is preserved
 * (not collapsed) so assembly can diagnose it.
 */
public record DofMap(String elementOriginalId, Map<String, String> localToGlobal) {

    public static DofMap of(String elementOriginalId, List<String> localLabels, List<String> globalKeys) {
        if (localLabels.size() != globalKeys.size()) {
            throw new IllegalArgumentException("dof map length mismatch for element " + elementOriginalId);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < localLabels.size(); i++) {
            map.put(localLabels.get(i), globalKeys.get(i));
        }
        return new DofMap(elementOriginalId, map);
    }
}
