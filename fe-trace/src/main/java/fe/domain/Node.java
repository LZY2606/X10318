package fe.domain;

import java.util.Objects;

/** A mesh node with stable original id and coordinates carrying a frame. */
public final class Node {
    private final String nodeId;
    private final Coordinates coords;

    public Node(String nodeId, Coordinates coords) {
        this.nodeId = nodeId;
        this.coords = coords;
    }

    public String nodeId() { return nodeId; }
    public Coordinates coords() { return coords; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node n)) return false;
        return Objects.equals(nodeId, n.nodeId) && Objects.equals(coords, n.coords);
    }
    @Override public int hashCode() { return Objects.hash(nodeId, coords); }
}
