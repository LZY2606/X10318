package fe.domain;

import java.util.Arrays;

/**
 * Dense local element matrix in declared local dof order. Each entry keeps the
 * imported {@link Quantity} (raw token + unit); {@link #value(int,int)} exposes
 * the numeric value used in the derived assembly layer.
 *
 * <p>Stored in the matrix entry unit, e.g. N/m for a stiffness block.
 */
public final class LocalMatrix {
    private final int n;
    private final Quantity[][] cells;
    private final String matrixUnit;

    public LocalMatrix(int n, Quantity[][] cells, String matrixUnit) {
        this.n = n;
        this.matrixUnit = matrixUnit;
        Quantity[][] copy = new Quantity[n][n];
        for (int i = 0; i < n; i++) {
            copy[i] = Arrays.copyOf(cells[i], n);
        }
        this.cells = copy;
    }

    public int size() { return n; }
    public String matrixUnit() { return matrixUnit; }
    public Quantity cell(int row, int col) { return cells[row][col]; }

    /** Numeric value, preferring normalized SI; null if not convertible. */
    public Double value(int row, int col) {
        Quantity q = cells[row][col];
        return q == null ? null : q.numeric();
    }

    public double valueOrZero(int row, int col) {
        Double v = value(row, col);
        return v == null ? 0.0 : v;
    }

    public boolean isSquareFor(int dofCount) { return n == dofCount; }
}
