package org.gsb.fem.domain;

/** One row of the element-local to physical global degree of freedom map. */
public class DofMapping {
    public String elementId;
    public String localDof;
    public String globalDof;

    public DofMapping() {
    }

    public DofMapping(String elementId, String localDof, String globalDof) {
        this.elementId = elementId;
        this.localDof = localDof;
        this.globalDof = globalDof;
    }
}
