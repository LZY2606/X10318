package femledger.model;

import java.util.List;
import java.util.Objects;

/**
 * An immutable, content-addressed snapshot of every direct source. All original identifiers,
 * local DOF order, raw matrix tokens, global mapping, constraint version, units and the float
 * summation mode are preserved verbatim. A new derivation produces a new version; the current
 * pointer only moves once a version is complete.
 */
public final class CaseVersion {
    private final String versionId;
    private final String parentVersionId;
    private final String label;
    private final String branchId;
    private final String createdFromEventId;
    private final long createdAt;
    private final String floatMode;       // SEQUENTIAL_SORTED | SEQUENTIAL_SOURCE
    private final List<Node> nodes;
    private final List<Element> elements;
    private final List<MappingEntry> mappings;
    private final List<ConstraintRec> constraints;

    public CaseVersion(String versionId, String parentVersionId, String label, String branchId,
                       String createdFromEventId, long createdAt, String floatMode,
                       List<Node> nodes, List<Element> elements, List<MappingEntry> mappings,
                       List<ConstraintRec> constraints) {
        this.versionId = versionId;
        this.parentVersionId = parentVersionId;
        this.label = label;
        this.branchId = branchId;
        this.createdFromEventId = createdFromEventId;
        this.createdAt = createdAt;
        this.floatMode = floatMode;
        this.nodes = List.copyOf(nodes);
        this.elements = List.copyOf(elements);
        this.mappings = List.copyOf(mappings);
        this.constraints = List.copyOf(constraints);
    }

    public String versionId() { return versionId; }
    public String parentVersionId() { return parentVersionId; }
    public String label() { return label; }
    public String branchId() { return branchId; }
    public String createdFromEventId() { return createdFromEventId; }
    public long createdAt() { return createdAt; }
    public String floatMode() { return floatMode; }
    public List<Node> nodes() { return nodes; }
    public List<Element> elements() { return elements; }
    public List<MappingEntry> mappings() { return mappings; }
    public List<ConstraintRec> constraints() { return constraints; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof CaseVersion v)) return false;
        return Objects.equals(versionId, v.versionId);
    }
    @Override public int hashCode() { return Objects.hashCode(versionId); }
}
