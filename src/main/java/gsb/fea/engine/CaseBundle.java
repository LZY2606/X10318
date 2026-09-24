package gsb.fea.engine;

import gsb.fea.model.CaseInput;

/**
 * A source snapshot plus its content hash. The dependency graph compares these
 * hashes to decide whether a previously derived result is still valid or must
 * be recomputed.
 */
public record CaseBundle(CaseInput input, String sourceHash) {
    public static CaseBundle of(CaseInput input) {
        return new CaseBundle(input, Hashes.contentHash(input));
    }
}
