package fe.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable engineering model as imported: mesh nodes, element
 * connectivity + local matrices + dof mapping, and boundary constraints.
 *
 * <p>Everything that the review checklist asks to preserve is here: original
 * element ids, local dof order, local matrix values (raw tokens + units),
 * global mapping (on {@link LocalDoF}), constraint versions, units and the
 * declared floating point mode.
 */
public final class Model {
    private final String coordinateFrame;
    private final String lengthUnit;
    private final String stiffnessUnit;
    private final FloatMode floatMode;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final List<Constraint> constraints = new ArrayList<>();

    public Model(String coordinateFrame, String lengthUnit, String stiffnessUnit, FloatMode floatMode) {
        this.coordinateFrame = coordinateFrame;
        this.lengthUnit = lengthUnit;
        this.stiffnessUnit = stiffnessUnit;
        this.floatMode = floatMode;
    }

    public String coordinateFrame() { return coordinateFrame; }
    public String lengthUnit() { return lengthUnit; }
    public String stiffnessUnit() { return stiffnessUnit; }
    public FloatMode floatMode() { return floatMode; }

    public void addNode(Node n) { nodes.put(n.nodeId(), n); }
    public void addElement(Element e) { elements.put(e.elementId(), e); }
    public void addConstraint(Constraint c) { constraints.add(c); }

    public Map<String, Node> nodes() { return nodes; }
    public Map<String, Element> elements() { return elements; }
    public List<Constraint> constraints() { return constraints; }

    public int globalDimension() {
        int max = -1;
        for (Element e : elements.values()) {
            for (LocalDoF d : e.dofs()) max = Math.max(max, d.globalIndex());
        }
        return max + 1;
    }

    /** Physical dof -> global index as declared by the mapping. */
    public Map<String, Integer> physicalToGlobal() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Element e : elements.values()) {
            for (LocalDoF d : e.dofs()) {
                map.putIfAbsent(d.physicalDoF(), d.globalIndex());
            }
        }
        return map;
    }
}
