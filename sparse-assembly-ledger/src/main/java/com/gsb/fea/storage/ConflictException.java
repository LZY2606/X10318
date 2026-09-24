package com.gsb.fea.storage;

/**
 * Raised when two users edit from the same base revision and the second save
 * finds the base no longer current. Carries both revisions so the caller can
 * present a resolvable merge/choice instead of losing either edit.
 */
public class ConflictException extends RuntimeException {
    private final String expectedBase;
    private final String actualCurrent;

    public ConflictException(String expectedBase, String actualCurrent) {
        super("optimistic lock conflict: base=" + expectedBase + " current=" + actualCurrent);
        this.expectedBase = expectedBase;
        this.actualCurrent = actualCurrent;
    }

    public String expectedBase() { return expectedBase; }
    public String actualCurrent() { return actualCurrent; }
}
