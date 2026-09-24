package feassembly.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full logical state of a case at some point in history. Ordered maps keep
 * import order stable; all materials retain their original representation.
 */
public final class CaseState {
    public final String stateId;
    public final String schemaVersion;
    public final Map<String, RawModels.Node> nodes = new LinkedHashMap<>();
    public final Map<String, RawModels.Element> elements = new LinkedHashMap<>();
    public final Map<String, RawModels.LocalMatrix> localMatrices = new LinkedHashMap<>();
    public final Map<String, RawModels.DofMapEntry> dofMap = new LinkedHashMap<>();
    public final Map<String, RawModels.Constraint> constraints = new LinkedHashMap<>();

    public CaseState(String stateId) {
        this(stateId, "1.0");
    }

    public CaseState(String stateId, String schemaVersion) {
        this.stateId = stateId;
        this.schemaVersion = schemaVersion;
    }

    public CaseState copy(String newStateId) {
        JSONObject json = toJson();
        json.put("stateId", newStateId);
        return fromJson(json);
    }

    public JSONObject toJson() {
        JSONArray n = new JSONArray();
        nodes.values().forEach(x -> n.put(x.toJson()));
        JSONArray e = new JSONArray();
        elements.values().forEach(x -> e.put(x.toJson()));
        JSONArray lm = new JSONArray();
        localMatrices.values().forEach(x -> lm.put(x.toJson()));
        JSONArray dm = new JSONArray();
        dofMap.values().forEach(x -> dm.put(x.toJson()));
        JSONArray c = new JSONArray();
        constraints.values().forEach(x -> c.put(x.toJson()));
        return new JSONObject()
                .put("stateId", stateId)
                .put("schemaVersion", schemaVersion)
                .put("nodes", n)
                .put("elements", e)
                .put("localMatrices", lm)
                .put("dofMap", dm)
                .put("constraints", c);
    }

    public static CaseState fromJson(JSONObject o) {
        CaseState s = new CaseState(o.getString("stateId"),
                o.optString("schemaVersion", "1.0"));
        o.getJSONArray("nodes").forEach(v -> {
            RawModels.Node nd = RawModels.Node.fromJson((JSONObject) v);
            s.nodes.put(nd.nodeId, nd);
        });
        o.getJSONArray("elements").forEach(v -> {
            RawModels.Element el = RawModels.Element.fromJson((JSONObject) v);
            s.elements.put(el.elementId, el);
        });
        o.getJSONArray("localMatrices").forEach(v -> {
            RawModels.LocalMatrix lm = RawModels.LocalMatrix.fromJson((JSONObject) v);
            s.localMatrices.put(lm.elementId, lm);
        });
        o.getJSONArray("dofMap").forEach(v -> {
            RawModels.DofMapEntry dm = RawModels.DofMapEntry.fromJson((JSONObject) v);
            s.dofMap.put(dm.physicalDof, dm);
        });
        o.getJSONArray("constraints").forEach(v -> {
            RawModels.Constraint c = RawModels.Constraint.fromJson((JSONObject) v);
            s.constraints.put(c.constraintId, c);
        });
        return s;
    }

    public List<String> orderedGlobalDofs() {
        List<String> all = new ArrayList<>(dofMap.keySet());
        all.sort((a, b) -> Integer.compare(parseIndex(a), parseIndex(b)));
        return all;
    }

    private int parseIndex(String physical) {
        try {
            return Integer.parseInt(dofMap.get(physical).globalIndexRaw.trim());
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
