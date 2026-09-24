package com.gsb.fea.model;

import java.util.List;

/** Mesh node. Coordinates keep raw text + coordinate datum; unknowns are the
 *  physical dof ids living at the node (e.g. ux, uy). */
public record Node(String nodeId, String originalNumber, List<RawValue> coords,
                   List<String> dofIds) {
    public Node {
        coords = List.copyOf(coords);
        dofIds = List.copyOf(dofIds);
    }
}
