package gsb.fea.model;

import gsb.fea.util.RawDecimal;

import java.util.ArrayList;
import java.util.List;

/**
 * A revision of one element local matrix. Values retain raw representation, unit and
 * the floating-point mode declared by the source.
 */
public final class LocalMatrix {
    public String uid;
    public String elementId;
    public String revision;
    public int dimension;
    public String unit = "N/m";
    public String floatMode = "IEEE754-BINARY64";
    public boolean declaredSymmetric = false;
    public List<Entry> entries = new ArrayList<>();

    public LocalMatrix() {
    }

    public static final class Entry {
        public int localRow;
        public int localCol;
        public RawDecimal value;

        public Entry() {
        }

        public Entry(int localRow, int localCol, RawDecimal value) {
            this.localRow = localRow;
            this.localCol = localCol;
            this.value = value;
        }
    }

    public RawDecimal at(int row, int col) {
        for (Entry entry : entries) {
            if (entry.localRow == row && entry.localCol == col) {
                return entry.value;
            }
        }
        return null;
    }

    public List<Entry> denseEntries() {
        List<Entry> dense = new ArrayList<>();
        for (int r = 0; r < dimension; r++) {
            for (int c = 0; c < dimension; c++) {
                RawDecimal value = at(r, c);
                dense.add(new Entry(r, c, value == null ? new RawDecimal("0", unit) : value));
            }
        }
        return dense;
    }
}
