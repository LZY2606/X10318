package fe.domain;

import java.util.Objects;

/**
 * One local degree of freedom of an element, in declared local order.
 *
 * <p>{@code slot} is the zero-based position in the local ordering.
 * {@code physicalDoF} is the stable physical identity (node id + component),
 * which never changes under renumbering. {@code globalIndex} is the current
 * numeric global mapping; renumbering changes only this, never the physical
 * identity.
 *
 * <p>Multiple local slots may legally map to the same physical dof (repeated
 * dof / duplicated connectivity); that is handled and diagnosed at assembly.
 */
public final class LocalDoF {
    private final int slot;
    private final String nodeId;
    private final String component;
    private final String physicalDoF;
    private final int globalIndex;

    public LocalDoF(int slot, String nodeId, String component, String physicalDoF, int globalIndex) {
        this.slot = slot;
        this.nodeId = nodeId;
        this.component = component;
        this.physicalDoF = physicalDoF;
        this.globalIndex = globalIndex;
    }

    public static String physicalKey(String nodeId, String component) {
        return nodeId + ":" + component;
    }

    public int slot() { return slot; }
    public String nodeId() { return nodeId; }
    public String component() { return component; }
    public String physicalDoF() { return physicalDoF; }
    public int globalIndex() { return globalIndex; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocalDoF d)) return false;
        return slot == d.slot && globalIndex == d.globalIndex
                && Objects.equals(nodeId, d.nodeId)
                && Objects.equals(component, d.component)
                && Objects.equals(physicalDoF, d.physicalDoF);
    }
    @Override public int hashCode() { return Objects.hash(slot, nodeId, component, physicalDoF, globalIndex); }
}
