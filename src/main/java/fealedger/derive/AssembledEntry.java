package fealedger.derive;

import java.util.List;

/**
 * One nonzero of the global matrix and the ordered stack of single-term
 * contributions that produced it. Both accumulation results are recorded so
 * the UI can show float-order sensitivity explicitly.
 */
public record AssembledEntry(
        String entryId,       // K[rowGlobalDof,colGlobalDof] keyed on logical identity
        int rowIndex,
        int colIndex,
        String rowGlobalDof,
        String colGlobalDof,
        List<String> contributionIds, // deterministic order
        double sumF64LeftToRight,
        double sumRationalOnce,
        String sumRationalText,
        double floatOrderDelta // |f64 - rational|, highlights summation-order sensitivity
) {}
