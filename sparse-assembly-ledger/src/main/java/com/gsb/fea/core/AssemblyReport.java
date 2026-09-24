package com.gsb.fea.core;

import java.util.List;

/** Full derived result of assembling one source bundle one way. */
public record AssemblyReport(AssembleOptions.Strategy strategy,
                            AssembleOptions.FloatOrder floatOrder,
                            List<String> dofOrder, List<AssemblyCandidate> candidates,
                            List<Diagnostic> diagnostics,
                            List<FloatSensitivity> sensitivity, boolean rejected) {
    public AssemblyReport {
        dofOrder = List.copyOf(dofOrder);
        candidates = List.copyOf(candidates);
        diagnostics = List.copyOf(diagnostics);
        sensitivity = List.copyOf(sensitivity);
    }

    public boolean indeterminate() { return candidates.size() > 1; }
}
