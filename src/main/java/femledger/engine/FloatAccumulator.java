package femledger.engine;

import java.util.Comparator;
import java.util.List;

/**
 * Floating point summation with an explicitly retained, replayable mode.
 *
 * SEQUENTIAL_SORTED sums in the deterministic contribution order (element ordinal then local
 * row/column). SEQUENTIAL_SOURCE sums in raw source ingestion order. Both are strictly
 * sequential (no parallel / tree reduction), so the round-off is reproducible bit-for-bit.
 */
public final class FloatAccumulator {
    public static final String SORTED = "SEQUENTIAL_SORTED";
    public static final String SOURCE = "SEQUENTIAL_SOURCE";

    private FloatAccumulator() {}

    public record Term(int sortedOrder, int sourceOrder, double value) {}

    public static double sum(List<Term> terms, String mode) {
        Comparator<Term> order = SOURCE.equals(mode)
                ? Comparator.comparingInt(Term::sourceOrder)
                : Comparator.comparingInt(Term::sortedOrder);
        double acc = 0.0;
        for (Term term : terms.stream().sorted(order).toList()) {
            acc += term.value();
        }
        return acc;
    }
}
