package fealedger.derive;

import fealedger.domain.Sources.*;
import java.util.List;

/** Immutable bundle of exactly the source revisions an assembly derivation depends on. */
public record AssemblyInput(
        CaseHeader header,
        List<NodeRecord> nodes,
        List<ElementRecord> elements,
        List<LocalMatrixRecord> localMatrices,
        List<DofMapRecord> dofMaps,
        List<ConstraintRecord> constraints,
        String renumberVersion // null/empty means author-proposed indices
) {}
