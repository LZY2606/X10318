package gsb.fea.model;

import gsb.fea.util.RawDecimal;

import java.util.ArrayList;
import java.util.List;

/** A mesh node. Coordinates keep the original textual representation and coordinate frame. */
public final class Node {
    public String uid;
    public int originalNumber;
    public RawDecimal x;
    public RawDecimal y;
    public RawDecimal z;
    public String coordinateFrame = "GLOBAL-XYZ";
    public List<String> dofAxes = new ArrayList<>();

    public Node() {
    }

    public Node(String uid, int originalNumber, RawDecimal x, RawDecimal y, RawDecimal z) {
        this.uid = uid;
        this.originalNumber = originalNumber;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
