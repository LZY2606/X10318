package feassembly.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Raw, immutable source materials. Numeric values are kept in their original
 * textual representation ("raw") so float pattern, units and coordinate basis
 * are never lost on import. The derived layer performs normalization only.
 */
public final class RawModels {

    private RawModels() {}

    public static final class Node {
        public final String nodeId;
        public final String xRaw, yRaw;
        public final String coordBasis;

        public Node(String nodeId, String xRaw, String yRaw, String coordBasis) {
            this.nodeId = nodeId;
            this.xRaw = xRaw;
            this.yRaw = yRaw;
            this.coordBasis = coordBasis == null ? "CARTESIAN-LOCAL" : coordBasis;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("nodeId", nodeId)
                    .put("xRaw", xRaw)
                    .put("yRaw", yRaw)
                    .put("coordBasis", coordBasis);
        }

        public static Node fromJson(JSONObject o) {
            return new Node(o.getString("nodeId"), o.getString("xRaw"),
                    o.getString("yRaw"), o.optString("coordBasis", "CARTESIAN-LOCAL"));
        }
    }

    /** element connectivity: original element id + ordered node id list */
    public static final class Element {
        public final String elementId;
        public final List<String> nodeIds;
        public final String shape;

        public Element(String elementId, List<String> nodeIds, String shape) {
            this.elementId = elementId;
            this.nodeIds = new ArrayList<>(nodeIds);
            this.shape = shape == null ? "BAR2" : shape;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("elementId", elementId)
                    .put("nodeIds", new JSONArray(nodeIds))
                    .put("shape", shape);
        }

        public static Element fromJson(JSONObject o) {
            List<String> ids = new ArrayList<>();
            o.getJSONArray("nodeIds").forEach(v -> ids.add((String) v));
            return new Element(o.getString("elementId"), ids, o.optString("shape", "BAR2"));
        }
    }

    /**
     * Local matrix entry: value kept as raw text; floatMode records the
     * arithmetic pattern used by the provider (e.g. IEEE754-DECIMAL-INPUT).
     * dof sequence is the local dof order, e.g. ["n1:ux","n1:uy",...].
     */
    public static final class LocalMatrix {
        public final String elementId;
        public final List<String> localDofs;
        public final List<String[]> entries; // row,col,rawValue
        public final String units;
        public final String floatMode;

        public LocalMatrix(String elementId, List<String> localDofs,
                           List<String[]> entries, String units, String floatMode) {
            this.elementId = elementId;
            this.localDofs = new ArrayList<>(localDofs);
            this.entries = new ArrayList<>(entries);
            this.units = units;
            this.floatMode = floatMode == null ? "IEEE754-BINARY64" : floatMode;
        }

        public JSONObject toJson() {
            JSONArray arr = new JSONArray();
            for (String[] e : entries) {
                arr.put(new JSONArray().put(e[0]).put(e[1]).put(e[2]));
            }
            return new JSONObject()
                    .put("elementId", elementId)
                    .put("localDofs", new JSONArray(localDofs))
                    .put("entries", arr)
                    .put("units", units)
                    .put("floatMode", floatMode);
        }

        public static LocalMatrix fromJson(JSONObject o) {
            List<String> dofs = new ArrayList<>();
            o.getJSONArray("localDofs").forEach(v -> dofs.add((String) v));
            List<String[]> entries = new ArrayList<>();
            o.getJSONArray("entries").forEach(v -> {
                JSONArray a = (JSONArray) v;
                entries.add(new String[]{a.getString(0), a.getString(1), a.getString(2)});
            });
            return new LocalMatrix(o.getString("elementId"), dofs, entries,
                    o.optString("units", "N/m"), o.optString("floatMode", "IEEE754-BINARY64"));
        }
    }

    /** global dof mapping: physical dof key ("node:component") -> global index raw text */
    public static final class DofMapEntry {
        public final String physicalDof;
        public final String globalIndexRaw;

        public DofMapEntry(String physicalDof, String globalIndexRaw) {
            this.physicalDof = physicalDof;
            this.globalIndexRaw = globalIndexRaw;
        }

        public JSONObject toJson() {
            return new JSONObject().put("physicalDof", physicalDof)
                    .put("globalIndexRaw", globalIndexRaw);
        }

        public static DofMapEntry fromJson(JSONObject o) {
            return new DofMapEntry(o.getString("physicalDof"), o.getString("globalIndexRaw"));
        }
    }

    /** boundary constraint with version tag and strategy ELIMINATION or PENALTY */
    public static final class Constraint {
        public final String constraintId;
        public final String physicalDof;
        public final String valueRaw;
        public final String units;
        public final String strategy; // ELIMINATION / PENALTY
        public final String penaltyRaw;
        public final String constraintVersion;

        public Constraint(String constraintId, String physicalDof, String valueRaw,
                          String units, String strategy, String penaltyRaw,
                          String constraintVersion) {
            this.constraintId = constraintId;
            this.physicalDof = physicalDof;
            this.valueRaw = valueRaw;
            this.units = units;
            this.strategy = strategy;
            this.penaltyRaw = penaltyRaw;
            this.constraintVersion = constraintVersion;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject()
                    .put("constraintId", constraintId)
                    .put("physicalDof", physicalDof)
                    .put("valueRaw", valueRaw)
                    .put("units", units)
                    .put("strategy", strategy)
                    .put("constraintVersion", constraintVersion);
            if (penaltyRaw != null) o.put("penaltyRaw", penaltyRaw);
            return o;
        }

        public static Constraint fromJson(JSONObject o) {
            return new Constraint(o.getString("constraintId"), o.getString("physicalDof"),
                    o.getString("valueRaw"), o.optString("units", "m"),
                    o.getString("strategy"), o.optString("penaltyRaw", null),
                    o.optString("constraintVersion", "cv-1"));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Constraint)) return false;
            Constraint c = (Constraint) o;
            return Objects.equals(constraintId, c.constraintId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(constraintId);
        }
    }
}
