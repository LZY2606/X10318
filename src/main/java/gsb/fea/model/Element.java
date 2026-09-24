package gsb.fea.model;

import java.util.List;

/**
 * One finite element as imported. {@code originalId} is the element number used
 * by the engineering team and must survive renumbering. {@code nodeIds} is the
 * element connectivity in imported order. Local degrees of freedom map to
 * physical dof keys via the dof-kind rule in {@link CaseInput} (e.g. one
 * scalar dof per node), kept as {@code localDofOrder} so the local ordering is
 * preserved verbatim.
 */
public record Element(String originalId, List<String> nodeIds, List<String> localDofOrder,
                      String elementType, String materialId) {
    public int nodeCount() {
        return nodeIds.size();
    }
}
