package fe.assembly;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Global sparse matrix. Entries are keyed by (row,col) so the sparsity pattern
 * is exact; each entry keeps the provenance stack of every term that built it.
 */
public final class SparseMatrix {
    public final int dimension;
    public final String unit;
    private final Map<Long, SparseEntry> entries = new TreeMap<>();

    public SparseMatrix(int dimension, String unit) {
        this.dimension = dimension;
        this.unit = unit;
    }

    static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    public SparseEntry entry(int row, int col) {
        return entries.get(key(row, col));
    }

    public SparseEntry getOrCreate(int row, int col) {
        return entries.computeIfAbsent(key(row, col), k -> new SparseEntry(row, col));
    }

    public Set<Map.Entry<Long, SparseEntry>> entrySet() {
        return entries.entrySet();
    }

    public int nonZeroCount() { return entries.size(); }

    public double get(int row, int col) {
        SparseEntry e = entry(row, col);
        return e == null ? 0.0 : e.value;
    }

    public void finalizeSums() {
        for (SparseEntry e : entries.values()) {
            e.contributions.sort((a, b) -> Integer.compare(a.order, b.order));
            e.sumInOrder();
        }
    }

    /** Row-keyed copy of numeric values, dense within dimension (0 = absent). */
    public double[][] toDense() {
        double[][] m = new double[dimension][dimension];
        for (SparseEntry e : entries.values()) m[e.row][e.col] = e.value;
        return m;
    }
}
