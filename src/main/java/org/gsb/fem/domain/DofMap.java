package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/** Local-to-global degree of freedom mapping (one revision, stable ids). */
public class DofMap {
    public String id;
    public List<DofMapping> mappings = new ArrayList<>();

    public DofMap() {
    }

    public DofMap(String id, List<DofMapping> mappings) {
        this.id = id;
        this.mappings = mappings;
    }
}
