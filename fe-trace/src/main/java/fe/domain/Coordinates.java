package fe.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A point with an explicit coordinate frame (basis). The original textual
 * coordinate tokens are retained verbatim; normalized numeric coordinates are
 * derived. Unconvertible coordinates keep their token and are flagged.
 */
public final class Coordinates {
    private final String frame;
    private final List<Quantity> components;

    public Coordinates(String frame, List<Quantity> components) {
        this.frame = frame;
        this.components = List.copyOf(components);
    }

    public static Coordinates of(String frame, double x, double y, double z, String lengthUnit) {
        return new Coordinates(frame, Arrays.asList(
                Quantity.ofSI(x, lengthUnit, fmt(x), lengthUnit),
                Quantity.ofSI(y, lengthUnit, fmt(y), lengthUnit),
                Quantity.ofSI(z, lengthUnit, fmt(z), lengthUnit)));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }

    public String frame() { return frame; }
    public List<Quantity> components() { return components; }

    /** Numeric coordinates in the stored normalized frame, or null. */
    public double[] numericOrNull() {
        double[] out = new double[components.size()];
        for (int i = 0; i < out.length; i++) {
            Double n = components.get(i).numeric();
            if (n == null) return null;
            out[i] = n;
        }
        return out;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Coordinates c)) return false;
        return Objects.equals(frame, c.frame) && Objects.equals(components, c.components);
    }
    @Override public int hashCode() { return Objects.hash(frame, components); }
}
