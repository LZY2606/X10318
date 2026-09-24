package gsb.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw element connectivity: original element number + ordered nodes, exactly as imported. */
public final class ElementConnectivity {
    public final String originalNumber;
    public final String kind;
    public final List<String> nodes;
    public final Map<String, String> meta;

    public ElementConnectivity(String originalNumber, String kind, List<String> nodes,
                               Map<String, String> meta) {
        this.originalNumber = originalNumber;
        this.kind = kind;
        this.nodes = List.copyOf(nodes);
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    /** Degenerate element: repeated node id inside one connectivity (n1 == n2 ...). */
    public boolean degenerate() {
        return new ArrayList<>(nodes).size() != nodes.stream().distinct().count();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originalNumber", originalNumber);
        m.put("kind", kind);
        m.put("nodes", nodes);
        m.put("meta", meta);
        return m;
    }
}
