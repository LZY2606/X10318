package gsb.fea.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * A quantity imported from source text. The raw representation is always retained;
 * conversion to BigDecimal is attempted only in the derived layer.
 */
public final class RawDecimal {
    private final String raw;
    private final String unit;

    @JsonCreator
    public RawDecimal(@JsonProperty("raw") String raw, @JsonProperty("unit") String unit) {
        this.raw = raw == null ? "" : raw;
        this.unit = unit == null ? "" : unit;
    }

    public String raw() {
        return raw;
    }

    public String unit() {
        return unit;
    }

    public boolean convertible() {
        return parsed() != null;
    }

    public BigDecimal parsed() {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return new BigDecimal(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public BigDecimal require() {
        BigDecimal value = parsed();
        if (value == null) {
            throw new NumberFormatException("not a decimal: " + raw);
        }
        return value;
    }

    public double doubleValue() {
        BigDecimal value = parsed();
        return value == null ? Double.NaN : value.doubleValue();
    }

    public static BigDecimal toDoubleRoundTrip(BigDecimal value) {
        return new BigDecimal(value.doubleValue(), MathContext.DECIMAL64);
    }

    public static BigDecimal divideExact(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MathContext.DECIMAL64);
    }

    public static BigDecimal round64(BigDecimal value) {
        return value.setScale(17, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
