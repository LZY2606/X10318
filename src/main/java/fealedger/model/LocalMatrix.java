package fealedger.model;

import java.util.Arrays;

/**
 * An element local matrix. {@code labels} is the local degree-of-freedom order
 * exactly as imported (the "local dof order" provenance) and {@code values} is
 * the matching dense matrix, entries kept in their original units.
 */
public final class LocalMatrix {
    public final String[] labels;
    public final Quantity[][] values;

    public LocalMatrix(String[] labels, Quantity[][] values) {
        this.labels = labels;
        this.values = values;
    }

    public int size() { return labels.length; }

    @Override public String toString() {
        return "LocalMatrix" + Arrays.toString(labels);
    }
}
