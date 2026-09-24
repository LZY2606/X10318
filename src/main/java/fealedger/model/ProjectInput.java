package fealedger.model;

import java.util.ArrayList;
import java.util.List;

/** Immutable snapshot of everything imported from the engineering team. */
public final class ProjectInput {
    public String projectId = "proj-1";
    public String title = "";
    public final List<Node> nodes = new ArrayList<>();
    public final List<ElementInput> elements = new ArrayList<>();
    public ConstraintSet constraints;
    /** global dof ordering used by the free (unconstrained) system. */
    public final List<String> globalDofOrder = new ArrayList<>();
    /** optional renumbering table: canonical dof id -> displayed/new dof id. */
    public final java.util.Map<String, String> renumberMap = new java.util.LinkedHashMap<>();
    /** summation policy for coincident contributions: "canonical-order" or "reverse-order". */
    public String summationOrder = "canonical-order";

    public ProjectInput copy() {
        ProjectInput c = new ProjectInput();
        c.projectId = projectId;
        c.title = title;
        c.nodes.addAll(nodes);
        c.elements.addAll(elements);
        c.constraints = constraints;
        c.globalDofOrder.addAll(globalDofOrder);
        c.renumberMap.putAll(renumberMap);
        c.summationOrder = summationOrder;
        return c;
    }
}
