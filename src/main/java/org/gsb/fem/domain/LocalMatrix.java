package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Element local matrix. {@code dofOrder} preserves the original local degree of
 * freedom order as imported; sparse cells are kept in import order.
 */
public class LocalMatrix {
    public String id;
    public List<String> dofOrder = new ArrayList<>();
    public List<LocalEntry> entries = new ArrayList<>();
    /** true/false when declared by the source; null means the engine must check. */
    public Boolean declaredSymmetric;

    public LocalMatrix() {
    }

    public LocalMatrix(String id, List<String> dofOrder, List<LocalEntry> entries) {
        this.id = id;
        this.dofOrder = dofOrder;
        this.entries = entries;
    }
}
