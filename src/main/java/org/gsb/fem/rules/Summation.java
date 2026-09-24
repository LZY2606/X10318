package org.gsb.fem.rules;

import org.gsb.fem.domain.Contribution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Repeated contributions are always summed in a deterministic, recorded order.
 * The pattern is part of the float mode of a run; EXACT_DECIMAL audits the raw
 * tokens with arbitrary precision instead of binary doubles.
 */
public final class Summation {
    public static final String DOCUMENT_ORDER = "DOCUMENT_ORDER";
    public static final String SORTED_STRICT = "SORTED_STRICT";
    public static final String LEFT_TO_RIGHT = "LEFT_TO_RIGHT";
    public static final String EXACT_DECIMAL = "EXACT_DECIMAL";

    private Summation() {
    }

    public static List<Contribution> order(List<Contribution> contribs, String mode) {
        List<Contribution> copy = new ArrayList<>(contribs);
        switch (mode == null ? DOCUMENT_ORDER : mode) {
            case SORTED_STRICT -> copy.sort(Comparator.comparing(Contribution::orderKey));
            case LEFT_TO_RIGHT -> copy.sort(Comparator.comparingInt(c -> c.orderIndex));
            default -> {
                // DOCUMENT_ORDER: keep import order as generated.
            }
        }
        return copy;
    }

    /** Binary double sum in the given deterministic order; null when not summable. */
    public static Double sum(List<Contribution> ordered, String mode) {
        if (EXACT_DECIMAL.equals(mode)) {
            return null;
        }
        double acc = 0.0;
        boolean any = false;
        for (Contribution c : ordered) {
            if (!c.adopted || c.normalizedValue == null) {
                continue;
            }
            acc += c.normalizedValue;
            any = true;
        }
        return any ? acc : null;
    }

    /** Exact decimal audit sum over raw tokens times conversion factor. */
    public static String sumExact(List<Contribution> ordered) {
        BigDecimal acc = BigDecimal.ZERO;
        boolean any = false;
        for (Contribution c : ordered) {
            if (!c.adopted || c.normalizedValue == null) {
                continue;
            }
            try {
                BigDecimal raw = new BigDecimal(c.rawText.trim());
                BigDecimal factor = BigDecimal.valueOf(c.factor);
                acc = acc.add(raw.multiply(factor));
                any = true;
            } catch (NumberFormatException e) {
                // unconvertible token stays out of the exact sum as well
            }
        }
        return any ? acc.stripTrailingZeros().toPlainString() : null;
    }
}
