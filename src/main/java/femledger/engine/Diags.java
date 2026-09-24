package femledger.engine;

import femledger.model.Diagnostic;

import java.util.List;

/** Factory for the fixed vocabulary of assembly diagnostics. */
final class Diags {
    static final String DEGENERATE_ELEMENT = "DEGENERATE_ELEMENT";
    static final String DUPLICATE_GLOBAL_DOF = "DUPLICATE_GLOBAL_DOF";
    static final String MAPPING_GAP = "MAPPING_GAP";
    static final String ISOLATED_DOF = "ISOLATED_DOF";
    static final String CONSTRAINT_CONFLICT = "CONSTRAINT_CONFLICT";
    static final String MIXED_STRATEGY = "MIXED_STRATEGY";
    static final String ASYMMETRIC_LOCAL_MATRIX = "ASYMMETRIC_LOCAL_MATRIX";
    static final String UNCONVERTIBLE_VALUE = "UNCONVERTIBLE_VALUE";
    static final String UNIT_MISMATCH = "UNIT_MISMATCH";
    static final String CONSTRAINED_DOF_MISSING = "CONSTRAINED_DOF_MISSING";
    static final String PENALTY_NOT_SYMMETRIZED = "PENALTY_NOT_SYMMETRIZED";

    private Diags() {}

    static Diagnostic make(String code, Diagnostic.Severity severity, String message,
                           List<String> objects) {
        String id = "dg-" + Fingerprints.of(List.of(code, message, objects)).substring(0, 10);
        return new Diagnostic(id, code, severity, message, objects);
    }
}
