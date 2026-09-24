package fealedger.model;

/**
 * A single boundary constraint in a constraint version.
 *
 * <p>Types:
 * <ul>
 *   <li>{@code fixed}: dof is prescribed (value 0 unless set).</li>
 *   <li>{@code prescribed}: dof equals {@code value}.</li>
 *   <li>{@code tie}: dof {@code dofId} moves with {@code partnerId} (MPC-style).</li>
 * </ul>
 *
 * <p>A constraint version is homogeneous in strategy: either elimination
 * ({@code elimination}) or penalty ({@code penalty}, with {@code penaltyStiffness}).
 * Mixing the two strategies inside one version is an explicit error.
 */
public final class BoundaryConstraint {
    public final String id;
    public final String type;
    public final String dofId;
    public final String partnerId;
    public final Quantity value;

    public BoundaryConstraint(String id, String type, String dofId, String partnerId, Quantity value) {
        this.id = id;
        this.type = type;
        this.dofId = dofId;
        this.partnerId = partnerId;
        this.value = value;
    }
}
