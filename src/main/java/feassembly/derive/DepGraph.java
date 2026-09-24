package feassembly.derive;

import feassembly.model.CaseState;
import feassembly.model.RawModels;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency graph for incremental recomputation.
 *
 * Nodes: raw materials (node/element/localMatrix/dofMap/constraint),
 * per-element "elementContrib" derived nodes, per-entry "nz" nodes, the
 * "freeAssembly" node and the "constraintApply" node.
 *
 * Each node has a fingerprint of its direct inputs. Revalidating a state
 * against previously recorded fingerprints tells the reviewer exactly which
 * results are still VALID and which must be recomputed (and why), without
 * rebuilding the untouched part of the graph.
 */
public final class DepGraph {

    public static final class NodeInfo {
        public final String nodeId, kind, elementId;
        public final List<String> deps;
        public final String fingerprint;

        public NodeInfo(String nodeId, String kind, String elementId,
                        List<String> deps, String fingerprint) {
            this.nodeId = nodeId; this.kind = kind; this.elementId = elementId;
            this.deps = deps; this.fingerprint = fingerprint;
        }
    }

    private DepGraph() {}

    public static String fp(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return "DFP-" + Long.toHexString(h);
    }

    /** Build graph with current fingerprints. */
    public static JSONObject build(CaseState s, JSONObject derived) {
        JSONArray nodes = new JSONArray();
        JSONArray edges = new JSONArray();
        Map<String, NodeInfo> infos = new LinkedHashMap<>();

        for (RawModels.Node n : s.nodes.values()) {
            NodeInfo ni = new NodeInfo("raw:node:" + n.nodeId, "RAW_NODE", null,
                    new ArrayList<>(), fp(n.toJson().toString()));
            infos.put(ni.nodeId, ni);
        }
        for (RawModels.Element e : s.elements.values()) {
            List<String> deps = new ArrayList<>();
            e.nodeIds.forEach(nid -> deps.add("raw:node:" + nid));
            NodeInfo ni = new NodeInfo("raw:elem:" + e.elementId, "RAW_ELEMENT",
                    e.elementId, deps, fp(e.toJson().toString()));
            infos.put(ni.nodeId, ni);
        }
        for (RawModels.LocalMatrix m : s.localMatrices.values()) {
            NodeInfo ni = new NodeInfo("raw:lm:" + m.elementId, "RAW_LOCAL_MATRIX",
                    m.elementId, new ArrayList<>(), fp(m.toJson().toString()));
            infos.put(ni.nodeId, ni);
        }
        for (RawModels.DofMapEntry d : s.dofMap.values()) {
            NodeInfo ni = new NodeInfo("raw:dof:" + d.physicalDof, "RAW_DOF", null,
                    new ArrayList<>(), fp(d.toJson().toString()));
            infos.put(ni.nodeId, ni);
        }
        for (RawModels.Constraint c : s.constraints.values()) {
            NodeInfo ni = new NodeInfo("raw:con:" + c.constraintId, "RAW_CONSTRAINT",
                    null, new ArrayList<>(), fp(c.toJson().toString()));
            infos.put(ni.nodeId, ni);
        }

        // per-element derived contribution node
        for (RawModels.LocalMatrix m : s.localMatrices.values()) {
            String id = "der:elemContrib:" + m.elementId;
            List<String> deps = new ArrayList<>();
            deps.add("raw:lm:" + m.elementId);
            if (s.elements.containsKey(m.elementId)) deps.add("raw:elem:" + m.elementId);
            m.localDofs.forEach(ld -> {
                if (s.dofMap.containsKey(ld)) deps.add("raw:dof:" + ld);
            });
            NodeInfo ni = new NodeInfo(id, "DER_ELEMENT_CONTRIB", m.elementId, deps,
                    fp(deps + "|" + m.toJson()));
            infos.put(id, ni);
        }

        // nz nodes from the assembled matrix
        String freeId = "der:freeAssembly";
        List<String> freeDeps = new ArrayList<>();
        s.localMatrices.keySet().forEach(e -> freeDeps.add("der:elemContrib:" + e));
        s.dofMap.keySet().forEach(d -> freeDeps.add("raw:dof:" + d));
        infos.put(freeId, new NodeInfo(freeId, "DER_FREE_ASSEMBLY", null, freeDeps,
                derived.getJSONObject("freeMatrix").getString("units")
                        + "#" + derived.optString("fingerprint", "")));

        String conId = "der:constraintApply";
        List<String> conDeps = new ArrayList<>();
        conDeps.add(freeId);
        s.constraints.keySet().forEach(c -> conDeps.add("raw:con:" + c));
        infos.put(conId, new NodeInfo(conId, "DER_CONSTRAINT_APPLY", null, conDeps,
                fp(derived.getJSONArray("constraintCandidates").toString())));

        for (NodeInfo ni : infos.values()) {
            nodes.put(new JSONObject().put("nodeId", ni.nodeId).put("kind", ni.kind)
                    .put("elementId", ni.elementId == null ? JSONObject.NULL : ni.elementId)
                    .put("fingerprint", ni.fingerprint));
            for (String d : ni.deps) {
                edges.put(new JSONObject().put("from", d).put("to", ni.nodeId));
            }
        }
        return new JSONObject().put("nodes", nodes).put("edges", edges);
    }
