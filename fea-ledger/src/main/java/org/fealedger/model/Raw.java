package org.fealedger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw, as-imported engineering data. Nothing here is normalized: numeric
 * values keep their original textual representation; every quantity carries
 * its own unit / coordinate basis. Identity is the string id assigned by the
 * origin team and must survive export/import unchanged.
 */
public final class Raw {
    private Raw() {}

    /** Immutable key-value record using insertion order (stable serialization). */
    public static final class Doc {
        public final LinkedHashMap<String, Object> m = new LinkedHashMap<>();

        public Doc set(String k, Object v) { m.put(k, v); return this; }
        public Object get(String k) { return m.get(k); }
        public String str(String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
        public int integer(String k, int dflt) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : dflt; }
        public boolean bool(String k, boolean dflt) { Object v = m.get(k); return v instanceof Boolean b ? b : dflt; }

        @SuppressWarnings("unchecked")
        public LinkedHashMap<String, Object> obj(String k) {
            Object v = m.get(k);
            return v instanceof LinkedHashMap<?, ?> h ? (LinkedHashMap<String, Object>) h : null;
        }
        @SuppressWarnings("unchecked")
        public List<Object> list(String k) {
            Object v = m.get(k);
            return v instanceof List<?> l ? (List<Object>) l : null;
        }
        public boolean has(String k) { return m.containsKey(k); }
    }

    /** A physical node with coordinates kept verbatim plus coordinate basis id. */
    public static final class Node {
        public final String id;
        public final String originalNumber;
        public final String[] coords;          // raw decimal strings
        public final String coordinateBasis;   // e.g. "CARTESIAN:mm"
        public final String unit;              // coordinate unit
        public final Doc attributes;
        public Node(String id, String originalNumber, String[] coords,
                    String coordinateBasis, String unit, Doc attributes) {
            this.id = id;
            this.originalNumber = originalNumber;
            this.coords = coords;
            this.coordinateBasis = coordinateBasis;
            this.unit = unit;
            this.attributes = attributes;
        }
    }

    /**
     * Element connectivity: ordered list of node ids.
     * duplicateNodeIds are kept (not deduplicated) so the duplicate-DOF case is
     * detectable at derivation time.
     */
    public static final class Element {
        public final String id;
        public final String originalNumber;   // original element number, verbatim
        public final String type;
        public final List<String> nodeIds;    // ordered connectivity
        public final List<String> dofLabels;  // per-connectivity-slot dof order, e.g. u,v
        public final String localMatrixId;
        public final Doc attributes;
        public Element(String id, String originalNumber, String type, List<String> nodeIds,
                       List<String> dofLabels, String localMatrixId, Doc attributes) {
            this.id = id;
            this.originalNumber = originalNumber;
            this.type = type;
            this.nodeIds = nodeIds;
            this.dofLabels = dofLabels;
            this.localMatrixId = localMatrixId;
            this.attributes = attributes;
        }

        /** Local dof count = slots * labels-per-slot. */
        public int localDofCount() { return nodeIds.size() * dofLabels.size(); }
    }

    /** Local element matrix; entries keep raw textual values. */
    public static final class LocalMatrix {
        public static final class Entry {
            public final int row, col;              // 0-based local indices
            public final String rawValue;           // original decimal text
            public final String unit;               // entry unit
            public final String floatMode;          // e.g. IEEE754:binary64:ROUND_HALF_EVEN
            public Entry(int row, int col, String rawValue, String unit, String floatMode) {
                this.row = row; this.col = col; this.rawValue = rawValue;
                this.unit = unit; this.floatMode = floatMode;
            }
        }
        public final String id;
        public final String version;
        public final int size;
        public final List<Entry> entries;   // ordered exactly as imported
        public final Doc attributes;
        public LocalMatrix(String id, String version, int size, List<Entry> entries, Doc attributes) {
            this.id = id; this.version = version; this.size = size;
            this.entries = entries; this.attributes = attributes;
        }
    }

    /**
     * Optional explicit local->global mapping for one element (a "dof map" row).
     * A null globalDof means the source left the slot unmapped (diagnostic).
     */
    public static final class DofMapEntry {
        public final String elementId;
        public final int localIndex;
        public final String globalDofId;   // may be null
        public final DofMapEntry(String elementId, int localIndex, String globalDofId) {
            this.elementId = elementId; this.localIndex = localIndex; this.globalDofId = globalDofId;
        }
    }

    public enum ConstraintKind { FIXED, PRESCRIBED, LINEAR }

    /** Boundary constraint, versioned. Strategy is declared per constraint set. */
    public static final class Constraint {
        public final String id;
        public final String version;
        public final ConstraintKind kind;
        public final String globalDofId;
        public final String rawValue;       // null for FIXED-without-value
        public final String unit;
        public final String floatMode;
        public final List<String> coupledDofIds; // LINEAR: coupled dofs (kept ordered)
        public final List<String> coeffs;       // LINEAR: matching raw coefficients
        public final Doc attributes;
        public Constraint(String id, String version, ConstraintKind kind, String globalDofId,
                          String rawValue, String unit, String floatMode,
                          List<String> coupledDofIds, List<String> coeffs, Doc attributes) {
            this.id = id; this.version = version; this.kind = kind; this.globalDofId = globalDofId;
            this.rawValue = rawValue; this.unit = unit; this.floatMode = floatMode;
            this.coupledDofIds = coupledDofIds; this.coeffs = coeffs; this.attributes = attributes;
        }
    }

    /** Whole raw project snapshot as imported. */
    public static final class Project {
        public String id;
        public String name;
        public String revision;
        public String defaultCoordinateBasis;
        public String defaultUnit;
        public String defaultFloatMode;
        public final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        public final LinkedHashMap<String, Element> elements = new LinkedHashMap<>();
        public final LinkedHashMap<String, LocalMatrix> matrices = new LinkedHashMap<>();
        public final List<DofMapEntry> dofMap = new ArrayList<>();
        public final LinkedHashMap<String, Constraint> constraints = new LinkedHashMap<>();
        public String constraintStrategy;   // ELIMINATION or PENALTY (never mixed within a run)
        public final Doc attributes = new Doc();
    }
}
