package fealedger.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import fealedger.model.Enums.ConstraintKind;
import java.util.List;

/** Immutable source-material records. Every numeric field keeps its raw textual representation. */
public final class Sources {
    private Sources() {}

    /** Header of an imported case: units, coordinate basis and float mode are preserved verbatim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseHeader(
            String caseId,
            String title,
            String coordinateBasis,   // e.g. "CARTESIAN_XY", retained raw; derivation normalizes
            String lengthUnit,        // raw unit token, e.g. "mm"
            String forceUnit,         // raw unit token, e.g. "N"
            String floatMode,         // raw token -> Enums.FloatMode
            String constraintStrategy // raw token -> Enums.ConstraintStrategy
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeRecord(
            String nodeId,
            List<String> coordsRaw, // original tokens, e.g. ["0", "2.5"]
            String unit            // coordinate unit token as written
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ElementRecord(
            String elementId,       // original element number preserved as string
            String elementType,
            List<String> nodeIds,   // connectivity in local order
            int localDofPerNode
    ) {}

    /**
     * Dense local element matrix. Values preserve the exact token text from
     * import so decimal representation (e.g. "0.1" vs "0.10") survives round trip.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocalMatrixRecord(
            String elementId,
            List<String> localDofOrder, // labels in local DOF order
            List<List<String>> valuesRaw,
            String unitStiffness        // raw stiffness unit token as written
    ) {}

    /** Logical DOF identity: (node, local dof label). Renumbering changes index, never identity. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DofMapRecord(
            String elementId,
            String nodeId,
            String localDof,
            String globalDofId,   // stable logical id "dof@node" (may equal index initially)
            int proposedIndex     // author-proposed storage index; may collide
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConstraintRecord(
            String constraintId,
            String constraintVersion,
            ConstraintKind kind,
            String dofA,
            String dofB,          // null for PRESCRIBED
            String valueRaw,      // raw prescribed token (null for TIED)
            String unit,          // raw displacement/force unit token
            String penaltyAlphaRaw // raw penalty alpha token, meaningful only for PENALTY
    ) {}
}
