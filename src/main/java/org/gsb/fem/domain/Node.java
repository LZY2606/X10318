package org.gsb.fem.domain;

/** Mesh node. Coordinates keep raw text, unit and the coordinate basis. */
public class Node {
    public String id;
    public String frame = "CARTESIAN#PLATE-Z0";
    public RawValue x;
    public RawValue y;
    public RawValue z;

    public Node() {
    }

    public Node(String id, String frame, RawValue x, RawValue y, RawValue z) {
        this.id = id;
        this.frame = frame == null ? this.frame : frame;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
