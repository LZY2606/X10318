package com.gsb.fea.core;

import java.util.List;
import java.util.Map;

/**
 * One legal assembly outcome. Conflicting boundary decisions yield several
 * candidates instead of one forced answer; an unchosen candidate stays stored.
 * prescribed holds dof->fixed value for ELIMINATION; penalty holds dof->weight.
 */
public record AssemblyCandidate(String candidateId, String label,
                                Map<String, Double> prescribed,
                                Map<String, Double> penalty,
                                List<SparseEntry> entries,
                                List<Diagnostic> diagnostics, String reason) {
    public AssemblyCandidate {
        prescribed = Map.copyOf(prescribed);
        penalty = Map.copyOf(penalty);
        entries = List.copyOf(entries);
        diagnostics = List.copyOf(diagnostics);
    }

    public AssemblyCandidate withReason(String r) {
        return new AssemblyCandidate(candidateId, label, prescribed, penalty, entries, diagnostics, r);
    }
}
