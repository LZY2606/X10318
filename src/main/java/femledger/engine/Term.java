package femledger.engine;

import femledger.model.Contribution;

/** Internal accumulator term carrying both deterministic orderings. */
final class Term {
    final Contribution contribution;
    final int elementOrdinal;
    final int localRowIndex;
    final int localColIndex;
    final int sourceOrder;

    Term(Contribution contribution, int elementOrdinal, int localRowIndex,
         int localColIndex, int sourceOrder) {
        this.contribution = contribution;
        this.elementOrdinal = elementOrdinal;
        this.localRowIndex = localRowIndex;
        this.localColIndex = localColIndex;
        this.sourceOrder = sourceOrder;
    }

    int sortedOrder() {
        return ((elementOrdinal & 0xffff) << 20)
                | ((localRowIndex & 0x3ff) << 10)
                | (localColIndex & 0x3ff);
    }
}
