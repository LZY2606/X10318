package org.gsb.fem.rules;

import org.gsb.fem.domain.RawValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal unit registry. Import keeps raw text; only the derived layer converts
 * into the base unit of a dimension. Unknown or dimensionally mismatched units
 * stay in place with a diagnosis — values are never dropped to make a picture.
 */
public final class UnitRegistry {

    public record Unit(String dimension, String base, double factorToBase) {
    }

    private static final Map<String, Unit> UNITS = new HashMap<>();

    static {
        reg("m", "LENGTH", "m", 1.0);
        reg("mm", "LENGTH", "m", 1.0e-3);
        reg("cm", "LENGTH", "m", 1.0e-2);
        reg("in", "LENGTH", "m", 0.0254);
        reg("N", "FORCE", "N", 1.0);
        reg("kN", "FORCE", "N", 1.0e3);
        reg("lbf", "FORCE", "N", 4.4482216152605);
        reg("N/m", "STIFFNESS", "N/m", 1.0);
        reg("N/mm", "STIFFNESS", "N/m", 1.0e3);
        reg("kN/m", "STIFFNESS", "N/m", 1.0e3);
        reg("kN/mm", "STIFFNESS", "N/m", 1.0e6);
        reg("lbf/in", "STIFFNESS", "N/m", 175.12683524647645);
        reg("Pa", "PRESSURE", "Pa", 1.0);
        reg("MPa", "PRESSURE", "Pa", 1.0e6);
    }

    private static void reg(String symbol, String dim, String base, double factor) {
        UNITS.put(symbol, new Unit(dim, base, factor));
    }

    public static boolean known(String unit) {
        return unit != null && UNITS.containsKey(unit.trim());
    }

    public static Unit of(String unit) {
        return unit == null ? null : UNITS.get(unit.trim());
    }

    public static String dimensionOf(String unit) {
        Unit u = of(unit);
        return u == null ? null : u.dimension();
    }

    /**
     * Normalize into the base unit of the value's own dimension. On failure the
     * raw token is preserved and {@code error} explains why.
     */
    public static void normalize(RawValue rv) {
        if (rv == null) {
            return;
        }
        String raw = rv.raw == null ? "" : rv.raw.trim();
        if (raw.isEmpty()) {
            rv.error = "EMPTY_VALUE";
            return;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            rv.error = "UNPARSEABLE_NUMBER";
            return;
        }
        String unit = rv.unit == null ? "" : rv.unit.trim();
        if (unit.isEmpty()) {
            rv.value = parsed;
            rv.baseUnit = "";
            return;
        }
        Unit u = of(unit);
        if (u == null) {
            rv.error = "UNKNOWN_UNIT:" + unit;
            return;
        }
        rv.value = parsed * u.factorToBase();
        rv.baseUnit = u.base();
    }

    /** Convert a normalized base value of {@code fromBase} into {@code toBase} (same dimension). */
    public static Double convertBase(double baseValue, String fromBase, String toBase) {
        if (fromBase == null || fromBase.isEmpty() || fromBase.equals(toBase)) {
            return baseValue;
        }
        Unit from = null;
        Unit to = null;
        for (Unit u : UNITS.values()) {
            if (u.base().equals(fromBase)) {
                from = u;
            }
            if (u.base().equals(toBase)) {
                to = u;
            }
        }
        if (from == null || to == null || !from.dimension().equals(to.dimension())) {
            return null;
        }
        return baseValue / to.factorToBase();
    }
}
