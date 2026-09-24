package feassembly.core;

import feassembly.model.CaseState;
import feassembly.model.RawModels;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure functions that apply one event payload to a CaseState, producing the
 * next state. No side effects, no storage access. Unknown event types throw.
 */
public final class CommitApplier {

    private CommitApplier() {}

    public static CaseState apply(CaseState before, String eventType, JSONObject p) {
        CaseState s = before.copy(p.optString("newStateId",
                before.stateId + "->" + eventType));
        switch (eventType) {
            case "IMPORT_BASELINE": importAll(s, p); break;
            case "PARAM_REVISION": paramRevision(s, p); break;
            case "LOCAL_SOURCE_CHANGE": localSourceChange(s, p); break;
            case "CONFLICTING_DECISION": conflictingDecision(s, p); break;
            case "RENUMBER": renumber(s, p); break;
            case "ADD_ELEMENT_SCENARIO": addElementScenario(s, p); break;
            case "RESOLVE_CHOICE": resolveChoice(s, p); break;
            case "ADOPT_BRANCH": /* state unchanged, decision recorded */ break;
            default: throw new IllegalArgumentException("unknown event type " + eventType);
        }
        return s;
    }

    private static void importAll(CaseState s, JSONObject p) {
        p.getJSONArray("nodes").forEach(v -> {
            RawModels.Node n = RawModels.Node.fromJson((JSONObject) v);
            s.nodes.put(n.nodeId, n);
        });
        p.getJSONArray("elements").forEach(v -> {
            RawModels.Element e = RawModels.Element.fromJson((JSONObject) v);
            s.elements.put(e.elementId, e);
        });
        p.getJSONArray("localMatrices").forEach(v -> {
            RawModels.LocalMatrix m = RawModels.LocalMatrix.fromJson((JSONObject) v);
            s.localMatrices.put(m.elementId, m);
        });
        p.getJSONArray("dofMap").forEach(v -> {
            RawModels.DofMapEntry d = RawModels.DofMapEntry.fromJson((JSONObject) v);
            s.dofMap.put(d.physicalDof, d);
        });
        p.getJSONArray("constraints").forEach(v -> {
            RawModels.Constraint c = RawModels.Constraint.fromJson((JSONObject) v);
            s.constraints.put(c.constraintId, c);
        });
    }

