package gsb.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Element local matrix: local dof ordering and values are retained as imported.
 * entries: list of [rowIndex, colIndex, rawValue, unit?].
 */
public final class LocalMatrix {
    public static final class Entry {
        public final int r, c;
        public final Quant q;
        public Entry(int r, int c, Quant q) { this.r = r; this.c = c; this.q = q; }
    }

    public final String elementRef;          // references ElementConnectivity.originalNumber
    public final List<String> localDofOrder; // local dof labels in local order
    public final List<Entry> entries;

    public LocalMatrix(String elementRef, List<String> localDofOrder, List<Entry> entries) {
        this.elementRef = elementRef;
        this.localDofOrder = List.copyOf(localDofOrder);
        this.entries = List.copyOf(entries);
    }

    public int n() { return localDofOrder.size(); }

    /** Entry lookup with the imported unit; missing entries are structural zeros. */
    public Quant get(int r, int c) {
        for (Entry e : entries) if (e.r == r && e.c == c) return e.q;
        return Quant.of(0, unit(entries));
    }

    private static String unit(List<Entry> es) {
        for (Entry e : es) if (e.q.usable()) return e.q.unit;
        return "1";
    }

    public List<Entry> usableEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.q.usable()) out.add(e);
        return out;
    }

    public Map<String, Object> entryMap(Entry e) {
        return Map.of("r", e.r, "c", e.c, "raw", e.q.raw, "value", e.q.value, "unit", e.q.unit);
    }
}
