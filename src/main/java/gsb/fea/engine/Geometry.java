package gsb.fea.engine;

import gsb.fea.model.Node;
import gsb.fea.model.Quantity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Geometry checks used to recognize degenerate elements. */
public final class Geometry {

    private Geometry() {
    }

    /** Signed area for a 3-node triangle; null when coordinates cannot be used. */
    public static Double triangleArea(Node a, Node b, Node c) {
        Double ax = usable(a.x());
        Double ay = usable(a.y());
        Double bx = usable(b.x());
        Double by = usable(b.y());
        Double cx = usable(c.x());
        Double cy = usable(c.y());
        if (ax == null || ay == null || bx == null || by == null || cx == null || cy == null) {
            return null;
        }
        return 0.5 * ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
    }

    private static Double usable(Quantity q) {
        return (q != null && q.isUsable()) ? q.value() : null;
    }

    public static boolean hasRepeatedNode(List<String> nodeIds) {
        Set<String> seen = new HashSet<>();
        for (String id : nodeIds) {
            if (!seen.add(id)) {
                return true;
            }
        }
        return false;
    }
}
