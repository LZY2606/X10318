package gsb.fea.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A renumbering decision. It is an index-only permutation: every global dof keeps its
 * physical identity; only its dense matrix index changes.
 */
public final class Renumber {
    public String revision;
    public Map<String, Integer> dofToNewIndex = new LinkedHashMap<>();
    public String rationale = "";

    public Renumber() {
    }
}
