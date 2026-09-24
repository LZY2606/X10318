package fe.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * A physical scalar that keeps its original textual representation and unit on
 * import. Normalization to a canonical SI representation happens only in the
 * derived layer; the original token never leaves the source record.
 *
 * <p>If a value cannot be converted, {@link #normalized()} returns
 * {@code null} and {@link #convertible()} is false. The value stays in place
 * with a diagnostic instead of being dropped for plotting.
 */
public final class Quantity {
    private final String raw;
    private final String unit;
    private final Double normalized;
    private final String canonicalUnit;

    public Quantity(String raw, String unit, Double normalized, String canonicalUnit) {
        this.raw = raw;
        this.unit = unit;
        this.normalized = normalized;
        this.canonicalUnit = canonicalUnit;
    }

    public static Quantity raw(String raw, String unit) {
        return new Quantity(raw, unit, null, null);
    }

    public static Quantity ofSI(double value, String canonicalUnit, String raw, String unit) {
        return new Quantity(raw, unit, value, canonicalUnit);
    }

    public String raw() { return raw; }
    public String unit() { return unit; }
    public Double normalized() { return normalized; }
    public String canonicalUnit() { return canonicalUnit; }
    public boolean convertible() { return normalized != null; }

    /**
     * Best effort numeric value used by the derived layer. Prefers the
     * normalized SI value, otherwise parses the raw token; null when the
     * source cannot be interpreted numerically at all.
     */
    public Double numeric() {
        if (normalized != null) return normalized;
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return Objects.equals(raw, q.raw) && Objects.equals(unit, q.unit)
                && Objects.equals(normalized, q.normalized)
                && Objects.equals(canonicalUnit, q.canonicalUnit);
    }
    @Override public int hashCode() { return Objects.hash(raw, unit, normalized, canonicalUnit); }
    @Override public String toString() {
        if (unit == null || unit.isEmpty()) return raw;
        return raw + " " + unit;
    }

    public String formatNormalized() {
        if (normalized == null) return null;
        return String.format(Locale.ROOT, "%.12g", normalized)
                + (canonicalUnit == null ? "" : " " + canonicalUnit);
    }
}
