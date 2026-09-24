package gsb.fea.model;

import java.util.List;

/**
 * Element local matrix with values kept as their original text. {@code dofOrder}
 * is the local degree-of-freedom order (labels) this matrix is indexed by; it
 * must match {@link Element#localDofOrder()}. Values may be asymmetric; that is
 * diagnosed rather than silently symmetrized.
 */
public record LocalMatrix(String elementOriginalId, List<String> dofOrder,
                          List<List<Quantity>> rows, String unit, String note) {
    public int size() {
        return rows.size();
    }
}
