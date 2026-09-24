package femledger.model;

import java.util.List;
import java.util.Objects;

/** An explicit, non-suppressed diagnostic attached to an assembly. */
public final class Diagnostic {
    public enum Severity { ERROR, WARNING, INFO }

    private final String id;
    private final String code;
    private final Severity severity;
    private final String message;
    private final List<String> objectIds;

    public Diagnostic(String id, String code, Severity severity, String message,
                      List<String> objectIds) {
        this.id = id;
        this.code = code;
        this.severity = severity;
        this.message = message;
        this.objectIds = List.copyOf(objectIds);
    }

    public String id() { return id; }
    public String code() { return code; }
    public Severity severity() { return severity; }
    public String message() { return message; }
    public List<String> objectIds() { return objectIds; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Diagnostic d)) return false;
        return Objects.equals(id, d.id) && Objects.equals(code, d.code)
                && severity == d.severity && Objects.equals(message, d.message)
                && Objects.equals(objectIds, d.objectIds);
    }
    @Override public int hashCode() { return Objects.hash(id, code, severity, message, objectIds); }
}
