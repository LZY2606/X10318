package gsb.fea.model;

/**
 * A scalar that keeps the original textual representation declared by the
 * engineering team. Normalization (unit conversion) only happens in the
 * derived layer; a value that cannot be converted stays at its original
 * position together with a diagnostic instead of being dropped for plotting.
 */
public record Quantity(String raw, Double value, String unit, String basis, String note) {

    public static Quantity of(String raw, String unit, String basis) {
        String text = raw == null ? "" : raw.trim();
        Double parsed = null;
        String problem = null;
        if (!text.isEmpty()) {
            try {
                parsed = Double.parseDouble(text);
            } catch (RuntimeException ex) {
                problem = "unparseable numeric literal: " + raw;
            }
        }
        return new Quantity(raw, parsed, unit == null ? "" : unit, basis == null ? "" : basis, problem);
    }

    public static Quantity parsed(double v, String unit, String basis) {
        return new Quantity(Double.toString(v), v, unit, basis, null);
    }

    public boolean isUsable() {
        return value != null && note == null;
    }

    public String display() {
        return raw + (unit == null || unit.isEmpty() ? "" : " " + unit);
    }
}
