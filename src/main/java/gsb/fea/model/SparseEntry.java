package gsb.fea.model;

import java.util.List;

/**
 * One nonzero of the assembled global matrix, with the deterministic, ordered
 * stack of the contributions that produced it.
 */
public record SparseEntry(int rowIndex, int colIndex, String rowKey, String colKey,
                         double value, String rawExpression,
                         List<String> contributionIds,
                         double altOrderValue, double orderDelta) {
    public static String entryId(String rowKey, String colKey) {
        return "entry:" + rowKey + "::" + colKey;
    }
}
