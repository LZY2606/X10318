package com.gsb.fea.core;

import java.util.List;
import java.util.Map;

/** Machine-readable diagnosis attached to a derived object. */
public record Diagnostic(String code, Severity severity, String message,
                         List<String> objects, Map<String, String> details) {
    public enum Severity { INFO, WARNING, ERROR }

    public Diagnostic {
        objects = objects == null ? List.of() : List.copyOf(objects);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static Diagnostic of(String code, Severity s, String msg, List<String> objects) {
        return new Diagnostic(code, s, msg, objects, Map.of());
    }
}
