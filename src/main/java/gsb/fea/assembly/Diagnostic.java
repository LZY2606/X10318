package gsb.fea.assembly;

import java.util.ArrayList;
import java.util.List;

public final class Diagnostic {
    public final String code;
    public final Severity severity;
    public final String message;
    public final List<String> objects = new ArrayList<>();

    public Diagnostic(String code, Severity severity, String message) {
        this.code = code;
        this.severity = severity;
        this.message = message;
    }

    public Diagnostic object(String id) {
        this.objects.add(id);
        return this;
    }

    public enum Severity {
        INFO,
        WARNING,
        BLOCKED
    }
}
