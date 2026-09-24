package org.gsb.fem.domain;

/**
 * A domain quantity as imported: the original textual representation and its
 * unit / coordinate frame are never discarded. Normalization fills {@link #value}
 * in the base unit of the dimension; when conversion is impossible the raw token
 * stays in place and {@link #error} carries the diagnosis.
 */
public class RawValue {
    public String raw;
    public String unit = "";
    public String frame = "";
    public Double value;
    public String baseUnit = "";
    public String error;

    public RawValue() {
    }

    public RawValue(String raw, String unit) {
        this.raw = raw;
        this.unit = unit == null ? "" : unit;
    }

    public RawValue(String raw, String unit, String frame) {
        this(raw, unit);
        this.frame = frame == null ? "" : frame;
    }

    public boolean convertible() {
        return value != null && error == null;
    }
}
