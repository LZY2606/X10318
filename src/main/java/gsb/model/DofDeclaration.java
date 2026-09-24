package gsb.model;

/**
 * A global degree of freedom with its coordinate basis.  Identity is the stable string
 * {@code node.dof} (or an explicit global id), never an array index: renumbering changes the
 * index but never this identity.
 */
public final class DofDeclaration {
    public final String id;       // stable physical identity, e.g. "n3.uy"
    public final String node;
    public final String component; // ux, uy, rot ...
    public final String basis;     // coordinate basis, e.g. "global:xy", "local:n4->n5"

    public DofDeclaration(String id, String node, String component, String basis) {
        this.id = id;
        this.node = node;
        this.component = component;
        this.basis = basis == null ? "global" : basis;
    }
}
