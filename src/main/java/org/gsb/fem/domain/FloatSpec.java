package org.gsb.fem.domain;

/**
 * Floating point summation pattern used when adding repeated contributions:
 * DOCUMENT_ORDER, SORTED_STRICT, LEFT_TO_RIGHT (assembly loop order), EXACT_DECIMAL.
 */
public class FloatSpec {
    public String id;
    public String summation;

    public FloatSpec() {
    }

    public FloatSpec(String id, String summation) {
        this.id = id;
        this.summation = summation;
    }
}
