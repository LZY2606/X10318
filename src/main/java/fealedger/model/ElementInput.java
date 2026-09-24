package fealedger.model;

/**
 * Raw element connectivity + local matrix + global dof mapping, as delivered by
 * the computational engineering team. Nothing here is rewritten on import:
 * original element number, local dof order, matrix values (with units) and the
 * global mapping are preserved verbatim for provenance.
 */
public final class ElementInput {
    public final String elementId;
    public final String materialId;
    public final String[] nodeIds;
    public final LocalMatrix localMatrix;
    /** globalDofs[i] is the global dof targeted by local dof {@code i}; null = unmapped. */
    public final String[] globalDofs;
    public final String matrixUnit;
    public final String floatMode;

    public ElementInput(String elementId, String materialId, String[] nodeIds,
                        LocalMatrix localMatrix, String[] globalDofs,
                        String matrixUnit, String floatMode) {
        this.elementId = elementId;
        this.materialId = materialId;
        this.nodeIds = nodeIds;
        this.localMatrix = localMatrix;
        this.globalDofs = globalDofs;
        this.matrixUnit = matrixUnit;
        this.floatMode = floatMode == null ? "ieee754-binary64" : floatMode;
    }
}
