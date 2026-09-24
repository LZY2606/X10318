package gsb.fea.assembly;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** One assembled non-zero (or structurally present) entry of the global matrix. */
public final class MatrixEntry {
    public String uid;
    public int physicalRow;
    public int physicalCol;
    public int displayedRow;
    public int displayedCol;
    public String globalRowDofId;
    public String globalColDofId;
    public BigDecimal exactValue;
    public double doubleValue;
    public String summationMode;
    public boolean numeric;
    public List<String> contributionUids = new ArrayList<>();
}
