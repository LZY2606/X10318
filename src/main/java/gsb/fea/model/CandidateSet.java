package gsb.fea.model;

import java.util.List;

/**
 * When more than one legal answer exists (e.g. elimination vs. penalty, or a
 * genuinely undecidable state) the engine returns all legal candidates instead
 * of collapsing to one. A later human decision adopts at most one; the others
 * are retained as unselected candidates.
 */
public record CandidateSet(String inputHash, List<AssemblyResult> candidates,
                           List<Diagnostic> diagnostics, boolean ambiguous) {
}
