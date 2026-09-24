package fealedger.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A diagnostic attached to a derived result. Diagnostics never disappear just
 * because a picture must be drawn: an unconverted value or an ambiguous state
 * stays visible with a stable severity and machine-readable code.
 */
public final class Diagnostic {
    public enum Severity { INFO, WARNING, ERROR }

    public final String code;
    public final Severity severity;
    public final String message;
    public final Map<String, String> refs = new LinkedHashMap<>();

    public Diagnostic(String code, Severity severity, String message) {
        this.code = code;
        this.severity = severity;
        this.message = message;
    }

    public Diagnostic ref(String key, String value) {
        refs.put(key, value);
        return this;
    }

    @Override public String toString() {
        return "[" + severity + " " + code + "] " + message + " " + refs;
    }
}
