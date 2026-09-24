package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The raw project bundle at one point in time. Imported materials are stored
 * verbatim: original element numbering, local dof order, local matrix values,
 * global mapping, constraint revisions, units and float patterns.
 */
public class Snapshot {
    public String id;
    public String parentSnapshotId;
    public String label;
    public String revision;
    public long createdAt;
    /** Content hash of this exact raw bundle; import preserves raw representation. */
    public String contentHash;

    public List<Node> nodes = new ArrayList<>();
    public List<Element> elements = new ArrayList<>();
    public List<LocalMatrix> localMatrices = new ArrayList<>();
    public List<DofMap> dofMaps = new ArrayList<>();
    public List<ConstraintSet> constraintSets = new ArrayList<>();
    public List<RenumberSpec> renumbers = new ArrayList<>();
    public List<FloatSpec> floatSpecs = new ArrayList<>();

    /** Id of the currently selected raw components used by derived runs. */
    public String selectedDofMapId;
    public String selectedConstraintSetId;
    public String selectedRenumberId;
    public String selectedFloatSpecId;

    public LocalMatrix matrixOf(String id) {
        for (LocalMatrix m : localMatrices) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public DofMap dofMapOf(String id) {
        for (DofMap m : dofMaps) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public ConstraintSet constraintSetOf(String id) {
        for (ConstraintSet c : constraintSets) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public Node nodeOf(String id) {
        for (Node n : nodes) {
            if (n.id.equals(id)) {
                return n;
            }
        }
        return null;
    }
}
