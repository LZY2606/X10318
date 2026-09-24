package com.gsb.fea.casepkg;

import com.gsb.fea.model.Constraint;
import com.gsb.fea.model.DofMap;
import com.gsb.fea.model.Element;
import com.gsb.fea.model.Node;
import com.gsb.fea.model.RawValue;
import com.gsb.fea.model.Renumbering;
import com.gsb.fea.model.SourceBundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Constructs immutable domain bundles from case-file maps, never normalizing
 *  raw tokens away (they travel inside RawValue). */
final class Bundles {
    private Bundles() {}

    @SuppressWarnings("unchecked")
    static SourceBundle fromMap(String bundleId, Map<String, Object> m) {
        List<Node> nodes = new ArrayList<>();
        for (Object no : (List<Object>) m.getOrDefault("nodes", List.of())) {
            nodes.add(node((Map<String, Object>) no));
        }
        List<Element> elements = new ArrayList<>();
        for (Object eo : (List<Object>) m.getOrDefault("elements", List.of())) {
            elements.add(element((Map<String, Object>) eo));
        }
        List<DofMap> maps = new ArrayList<>();
        for (Object mo : (List<Object>) m.getOrDefault("maps", List.of())) {
            maps.add(dofMap((Map<String, Object>) mo));
        }
        List<Constraint> cons = new ArrayList<>();
        for (Object co : (List<Object>) m.getOrDefault("constraints", List.of())) {
            cons.add(constraint((Map<String, Object>) co));
        }
        Renumbering rn = null;
        if (m.containsKey("renumbering") && m.get("renumbering") != null) {
            rn = renumbering((Map<String, Object>) m.get("renumbering"));
        }
        return new SourceBundle(bundleId,
                (String) m.getOrDefault("label", bundleId), nodes, elements, maps, cons, rn);
    }

    @SuppressWarnings("unchecked")
    private static Node node(Map<String, Object> m) {
        List<RawValue> coords = new ArrayList<>();
        for (Object co : (List<Object>) m.getOrDefault("coords", List.of())) {
            Map<String, Object> c = (Map<String, Object>) co;
            coords.add(RawValue.of((String) c.get("text"),
                    (String) c.get("unit"), (String) c.get("datum")));
        }
        return new Node((String) m.get("nodeId"), (String) m.get("originalNumber"),
                coords, strs((List<Object>) m.getOrDefault("dofIds", List.of())));
    }

    @SuppressWarnings("unchecked")
    private static Element element(Map<String, Object> m) {
        List<List<RawValue>> mat = new ArrayList<>();
        for (Object ro : (List<Object>) m.getOrDefault("matrix", List.of())) {
            List<RawValue> row = new ArrayList<>();
            for (Object co : (List<Object>) ro) {
                Map<String, Object> c = (Map<String, Object>) co;
                row.add(RawValue.of((String) c.get("text"),
                        (String) c.getOrDefault("unit", m.get("matrixUnit")),
                        (String) c.get("datum")));
            }
            mat.add(row);
        }
        return new Element((String) m.get("elementId"),
                (String) m.getOrDefault("originalNumber", (String) m.get("elementId")),
                strs((List<Object>) m.getOrDefault("nodeIds", List.of())),
                strs((List<Object>) m.getOrDefault("localDofs", List.of())),
                mat, (String) m.get("matrixUnit"),
                (String) m.getOrDefault("floatMode", "ieee754-binary64/input-order"),
                (String) m.getOrDefault("sourceFragment", ""));
    }

    @SuppressWarnings("unchecked")
    private static DofMap dofMap(Map<String, Object> m) {
        return new DofMap((String) m.get("elementId"),
                strs((List<Object>) m.get("mapping")),
                (String) m.getOrDefault("mapVersion", "v0"));
    }

    @SuppressWarnings("unchecked")
    private static Constraint constraint(Map<String, Object> m) {
        Map<String, Object> v = (Map<String, Object>) m.get("value");
        RawValue rv = v == null ? null
                : RawValue.of((String) v.get("text"),
                        (String) v.get("unit"), (String) v.get("datum"));
        return new Constraint((String) m.get("constraintId"), (String) m.get("dofId"),
                (String) m.get("kind"), rv,
                (String) m.getOrDefault("version", "bc-v0"),
                (String) m.getOrDefault("rationale", ""));
    }

    @SuppressWarnings("unchecked")
    private static Renumbering renumbering(Map<String, Object> m) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        Map<String, Object> raw = (Map<String, Object>) m.get("dofToIndex");
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            idx.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        return new Renumbering((String) m.getOrDefault("renumberingId", "rn"),
                idx, (String) m.getOrDefault("basis", "global dof order"),
                (String) m.getOrDefault("rationale", ""));
    }

    private static List<String> strs(List<Object> in) {
        List<String> out = new ArrayList<>();
        for (Object o : in) out.add((String) o);
        return out;
    }
}
