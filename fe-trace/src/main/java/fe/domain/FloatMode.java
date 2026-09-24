package fe.domain;

/**
 * Declared floating point summation mode for accumulating repeated
 * contributions. The mode is part of the preserved baseline so that the same
 * source material is always summed in the same deterministic order.
 *
 * <p>{@code ASC_ELEMENT} sorts contributions deterministically by
 * (element id, local row, local col) before summing; {@code RAW_ORDER} keeps
 * the recorded import order. Switching modes must be a new derived version,
 * never a silent in-place change, and the difference is diagnosed.
 */
public enum FloatMode {
    RAW_ORDER("raw-order"),
    ASC_ELEMENT("asc-element");

    private final String code;

    FloatMode(String code) { this.code = code; }

    public String code() { return code; }

    public static FloatMode fromCode(String code) {
        for (FloatMode m : values()) {
            if (m.code.equalsIgnoreCase(code)) return m;
        }
        throw new IllegalArgumentException("unknown float mode: " + code);
    }
}
