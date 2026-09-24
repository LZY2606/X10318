package com.gsb.fea.model;

import java.util.Map;

/**
 * A dof reordering. It only changes display/storage indices; physical dof
 * identity and physical correspondence stay unchanged.
 */
public record Renumbering(String renumberingId, Map<String, Integer> dofToIndex,
                          String basis, String rationale) {
    public Renumbering { dofToIndex = Map.copyOf(dofToIndex); }
}
