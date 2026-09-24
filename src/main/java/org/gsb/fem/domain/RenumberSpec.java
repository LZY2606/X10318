package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Renumbering permutation: physical dof identities never move, only their index
 * positions, given by the ordered physical id list.
 */
public class RenumberSpec {
    public String id;
    public List<String> orderedDofIds = new ArrayList<>();

    public RenumberSpec() {
    }
}
