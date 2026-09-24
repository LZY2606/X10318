package com.gsb.fea.model;

/**
 * A measurement value that keeps its original textual representation.
 * Normalization to double happens only in the derived layer; an unparseable
 * value stays in place with parseOk=false instead of being silently dropped.
 */
public record RawValue(String text, Double numeric, boolean parseOk, String parseError,
                       String unit, String datum) {
    public static RawValue of(String text, String unit, String datum) {
        String t = text == null ? "" : text.trim();
        try {
            double d = Double.parseDouble(t);
            return new RawValue(t, d, true, null, unit, datum);
        } catch (RuntimeException e) {
            return new RawValue(t, null, false, e.getClass().getSimpleName() + ": " + e.getMessage(), unit, datum);
        }
    }

    public static RawValue number(double d, String unit, String datum) {
        return new RawValue(Double.toString(d), d, true, null, unit, datum);
    }

    public double requireNumeric() {
        if (!parseOk || numeric == null) {
            throw new IllegalStateException("value not convertible: " + text + " (" + parseError + ")");
        }
        return numeric;
    }
}
