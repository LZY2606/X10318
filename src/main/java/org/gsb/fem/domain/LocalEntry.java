package org.gsb.fem.domain;

/** One nonzero local matrix cell; the value carries its original unit. */
public class LocalEntry {
    public int row;
    public int col;
    public RawValue value;

    public LocalEntry() {
    }

    public LocalEntry(int row, int col, RawValue value) {
        this.row = row;
        this.col = col;
        this.value = value;
    }
}
