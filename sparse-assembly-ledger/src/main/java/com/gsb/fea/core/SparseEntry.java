package com.gsb.fea.core;

import java.util.List;

/**
 * One non-zero of a global sparse matrix. Free assembly entries are keyed by
 * physical dof ids so renumbering changes index only, never identity.
 */
public record SparseEntry(String urn, String rowDof, String colDof, int rowIndex,
                          int colIndex, double value, List<Contribution> contributions) {
    public SparseEntry { contributions = List.copyOf(contributions); }
}
