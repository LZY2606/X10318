package fea.model;

import java.util.Map;

/** Unit conversion to canonical base (mm, N, N/mm). Unknown units raise
 *  IllegalArgumentException; callers keep the raw value in place and attach a diagnostic. */
public final class UnitConv {
    private static final Map<String, Double> LENGTH = Map.of(
            "mm", 1.0, "cm", 10.0, "m", 1000.0, "in", 25.4);
    private static final Map<String, Double> STIFF = Map.of(
            "N/mm", 1.0, "kN/m", 1.0, "N/m", 0.001, "kN/mm", 1000.0, "lbf/in", 0.175126835);

    private UnitConv() {}

    public static double toMm(String raw, String unit) {
        Double f = LENGTH.get(unit);
        if (f == null) throw new IllegalArgumentException("unknown length unit: " + unit);
        return Double.parseDouble(raw.trim()) * f;
    }

    public static double stiffnessToNperMm(String raw, String unit) {
        Double f = STIFF.get(unit);
        if (f == null) throw new IllegalArgumentException("unknown stiffness unit: " + unit);
        return Double.parseDouble(raw.trim()) * f;
    }

    public static boolean knownLength(String u) { return LENGTH.containsKey(u); }
    public static boolean knownStiffness(String u) { return STIFF.containsKey(u); }
}
