package gsb.fea.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Element connectivity. The original element number and the local degree-of-freedom
 * ordering are source data and are never reordered during assembly.
 */
public final class Element {
    public String uid;
    public int originalNumber;
    public String elementType;
    public List<String> nodeUids = new ArrayList<>();
    public List<String> localDofOrder = new ArrayList<>();
    public String note = "";

    public Element() {
    }

    public Element(String uid, int originalNumber, String elementType,
                   List<String> nodeUids, List<String> localDofOrder) {
        this.uid = uid;
        this.originalNumber = originalNumber;
        this.elementType = elementType;
        this.nodeUids = new ArrayList<>(nodeUids);
        this.localDofOrder = new ArrayList<>(localDofOrder);
    }
}
