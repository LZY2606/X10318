package gsb.fea.model;

/**
 * Arithmetic and summation policy. Summation order is explicit so that
 * repeatable contributions add in one deterministic order. A second order can
 * be used by the floating-point-order counterexample; the order itself is a
 * direct source and therefore versioned.
 */
public record FloatMode(String arithmetic, String summationOrder, long displayDigits) {

    public static final String DOUBLE = "DOUBLE";
    public static final String TEXT_DECIMAL = "TEXT_DECIMAL";

    /** element original id ascending, then local (row,col) row-major */
    public static final String ORDER_ELEM_ASC_IJ = "ELEMENT_ORIGINAL_ID:ASC;LOCAL:ROW_MAJOR";
    /** element original id descending, then local (col,row) column-major */
    public static final String ORDER_ELEM_DESC_JI = "ELEMENT_ORIGINAL_ID:DESC;LOCAL:COL_MAJOR";

    public static FloatMode baseline() {
        return new FloatMode(DOUBLE, ORDER_ELEM_ASC_IJ, 9);
    }
}
