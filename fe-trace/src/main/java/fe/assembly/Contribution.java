package fe.assembly;

/**
 * A single term added to one global non-zero entry, fully traced back to its
 * source: element id, the local dof slots and their physical identities, the
 * local (row,col) cell and its numeric value.
 *
 * <p>{@code order} is the deterministic position at which this contribution is
 * summed within its target entry, given the model's {@code FloatMode}.
 */
public final class Contribution {
    public final String contributionId;
    public final int order;
    public final String elementId;
    public final int localRowSlot;
    public final int localColSlot;
    public final String rowPhysical;
    public final String colPhysical;
    public final int globalRow;
    public final int globalCol;
    public final double value;
    public final String rawToken;
    public final String unit;

    public Contribution(String contributionId, int order, String elementId,
                        int localRowSlot, int localColSlot,
                        String rowPhysical, String colPhysical,
                        int globalRow, int globalCol, double value,
                        String rawToken, String unit) {
        this.contributionId = contributionId;
        this.order = order;
        this.elementId = elementId;
        this.localRowSlot = localRowSlot;
        this.localColSlot = localColSlot;
        this.rowPhysical = rowPhysical;
        this.colPhysical = colPhysical;
        this.globalRow = globalRow;
        this.globalCol = globalCol;
        this.value = value;
        this.rawToken = rawToken;
        this.unit = unit;
    }
}
