package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/** Element connectivity in original node order; {@code importSeq} records file order. */
public class Element {
    public String id;
    public int importSeq;
    public List<String> nodeIds = new ArrayList<>();
    public String matrixId;
    public String structuralType = "tri3";

    public Element() {
    }

    public Element(String id, int importSeq, List<String> nodeIds, String matrixId, String structuralType) {
        this.id = id;
        this.importSeq = importSeq;
        this.nodeIds = nodeIds;
        this.matrixId = matrixId;
        this.structuralType = structuralType;
    }
}
