package fealedger.model;

import java.util.Map;

/**
 * Unit registry for the offline case pack. Only linear, explicit factors are
 * supported — anything else is "not convertible" and must stay in place with
 * a diagnostic rather than silently losing meaning.
 */
public final class Units {
    private Units() {}

    public static final String BASE_FORCE = "N";
    public static final String BASE_LENGTH = "m";
    public static final String BASE_STIFFNESS = "N/m";
    public static final String BASE_COORD = "m";

    private static final Map<String, Double> FACTORS = Map.ofEntries(
            Map.entry("m", 1.0),
            Map.entry("mm", 0.001),
            Map.entry("cm", 0.01),
            Map.entry("N", 1.0),
            Map.entry("kN", 1000.0),
            Map.entry("MN", 1.0e6),
            Map.entry("N/m", 1.0),
            Map.entry("N/mm", 1000.0),
            Map.entry("kN/m", 1000.0),
            Map.entry("kN/mm", 1.0e6));

    public static double factorToBase(String unit) {
        Double f = FACTORS.get(unit);
        return f == null ? Double.NaN : f;
    }

    public static boolean known(String unit) {
        return unit == null || unit.isBlank() || FACTORS.containsKey(unit.trim());
    }
}
