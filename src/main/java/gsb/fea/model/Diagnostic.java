package gsb.fea.model;

import java.util.List;

/**
 * A structured finding. Diagnostics never silently disappear and never force a
 * single answer: when a situation is ambiguous the engine keeps the legal
 * candidates instead. {@code severity} is INFO/WARN/ERROR/BLOCK.
 */
public record Diagnostic(String code, String severity, String message,
                         List<String> affectedObjects, List<String> candidateIds) {

    public static final String REPEATED_DOF = "REPEATED_DOF";
    public static final String DEGENERATE_ELEMENT = "DEGENERATE_ELEMENT";
    public static final String ISOLATED_DOF = "ISOLATED_DOF";
    public static final String CONSTRAINT_CONFLICT = "CONSTRAINT_CONFLICT";
    public static final String FLOAT_ORDER_DELTA = "FLOAT_ORDER_DELTA";
    public static final String ASYMMETRIC_LOCAL = "ASYMMETRIC_LOCAL";
    public static final String UNCONVERTIBLE_VALUE = "UNCONVERTIBLE_VALUE";
    public static final String MISSING_LOCAL_MATRIX = "MISSING_LOCAL_MATRIX";
    public static final String STRATEGY_MIXING = "STRATEGY_MIXING";
}
