package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * One nonzero of the assembled matrix. Identity follows the physical dof pair
 * ({@link #id}) and survives renumbering; {@link #rowIndex}/{@link #colIndex} are
 * the post-renumber positions.
 */
public class GlobalEntry {
    public String id;
    public String rowDofId;
    public String colDofId;
    public int rowIndex;
    public int colIndex;

    public Double value;
    public String valueExact;
    public String summation;
    public String unit;
    public boolean asymmetricPair;

    public List<Contribution> contributions = new ArrayList<>();

    public boolean hasUnconvertible() {
        for (Contribution c : contributions) {
            if (c.normalizedValue == null) {
                return true;
            }
        }
        return false;
    }
}
