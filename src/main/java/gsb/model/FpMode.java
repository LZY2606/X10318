package gsb.model;

import java.util.List;

/**
 * Floating point accumulation policy.  Binary64 addition is not associative,
 * so the summation order is a first-class source attribute rather than an
 * implementation accident.
 */
public final class FpMode {
    public enum Order {
        /** Element original number ascending, local row/col ascending — the ledger default. */
        FORWARD,
        /** Reverse deterministic order; exposes non-associativity in diagnostics. */
        REVERSE
    }

    public final Order order;
    /** Rounding mode label, preserved verbatim from import ("binary64:rne"). */
    public final String rounding;

    public FpMode(Order order, String rounding) {
        this.order = order == null ? Order.FORWARD : order;
        this.rounding = rounding == null || rounding.isBlank() ? "binary64:rne" : rounding;
    }

    public static FpMode parse(String text) {
        if (text == null) return new FpMode(Order.FORWARD, "binary64:rne");
        String[] parts = text.split("\\|");
        Order o = parts[0].trim().equalsIgnoreCase("reverse") ? Order.REVERSE : Order.FORWARD;
        String r = parts.length > 1 ? parts[1].trim() : "binary64:rne";
        return new FpMode(o, r);
    }

    /** Deterministic fold; the same list + mode always yields the same double. */
    public double sum(List<Double> xs) {
        double acc = 0d;
        if (order == Order.REVERSE) {
            for (int i = xs.size() - 1; i >= 0; i--) acc += xs.get(i);
        } else {
            for (double x : xs) acc += x;
        }
        return acc;
    }

    public String code() {
        return (order == Order.REVERSE ? "reverse" : "forward") + "|" + rounding;
    }

    @Override public String toString() { return code(); }
}
