package fealedger.derive;

import java.util.List;

/**
 * A diagnosed anomaly. severity:
 *  ERROR   - assembly cannot be uniquely/legally completed (e.g. constraint conflict)
 *  WARNING - result produced but flagged (degenerate element, asymmetry, isolated dof)
 */
public record Diagnostic(
        String code,
        String severity,
        String message,
        List<String> subjectIds // elements / dofs / constraints involved
) {
    public static Diagnostic error(String code, String message, List<String> subjects) {
        return new Diagnostic(code, "ERROR", message, subjects);
    }

    public static Diagnostic warn(String code, String message, List<String> subjects) {
        return new Diagnostic(code, "WARNING", message, subjects);
    }
}
