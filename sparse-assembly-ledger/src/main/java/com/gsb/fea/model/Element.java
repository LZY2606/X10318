package com.gsb.fea.model;

import java.util.List;

/**
 * Original element as imported. The local matrix is the source's local
 * stiffness matrix: matrix.get(a).get(b) carries the raw token as printed in
 * the input, together with the summation/float pattern it was produced with.
 * localDofs preserves the original local degree-of-freedom ordering.
 */
public record Element(String elementId, String originalNumber, List<String> nodeIds,
                      List<String> localDofs, List<List<RawValue>> matrix,
                      String matrixUnit, String floatMode, String sourceFragment) {
    public Element {
        nodeIds = List.copyOf(nodeIds);
        localDofs = List.copyOf(localDofs);
        matrix = matrix == null ? List.of() : matrix.stream().map(List::copyOf).toList();
    }

    public int size() { return localDofs.size(); }
}
