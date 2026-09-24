package gsb.fea.assembly;

/**
 * How repeated contributions to the same sparse entry are combined.
 * <ul>
 *   <li>{@link #EXACT_DECIMAL} accumulates BigDecimal values in canonical order;</li>
 *   <li>{@link #DOUBLE_CANONICAL} accumulates binary64 doubles in ascending contribution order;</li>
 *   <li>{@link #DOUBLE_REVERSED} accumulates binary64 doubles in descending contribution order.</li>
 * </ul>
 */
public enum SummationSpec {
    EXACT_DECIMAL("EXACT-DECIMAL"),
    DOUBLE_CANONICAL("IEEE754-BINARY64/CANONICAL-ORDER"),
    DOUBLE_REVERSED("IEEE754-BINARY64/REVERSED-ORDER");

    public final String modeLabel;

    SummationSpec(String modeLabel) {
        this.modeLabel = modeLabel;
    }
}
