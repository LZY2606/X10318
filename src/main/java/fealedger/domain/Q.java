package fealedger.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/** Immutable exact rational number used by the RATIONAL_ONCE float mode. */
public final class Q extends Number implements Comparable<Q> {
    public static final Q ZERO = new Q(BigInteger.ZERO, BigInteger.ONE);
    public static final Q ONE = new Q(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den; // always positive

    private Q(BigInteger num, BigInteger den) {
        if (den.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.gcd(den);
        if (g.signum() != 0) {
            num = num.divide(g);
            den = den.divide(g);
        }
        this.num = num;
        this.den = den;
    }

    public static Q of(long n, long d) {
        return new Q(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    public static Q of(BigInteger n, BigInteger d) {
        return new Q(n, d);
    }

    /** Parses decimal or integer token text, e.g. "0.1", "-3", "1.5e2". */
    public static Q parse(String token) {
        String s = token.trim();
        if (s.isEmpty()) {
            throw new NumberFormatException("empty rational");
        }
        boolean neg = s.startsWith("-");
        if (neg || s.startsWith("+")) {
            s = s.substring(1);
        }
        int ePos = Math.max(s.indexOf('e'), s.indexOf('E'));
        int exp = 0;
        if (ePos >= 0) {
            exp = Integer.parseInt(s.substring(ePos + 1));
            s = s.substring(0, ePos);
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length > 2) {
            throw new NumberFormatException("bad rational: " + token);
        }
        String intPart = parts[0];
        String fracPart = parts.length == 2 ? parts[1] : "";
        exp -= fracPart.length();
        String digits = intPart + fracPart;
        if (digits.isEmpty()) {
            digits = "0";
        }
        BigInteger n = new BigInteger(digits);
        if (exp >= 0) {
            n = n.multiply(BigInteger.TEN.pow(exp));
            return neg ? new Q(n.negate(), BigInteger.ONE) : new Q(n, BigInteger.ONE);
        }
        BigInteger d = BigInteger.TEN.pow(-exp);
        return neg ? new Q(n.negate(), d) : new Q(n, d);
    }

    public static Q valueOf(double v) {
        return parse(Double.toString(v));
    }

    public Q add(Q o) {
        return new Q(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Q subtract(Q o) {
        return new Q(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public Q multiply(Q o) {
        return new Q(num.multiply(o.num), den.multiply(o.den));
    }

    public Q negate() {
        return new Q(num.negate(), den);
    }

    public int signum() {
        return num.signum();
    }

    public double toDouble() {
        return new BigDecimal(num)
                .divide(new BigDecimal(den), MathContext.DECIMAL64)
                .doubleValue();
    }

    public BigInteger numerator() {
        return num;
    }

    public BigInteger denominator() {
        return den;
    }

    @Override
    public int compareTo(Q o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    @Override
    public int intValue() {
        return (int) longValue();
    }

    @Override
    public long longValue() {
        return num.divide(den).longValue();
    }

    @Override
    public float floatValue() {
        return (float) toDouble();
    }

    @Override
    public double doubleValue() {
        return toDouble();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Q q)) {
            return false;
        }
        return compareTo(q) == 0;
    }

    @Override
    public int hashCode() {
        return num.multiply(BigInteger.valueOf(31)).hashCode() ^ den.hashCode();
    }

    @Override
    public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
