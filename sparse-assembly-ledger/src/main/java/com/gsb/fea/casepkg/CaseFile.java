package com.gsb.fea.casepkg;

import java.util.List;
import java.util.Map;

/** The whole offline case package: domain bundles plus the ordered story. */
public record CaseFile(String caseId, String title, String summary,
                       Map<String, Object> units, List<Map<String, Object>> bundles,
                       List<CaseStep> steps, Map<String, Object> expected) {
    public CaseFile {
        steps = List.copyOf(steps);
        units = units == null ? Map.of() : Map.copyOf(units);
        bundles = bundles == null ? List.of() : List.copyOf(bundles);
        expected = expected == null ? Map.of() : Map.copyOf(expected);
    }
}
