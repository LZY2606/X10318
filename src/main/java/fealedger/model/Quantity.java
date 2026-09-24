package fealedger.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A scalar engineering quantity that always preserves its original textual
 * representation and unit. Normalisation to a base unit happens only in the
 * derivation layer; values that cannot be converted stay at their origin with
 * a diagnostic instead of being dropped.
 */
public final class Quantity {
    private final String raw;
    private final String unit;
    private final double value;
    private final boolean convertible;
    private final String diagnose;

    private Quantity(String raw, String unit, double value, boolean convertible, String diagnose) {
        this.raw = raw;
        this.unit = unit == null ? "" : unit;
        this.value = value;
        this.convertible = convertible;
        this.diagnose = diagnose;
    }

    /** Parse from the imported representation without losing it. */
    public static Quantity of(String raw, String unit) {
        String r = raw == null ? "" : raw.trim();
        double v = Double.NaN;
        boolean ok = false;
        String problem = null;
        try {
            v = Double.parseDouble(r);
            if (!Double.isFinite(v)) {
                problem = "non-finite numeric literal: " + r;
            } else {
                ok = true;
            }
        } catch (NumberFormatException e) {
            problem = "unparseable numeric literal: " + r;
        }
        if (ok && unit != null && !unit.isBlank()) {
            double factor = Units.factorToBase(unit.trim());
            if (!Double.isFinite(factor)) {
                return new Quantity(r, unit.trim(), v, false, "unknown or incompatible unit: " + unit.trim());
            }
        }
        return new Quantity(r, unit, v, ok, problem);
    }

    public static Quantity ofParsed(double value, String unit) {
        return new Quantity(String.format(Locale.ROOT, "%.17g", value), unit, value, true, null);
    }

    public String raw() { return raw; }
    public String unit() { return unit; }
    public double value() { return value; }
    public boolean convertible() { return convertible; }
    public String diagnose() { return diagnose; }

    /** Value normalised to the canonical base unit of the quantity's family. */
    public double baseValue() {
        if (!convertible) return Double.NaN;
        if (unit == null || unit.isBlank()) return value;
        return value * Units.factorToBase(unit);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Quantity q)) return false;
        return Objects.equals(raw, q.raw) && Objects.equals(unit, q.unit);
    }
    @Override public int hashCode() { return Objects.hash(raw, unit); }
    @Override public String toString() { return raw + (unit == null || unit.isEmpty() ? "" : " " + unit); }
}
