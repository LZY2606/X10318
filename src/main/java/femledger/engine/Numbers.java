package femledger.engine;

import femledger.model.RawValue;

/** Numeric normalisation of raw tokens; the raw token is always retained by the caller. */
final class Numbers {
    private Numbers() {}

    /** Parses the stored numeric if present, otherwise parses the raw token. Null on failure. */
    static Double numeric(RawValue rv) {
        if (rv.numeric() != null) return rv.numeric();
        if (rv.raw() == null) return null;
        String token = rv.raw().trim().replace("_", "");
        if (token.isEmpty()) return null;
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
