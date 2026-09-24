package com.gsb.fea.core;

/**
 * Deterministic assembly parameters.
 *  CANONICAL : contributions summed in element import order, then local (row, col)
 *  GROUPED   : contributions summed grouped by source element then reordered
 *              within each global entry by (element ordinal, local col, row):
 *              a different but still deterministic float summation order, used
 *              to surface floating-point ordering sensitivity.
 */
public record AssembleOptions(Strategy strategy, FloatOrder floatOrder) {
    public enum Strategy { FREE, ELIMINATION, PENALTY }
    public enum FloatOrder { CANONICAL, GROUPED }

    public static AssembleOptions free() {
        return new AssembleOptions(Strategy.FREE, FloatOrder.CANONICAL);
    }
}
