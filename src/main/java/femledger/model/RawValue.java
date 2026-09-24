package femledger.model;

import java.util.Objects;

/**
 * A quantity that keeps its original textual representation, unit and (for coordinates) datum.
 * The raw token is preserved verbatim on import; the derived layer is the only place that may
 * normalise it to {@code numeric}. If a token cannot be parsed it stays in place and
 * {@link #numeric} remains {@code null} together with a conversion diagnostic.
 */
public final class RawValue {
    private final String id;
    private final String raw;
    private final String unit;
    private final String datum;
    private final Double numeric;

    public RawValue(String id, String raw, String unit, String datum, Double numeric) {
        this.id = id;
        this.raw = raw;
        this.unit = unit;
        this.datum = datum;
        this.numeric = numeric;
    }

    public String id() { return id; }
    public String raw() { return raw; }
    public String unit() { return unit; }
    public String datum() { return datum; }
    public Double numeric() { return numeric; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawValue that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(raw, that.raw)
                && Objects.equals(unit, that.unit) && Objects.equals(datum, that.datum)
                && Objects.equals(numeric, that.numeric);
    }
    @Override public int hashCode() { return Objects.hash(id, raw, unit, datum, numeric); }
}
