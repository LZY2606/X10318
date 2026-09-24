package com.femledger.domain;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of one assembly input version. Raw representations
 * (original element ids, local dof order, local matrix values, units,
 * coordinate basis, float mode) are preserved exactly as imported; any
 * normalization happens only in the derived assembly layer.
 */
public record Model(
        String floatMode,          // e.g. IEEE754_DOUBLE
        String summation,          // CANONICAL_ORDER
        String strategy,           // ELIMINATE or PENALTY (never mixed)
        List<NodeRec> nodes,
        List<ElementRec> elements,
        Map<String, String> dofMap,      // "E1.n1.ux" -> "g0"
        Map<String, Integer> numbering,  // global dof id -> matrix index (renumbering touches only this)
        ConstraintSet constraints
) {
    public record NodeRec(String id, double x, double y, String unit, String basis) {}

    public record ElementRec(
            String origId,
            String type,
            List<String> conn,
            List<String> localDofs,
            List<List<Double>> matrix,
            String unit) {}

    public record ConstraintSet(String version, String strategy, List<ConstraintItem> items) {}

    public record ConstraintItem(String dof, double value, String unit) {}
}
