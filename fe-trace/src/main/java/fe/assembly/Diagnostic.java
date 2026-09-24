package fe.assembly;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A structured, machine-readable finding produced while deriving an assembly.
 * Every edge case the archive replays is represented as one of these rather
 * than being silently resolved to a single answer.
 */
public final class Diagnostic {
    private final String code;
    private final Severity severity;
    private final String message;
    private final Map<String, String> refs = new LinkedHashMap<>();

    public Diagnostic(String code, Severity severity, String message) {
        this.code = code;
        this.severity = severity;
        this.message = message;
    }

    public Diagnostic ref(String key, String value) {
        refs.put(key, value);
        return this;
    }

    public String code() { return code; }
    public Severity severity() { return severity; }
    public String message() { return message; }
    public Map<String, String> refs() { return refs; }
}
