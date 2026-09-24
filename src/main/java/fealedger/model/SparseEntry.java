package fealedger.model;

import java.util.ArrayList;
import java.util.List;

/** A single nonzero of the global matrix, with its complete source stack. */
public final class SparseEntry {
    public final String entryId;
    public final String globalRow;
    public final String globalCol;
    public final double value;
    public final String unit;
    public final List<EntryContribution> contributions = new ArrayList<>();

    public SparseEntry(String entryId, String globalRow, String globalCol, double value, String unit) {
        this.entryId = entryId;
        this.globalRow = globalRow;
        this.globalCol = globalCol;
        this.value = value;
        this.unit = unit;
    }
}
