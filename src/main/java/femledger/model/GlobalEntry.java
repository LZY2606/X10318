package femledger.model;

import java.util.List;

/** One non-zero global matrix entry with its full per-term contribution stack. */
public final class GlobalEntry {
    private final String id;
    private final String rowLabel;
    private final String colLabel;
    private final int rowIndex;
    private final int colIndex;
    private final double value;
    private final List<Contribution> contributions;

    public GlobalEntry(String id, String rowLabel, String colLabel, int rowIndex, int colIndex,
                       double value, List<Contribution> contributions) {
        this.id = id;
        this.rowLabel = rowLabel;
        this.colLabel = colLabel;
        this.rowIndex = rowIndex;
        this.colIndex = colIndex;
        this.value = value;
        this.contributions = List.copyOf(contributions);
    }

    public String id() { return id; }
    public String rowLabel() { return rowLabel; }
    public String colLabel() { return colLabel; }
    public int rowIndex() { return rowIndex; }
    public int colIndex() { return colIndex; }
    public double value() { return value; }
    public List<Contribution> contributions() { return contributions; }
}
