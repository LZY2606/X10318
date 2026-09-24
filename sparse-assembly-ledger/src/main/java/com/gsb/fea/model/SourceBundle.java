package com.gsb.fea.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One imported revision of raw source material. Everything here is source
 * layer: original element numbering, local dof order, local matrix values with
 * tokens/unit/float mode, global mappings and constraint versions.
 */
public record SourceBundle(String bundleId, String label, List<Node> nodes,
                           List<Element> elements, List<DofMap> maps,
                           List<Constraint> constraints,
                           Renumbering renumbering) {
    public SourceBundle {
        nodes = List.copyOf(nodes);
        elements = List.copyOf(elements);
        maps = List.copyOf(maps);
        constraints = List.copyOf(constraints);
    }

    public Element element(String id) {
        for (Element e : elements) if (e.elementId().equals(id)) return e;
        return null;
    }

    public List<String> allDofIds() {
        List<String> ids = new ArrayList<>();
        for (Node n : nodes) for (String d : n.dofIds()) if (!ids.contains(d)) ids.add(d);
        return List.copyOf(ids);
    }
}
