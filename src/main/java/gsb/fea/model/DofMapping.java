package gsb.fea.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A revision of the local-to-global degree-of-freedom map.
 * One local dof may legitimately point at a global dof already used elsewhere in the
 * same element (duplicate dof / rigid coupling); the assembler detects and orders it.
 */
public final class DofMapping {
    public String revision;
    public List<MappingEntry> entries = new ArrayList<>();

    public DofMapping() {
    }

    public static final class MappingEntry {
        public String elementId;
        public String localDof;
        public String globalDofId;
        public int localIndex;

        public MappingEntry() {
        }

        public MappingEntry(String elementId, int localIndex, String localDof, String globalDofId) {
            this.elementId = elementId;
            this.localIndex = localIndex;
            this.localDof = localDof;
            this.globalDofId = globalDofId;
        }
    }

    public List<MappingEntry> forElement(String elementId) {
        List<MappingEntry> out = new ArrayList<>();
        for (MappingEntry entry : entries) {
            if (entry.elementId.equals(elementId)) {
                out.add(entry);
            }
        }
        return out;
    }
}
