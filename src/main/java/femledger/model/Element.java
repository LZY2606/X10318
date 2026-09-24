package femledger.model;

import java.util.List;
import java.util.Objects;

/**
 * An element as imported. {@code localDofOrder} is the ordered list of local degree-of-freedom
 * labels (e.g. {@code ["u1","v1", ...]}). {@code localMatrix} is the flat row-major local
 * stiffness matrix of size {@code localDofOrder}^2, every entry carrying its original token.
 */
public final class Element {
    private final String id;
    private final String originalId;
    private final String type;
    private final List<String> nodeIds;
    private final List<String> localDofOrder;
    private final List<RawValue> localMatrix;

    public Element(String id, String originalId, String type, List<String> nodeIds,
                   List<String> localDofOrder, List<RawValue> localMatrix) {
        this.id = id;
        this.originalId = originalId;
        this.type = type;
        this.nodeIds = List.copyOf(nodeIds);
        this.localDofOrder = List.copyOf(localDofOrder);
        this.localMatrix = List.copyOf(localMatrix);
    }

    public String id() { return id; }
    public String originalId() { return originalId; }
    public String type() { return type; }
    public List<String> nodeIds() { return nodeIds; }
    public List<String> localDofOrder() { return localDofOrder; }
    public List<RawValue> localMatrix() { return localMatrix; }
    public int matrixSize() { return localDofOrder.size(); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Element e)) return false;
        return Objects.equals(id, e.id) && Objects.equals(originalId, e.originalId)
                && Objects.equals(type, e.type) && Objects.equals(nodeIds, e.nodeIds)
                && Objects.equals(localDofOrder, e.localDofOrder)
                && Objects.equals(localMatrix, e.localMatrix);
    }
    @Override public int hashCode() {
        return Objects.hash(id, originalId, type, nodeIds, localDofOrder, localMatrix);
    }
}
