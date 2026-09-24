package femledger.model;

import java.util.List;
import java.util.Objects;

/** A mesh node. Coordinates carry their coordinate datum (e.g. {@code XY-CARTESIAN-m}). */
public final class Node {
    private final String id;
    private final String originalId;
    private final List<RawValue> coords;

    public Node(String id, String originalId, List<RawValue> coords) {
        this.id = id;
        this.originalId = originalId;
        this.coords = List.copyOf(coords);
    }

    public String id() { return id; }
    public String originalId() { return originalId; }
    public List<RawValue> coords() { return coords; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Node n)) return false;
        return Objects.equals(id, n.id) && Objects.equals(originalId, n.originalId)
                && Objects.equals(coords, n.coords);
    }
    @Override public int hashCode() { return Objects.hash(id, originalId, coords); }
}