    /** parameter revision: changes one matrix value (raw text kept). */
    private static void paramRevision(CaseState s, JSONObject p) {
        String elem = p.getString("elementId");
        int r = p.getInt("localRow"), c = p.getInt("localCol");
        String value = p.getString("valueRaw");
        RawModels.LocalMatrix lm = s.localMatrices.get(elem);
        List<String[]> entries = new ArrayList<>(lm.entries);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            String[] e = entries.get(i);
            if (Integer.parseInt(e[0].trim()) == r && Integer.parseInt(e[1].trim()) == c) {
                entries.set(i, new String[]{e[0], e[1], value});
                replaced = true;
            }
        }
        if (!replaced) entries.add(new String[]{String.valueOf(r), String.valueOf(c), value});
        s.localMatrices.put(elem, new RawModels.LocalMatrix(lm.elementId, lm.localDofs,
                entries, lm.units, p.optString("floatMode", lm.floatMode)));
    }

    /**
     * local-only source change: edits dof map of one element's dofs (or a
     * local coordinate basis); dependency propagation marks only that element.
     */
    private static void localSourceChange(CaseState s, JSONObject p) {
        if (p.has("dofMapUpdates")) {
            p.getJSONArray("dofMapUpdates").forEach(v -> {
                RawModels.DofMapEntry d = RawModels.DofMapEntry.fromJson((JSONObject) v);
                s.dofMap.put(d.physicalDof, d);
            });
        }
        if (p.has("coordBasis")) {
            String node = p.getString("nodeId");
            RawModels.Node old = s.nodes.get(node);
            s.nodes.put(node, new RawModels.Node(old.nodeId, old.xRaw, old.yRaw,
                    p.getString("coordBasis")));
        }
        if (p.has("localDofReorder")) {
            String elem = p.getString("elementId");
            RawModels.LocalMatrix lm = s.localMatrices.get(elem);
            JSONArray order = p.getJSONArray("localDofReorder");
            List<String> newOrder = new ArrayList<>();
            order.forEach(v -> newOrder.add((String) v));
            int k = newOrder.size();
            Map<String, Integer> idxOld = new LinkedHashMap<>();
            for (int i = 0; i < lm.localDofs.size(); i++) idxOld.put(lm.localDofs.get(i), i);
            double[][] old = new double[k][k];
            String[][] oldRaw = new String[k][k];
            for (String[] e : lm.entries) {
                int r = Integer.parseInt(e[0].trim()), c = Integer.parseInt(e[1].trim());
                old[r][c] = safe(e[2]); oldRaw[r][c] = e[2];
            }
            List<String[]> rebuilt = new ArrayList<>();
            for (int r = 0; r < k; r++) {
                for (int c = 0; c < k; c++) {
                    int or = idxOld.get(newOrder.get(r));
                    int oc = idxOld.get(newOrder.get(c));
                    rebuilt.add(new String[]{String.valueOf(r), String.valueOf(c),
                            oldRaw[or][oc] == null ? fmt(old[or][oc]) : oldRaw[or][oc]});
                }
            }
            s.localMatrices.put(elem, new RawModels.LocalMatrix(lm.elementId, newOrder,
                    rebuilt, lm.units, lm.floatMode));
        }
    }

    private static double safe(String raw) {
        try { return Assembler.parseDouble(raw); } catch (Exception e) { return Double.NaN; }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "UNCONVERTIBLE" : String.valueOf(v);
    }

    /**
     * Human decision that conflicts with an existing constraint version.
     * Adds a second constraint on the same dof; assembly reports
     * CONSTRAINT_CONFLICT and keeps both candidates.
     */
    private static void conflictingDecision(CaseState s, JSONObject p) {
        RawModels.Constraint c = new RawModels.Constraint(
                p.getString("constraintId"), p.getString("physicalDof"),
                p.getString("valueRaw"), p.optString("units", "m"),
                p.optString("strategy", "ELIMINATION"),
                p.optString("penaltyRaw", null),
                p.getString("constraintVersion"));
        s.constraints.put(c.constraintId, c);
    }

    /**
     * Renumber: applies a permutation to global indices. Physical identity is
     * preserved (physical dof keys unchanged), only indices change. A
     * physicalCorrespondence table is carried in the payload for the UI.
     */
    private static void renumber(CaseState s, JSONObject p) {
        Map<String, String> perm = new LinkedHashMap<>();
        p.getJSONArray("permutation").forEach(v -> {
            JSONObject o = (JSONObject) v;
            perm.put(o.getString("physicalDof"), o.getString("newGlobalIndexRaw"));
        });
        for (Map.Entry<String, String> e : perm.entrySet()) {
            RawModels.DofMapEntry old = s.dofMap.get(e.getKey());
            s.dofMap.put(e.getKey(),
                    new RawModels.DofMapEntry(old.physicalDof, e.getValue()));
        }
    }

    /**
     * Scenario injection for the counterexample archive. Mutates exactly one
     * direct source per scenario kind so blast radius stays checkable.
     */
    private static void addElementScenario(CaseState s, JSONObject p) {
        String kind = p.getString("scenario");
        switch (kind) {
            case "DUPLICATE_DOF": {
                // one element maps two local dofs onto the same physical dof
                String elem = p.getString("elementId");
                String fromDof = p.getString("localDof");
                String toDof = p.getString("aliasTo");
                RawModels.LocalMatrix lm = s.localMatrices.get(elem);
                List<String> dofs = new ArrayList<>(lm.localDofs);
                int idx = dofs.indexOf(fromDof);
                dofs.set(idx, toDof);
                s.localMatrices.put(elem, new RawModels.LocalMatrix(lm.elementId, dofs,
                        lm.entries, lm.units, lm.floatMode));
                break;
            }
            case "DEGENERATE_ELEMENT": {
                String elem = p.getString("elementId");
                RawModels.Element el = s.elements.get(elem);
                List<String> nodes = new ArrayList<>(el.nodeIds);
                nodes.set(nodes.size() - 1, nodes.get(0));
                s.elements.put(elem, new RawModels.Element(el.elementId, nodes, el.shape));
                break;
            }
            case "ISOLATED_NODE": {
                RawModels.Node n = RawModels.Node.fromJson(p.getJSONObject("node"));
                s.nodes.put(n.nodeId, n);
                p.getJSONArray("dofMap").forEach(v -> {
                    RawModels.DofMapEntry d = RawModels.DofMapEntry.fromJson((JSONObject) v);
                    s.dofMap.put(d.physicalDof, d);
                });
                break;
            }
            case "CONSTRAINT_CONFLICT":
                conflictingDecision(s, p);
                break;
            case "FLOAT_SUM_ORDER": {
                // add a second element contributing into the same global pair,
                // with a decimal that rounds differently in binary64
                importOneElement(s, p.getJSONObject("element"));
                importOneMatrix(s, p.getJSONObject("localMatrix"));
                break;
            }
            case "RENUMBER":
                renumber(s, p);
                break;
            case "LOCAL_MATRIX_ASYMMETRIC": {
                // perturb one off-diagonal entry of one element only
                paramRevision(s, p);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown scenario " + kind);
        }
    }

    private static void importOneElement(CaseState s, JSONObject o) {
        RawModels.Element e = RawModels.Element.fromJson(o);
        s.elements.put(e.elementId, e);
    }

    private static void importOneMatrix(CaseState s, JSONObject o) {
        RawModels.LocalMatrix m = RawModels.LocalMatrix.fromJson(o);
        s.localMatrices.put(m.elementId, m);
    }

    /**
     * Reviewer resolution: chooses ELIMINATION / PENALTY for an unresolved
     * constraint set, or withdraws one conflicting constraint. The unchosen
     * candidate is retained in the derived bundle, never overwritten.
     */
    private static void resolveChoice(CaseState s, JSONObject p) {
        String action = p.optString("action", "CHOOSE_STRATEGY");
        if ("WITHDRAW_CONSTRAINT".equals(action)) {
            s.constraints.remove(p.getString("constraintId"));
            return;
        }
        String chosen = p.getString("chosenStrategy");
        Map<String, RawModels.Constraint> rewritten = new LinkedHashMap<>();
        for (RawModels.Constraint c : s.constraints.values()) {
            if ("ELIMINATION".equals(chosen) && "PENALTY".equals(c.strategy)) {
                rewritten.put(c.constraintId, new RawModels.Constraint(c.constraintId,
                        c.physicalDof, c.valueRaw, c.units, "ELIMINATION", null,
                        c.constraintVersion + "-resolved"));
            } else if ("PENALTY".equals(chosen) && "ELIMINATION".equals(c.strategy)) {
                String pen = p.optString("penaltyRaw", "1.0E12");
                rewritten.put(c.constraintId, new RawModels.Constraint(c.constraintId,
                        c.physicalDof, c.valueRaw, c.units, "PENALTY", pen,
                        c.constraintVersion + "-resolved"));
            } else {
                rewritten.put(c.constraintId, c);
            }
        }
        s.constraints.clear();
        s.constraints.putAll(rewritten);
    }
}
