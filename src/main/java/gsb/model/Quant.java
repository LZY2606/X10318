package gsb.model;

import java.util.Set;

/**
 * A domain quantity: the raw textual representation is retained verbatim ("import 时保留原始表示");
 * the parsed double only exists on the derivation layer.  Values that cannot be converted stay in
 * place carrying a diagnostic instead of being dropped.
 */
public final class Quant {
    private static final Set<String> KNOWN = Set.of(
            "N/m", "N", "m", "mm", "Pa", "N*m/rad", "1", "unitless", "kN/m");

    public final double value;
    public final String unit;
    public final String raw;
    public final boolean convertible;
    public final String diagnostic;

    private Quant(double value, String unit, String raw, boolean convertible, String diagnostic) {
        this.value = value;
        this.unit = unit;
        this.raw = raw;
        this.convertible = convertible;
        this.diagnostic = diagnostic;
    }

    public static Quant of(Object rawValue, String unit) {
        String raw = String.valueOf(rawValue);
        String u = unit == null || unit.isBlank() ? "1" : unit.trim();
        try {
            double v = Double.parseDouble(raw.trim());
            String diag = KNOWN.contains(u) ? null
                    : "UNKNOWN_UNIT:" + u + " (kept in place, no normalisation)";
            return new Quant(v, u, raw, true, diag);
        } catch (NumberFormatException e) {
            return new Quant(Double.NaN, u, raw, false,
                    "UNCONVERTIBLE_VALUE:" + raw + " (left in place, not dropped)");
        }
    }

    public boolean usable() { return convertible && !Double.isNaN(value); }

    @Override public String toString() {
        return raw + " " + unit + (usable() ? "" : " [!]");
    }
}
