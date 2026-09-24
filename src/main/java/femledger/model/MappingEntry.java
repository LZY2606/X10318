package femledger.model;

import java.util.Objects;

/** One element-local DOF -> global DOF label mapping, in local DOF order. */
public final class MappingEntry {
    private final String id;
    private final String elementId;
    private final String localDof;
    private final String globalDof;

    public MappingEntry(String id, String elementId, String localDof, String globalDof) {
        this.id = id;
        this.elementId = elementId;
        this.localDof = localDof;
        this.globalDof = globalDof;
    }

    public String id() { return id; }
    public String elementId() { return elementId; }
    public String localDof() { return localDof; }
    public String globalDof() { return globalDof; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof MappingEntry m)) return false;
        return Objects.equals(id, m.id) && Objects.equals(elementId, m.elementId)
                && Objects.equals(localDof, m.localDof) && Objects.equals(globalDof, m.globalDof);
    }
    @Override public int hashCode() { return Objects.hash(id, elementId, localDof, globalDof); }
}
