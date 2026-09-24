package fealedger.model;

/**
 * A mesh node. Coordinates are kept in their original representation and unit;
 * the coordinate frame/datum (e.g. {@code global-cartesian-m}) is recorded so a
 * renumbering can never be confused with a physical change.
 */
public final class Node {
    public final String id;
    public final double x;
    public final double y;
    public final String coordinateBasis;
    public final String xRaw;
    public final String yRaw;

    public Node(String id, double x, double y, String coordinateBasis, String xRaw, String yRaw) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.coordinateBasis = coordinateBasis == null ? "global-cartesian" : coordinateBasis;
        this.xRaw = xRaw;
        this.yRaw = yRaw;
    }
}
