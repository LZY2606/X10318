package fe.assembly;

import java.util.ArrayList;
import java.util.List;

/**
 * One global non-zero entry with its full stack of single contributions.
 * The entry value is the deterministic sum of the contributions in recorded
 * {@code order}.
 */
public final class SparseEntry {
    public final int row;
    public final int col;
    public final List<Contribution> contributions = new ArrayList<>();
    public double value;

    public SparseEntry(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public String entryId() {
        return "K[" + row + "][" + col + "]";
    }

    void sumInOrder() {
        double s = 0.0;
        for (Contribution c : contributions) s += c.value;
        this.value = s;
    }
}
