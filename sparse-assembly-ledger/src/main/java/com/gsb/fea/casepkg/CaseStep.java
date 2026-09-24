package com.gsb.fea.casepkg;

import java.util.List;
import java.util.Map;

/**
 * One scripted step of the offline engineering case.
 *
 * kinds:
 *   IMPORT       - load a bundle revision into the ledger (params: bundleId)
 *   PARAM_REVISE - change a single parameter (penalty value etc.)
 *   SOURCE_PATCH - change one local-only source datum (one local matrix token)
 *   PROBE        - run a boundary scenario over a bundle without changing sources
 *   DECIDE       - a human decision that may conflict with an earlier one
 *   REPLAY_END   - closes the narrative
 */
public record CaseStep(String id, String kind, String title, String narrative,
                       Map<String, Object> params, List<String> expectDiagnostics,
                       Map<String, Object> expectState) {
    public CaseStep {
        expectDiagnostics = expectDiagnostics == null ? List.of() : List.copyOf(expectDiagnostics);
        expectState = expectState == null ? Map.of() : Map.copyOf(expectState);
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
