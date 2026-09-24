package gsb.fea.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of all source material. Derivations point at a version and never
 * mutate it; a new edit produces a new version and (only on complete success) moves the
 * current pointer.
 */
public final class ProjectVersion {
    public String id;
    public String parentId;
    public String label;
    public String author = "engineering";
    public long createdAtMillis;
    public String editSummary = "";

    public Map<String, Node> nodes = new LinkedHashMap<>();
    public Map<String, Element> elements = new LinkedHashMap<>();
    public Map<String, LocalMatrix> matrices = new LinkedHashMap<>();
    public Map<String, DofMapping> mappings = new LinkedHashMap<>();
    public Map<String, ConstraintSet> constraintSets = new LinkedHashMap<>();
    public Map<String, Renumber> renumbers = new LinkedHashMap<>();

    /** Revision selected as the active input for assembly on this version. */
    public String selectedMappingRevision;
    public String selectedConstraintRevision;
    public String selectedRenumberRevision;

    public List<String> tags = new ArrayList<>();

    public ProjectVersion() {
    }

    public ProjectVersion(String id, String parentId, String label, long createdAtMillis) {
        this.id = id;
        this.parentId = parentId;
        this.label = label;
        this.createdAtMillis = createdAtMillis;
    }

    public DofMapping selectedMapping() {
        return mappings.get(selectedMappingRevision);
    }

    public ConstraintSet selectedConstraints() {
        return constraintSets.get(selectedConstraintRevision);
    }

    public Renumber selectedRenumber() {
        return selectedRenumberRevision == null ? null : renumbers.get(selectedRenumberRevision);
    }
}
