package gsb.fea.assembly;

import java.math.BigDecimal;
import java.util.List;

/** One atomic addition to a global sparse entry, with a complete provenance chain. */
public final class ContributionItem {
    public String uid;
    public String elementId;
    public int originalElementNumber;
    public String localMatrixId;
    public String matrixRevision;
    public int localRow;
    public int localCol;
    public String localRowDof;
    public String localColDof;
    public String globalRowDofId;
    public String globalColDofId;
    public int physicalRow;
    public int physicalCol;
    public String rawValue;
    public String unit;
    public String floatMode;
    public BigDecimal exactValue;
    public double doubleValue;
    /** Zero-based position of this addition in the deterministic summation order. */
    public int sumOrder;

    public List<String> sourceChain() {
        return List.of(
                elementId,
                localMatrixId,
                "LM-ENTRY(" + localRow + "," + localCol + ")",
                "MAP(" + localRowDof + "->" + globalRowDofId + ","
                        + localColDof + "->" + globalColDofId + ")",
                "CONTRIBUTION(" + physicalRow + "," + physicalCol + ")#" + sumOrder
        );
    }
}
