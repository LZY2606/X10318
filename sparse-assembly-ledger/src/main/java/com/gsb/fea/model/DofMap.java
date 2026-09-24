package com.gsb.fea.model;

import java.util.List;

/**
 * Local-to-global dof mapping for one element. mapping.get(a) is the global
 * physical dof id targeted by local dof localDofs.get(a). Repeated entries are
 * legal on input and are diagnosed by the assembler, never collapsed silently.
 */
public record DofMap(String elementId, List<String> mapping, String mapVersion) {
    public DofMap { mapping = List.copyOf(mapping); }
}
