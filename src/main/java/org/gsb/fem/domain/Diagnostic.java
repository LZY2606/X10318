package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.List;

/** Machine-readable diagnosis attached to a stable subject object. */
public class Diagnostic {
    public String code;
    public String severity;
    public String subjectId;
    public String subjectKind;
    public String message;
    public List<String> relatedIds = new ArrayList<>();

    public Diagnostic() {
    }

    public Diagnostic(String code, String severity, String subjectKind, String subjectId, String message) {
        this.code = code;
        this.severity = severity;
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.message = message;
    }

    public Diagnostic related(String id) {
        relatedIds.add(id);
        return this;
    }
}
