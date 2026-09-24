package fealedger.derive;

/**
 * One single local-matrix term and its provenance.
 * The contribution id is deterministic: same element/local slot/value/mapping => same id.
 */
public record Contribution(
        String contributionId,
        String elementId,        // original element number
        String rowLocalDof,      // local DOF label in the preserved local order
        String colLocalDof,
        String rowGlobalDof,     // logical global DOF identity
        String colGlobalDof,
        int rowIndex,
        int colIndex,
        String rawValue,         // original token text
        String unit              // stiffness unit token
) {}
