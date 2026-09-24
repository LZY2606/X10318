package gsb.fea.model;

/**
 * A mesh node. Coordinates keep the original text plus unit and coordinate
 * basis. Every physical quantity carries a unit or coordinate basis.
 */
public record Node(String id, Quantity x, Quantity y, Quantity z) {
    public String physicalKey() {
        return "node:" + id;
    }
}
