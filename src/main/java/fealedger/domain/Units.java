package fealedger.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit / coordinate-basis normalization used only by the derivation layer.
 * Source records always retain their raw tokens; an unconvertible value stays
 * in place and gets a diagnostic instead of being dropped.
 */
public final class Units {
    private Units() {}

    public record UnitConversion(double factor, String canonical) {}

    private static final Map<String, UnitConversion> LENGTH = new LinkedHashMap<>();
    private static final Map<String, UnitConversion> FORCE = new LinkedHashMap<>();
    private static final Map<String, UnitConversion> DISP = new LinkedHashMap<>();
    private static final Map<String, UnitConversion> STIFF = new LinkedHashMap<>();
    private static final Set<String> BASIS = Set.of("CARTESIAN_XY", "CARTESIAN2D", "CARTESIAN_XYZ");

    static {
        LENGTH.put("m", new UnitConversion(1.0, "m"));
        LENGTH.put("cm", new UnitConversion(0.01, "m"));
        LENGTH.put("mm", new UnitConversion(0.001, "m"));
        FORCE.put("N", new UnitConversion(1.0, "N"));
        FORCE.put("kN", new UnitConversion(1000.0, "N"));
        DISP.put("m", new UnitConversion(1.0, "m"));
        DISP.put("mm", new UnitConversion(0.001, "m"));
        STIFF.put("N/m", new UnitConversion(1.0, "N/m"));
        STIFF.put("N/mm", new UnitConversion(1000.0, "N/m"));
        STIFF.put("kN/mm", new UnitConversion(1_000_000.0, "N/m"));
    }

    public static UnitConversion length(String raw) {
        return LENGTH.get(raw);
    }

    public static UnitConversion force(String raw) {
        return FORCE.get(raw);
    }

    public static UnitConversion displacement(String raw) {
        return DISP.get(raw);
    }

    public static UnitConversion stiffness(String raw) {
        return STIFF.get(raw);
    }

    /** Returns null diagnostic string when convertible, otherwise the reason it is not. */
    public static String diagnoseLength(String raw) {
        return LENGTH.containsKey(raw) ? null : "UNCONVERTIBLE_LENGTH_UNIT:" + raw;
    }

    public static String diagnoseStiffness(String raw) {
        return STIFF.containsKey(raw) ? null : "UNCONVERTIBLE_STIFFNESS_UNIT:" + raw;
    }

    public static String diagnoseBasis(String raw) {
        return BASIS.contains(raw) ? null : "UNKNOWN_COORDINATE_BASIS:" + raw;
    }
}
