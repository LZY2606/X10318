package femledger.model;

/**
 * A single local-matrix term's contribution to one global entry. Every contribution remembers
 * its element id, local row/column DOF, the original local matrix value (token + unit) and the
 * stable contribution ordinal that fixes deterministic summation order.
 */
public final class Contribution {
    private final String id;
    private final String elementId;
    private final String localRowDof;
    private final String localColDof;
    private final double value;
    private final String rawToken;
    private final String unit;
    private final int order;

    public Contribution(String id, String elementId, String localRowDof, String localColDof,
                        double value, String rawToken, String unit, int order) {
        this.id = id;
        this.elementId = elementId;
        this.localRowDof = localRowDof;
        this.localColDof = localColDof;
        this.value = value;
        this.rawToken = rawToken;
        this.unit = unit;
        this.order = order;
    }

    public String id() { return id; }
    public String elementId() { return elementId; }
    public String localRowDof() { return localRowDof; }
    public String localColDof() { return localColDof; }
    public double value() { return value; }
    public String rawToken() { return rawToken; }
    public String unit() { return unit; }
    public int order() { return order; }
}
