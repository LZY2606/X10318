package fe.domain;

import java.util.List;
import java.util.Objects;

/**
 * A finite element with original element id, node connectivity, local dof
 * ordering and local matrix. Degenerate elements (repeated node in
 * connectivity, zero/negative volume inferred from geometry) are retained and
 * diagnosed, never silently dropped.
 */
public final class Element {
    private final String elementId;
    private final String type;
    private final List<String> nodeIds;
    private final List<LocalDoF> dofs;
    private final LocalMatrix matrix;

    public Element(String elementId, String type, List<String> nodeIds,
                   List<LocalDoF> dofs, LocalMatrix matrix) {
        this.elementId = elementId;
        this.type = type;
        this.nodeIds = List.copyOf(nodeIds);
        this.dofs = List.copyOf(dofs);
        this.matrix = matrix;
    }

    public String elementId() { return elementId; }
    public String type() { return type; }
    public List<String> nodeIds() { return nodeIds; }
    public List<LocalDoF> dofs() { return dofs; }
    public LocalMatrix matrix() { return matrix; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Element e)) return false;
        return Objects.equals(elementId, e.elementId) && Objects.equals(type, e.type)
                && Objects.equals(nodeIds, e.nodeIds) && Objects.equals(dofs, e.dofs)
                && Objects.equals(matrix, e.matrix);
    }
    @Override public int hashCode() { return Objects.hash(elementId, type, nodeIds, dofs, matrix); }
}
