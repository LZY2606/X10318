package gsb.fea.model;

/**
 * A single prescribed boundary condition. {@code version} is the boundary
 * constraint version tag imported with the data. A physical dof fixed to two
 * different values in the same version is a constraint conflict and is kept,
 * not reconciled silently.
 */
public record ConstraintDef(String id, String globalDofKey, Quantity value, String kind, String version) {
    public static String KIND_DIRICHLET = "DIRICHLET";
}
