package gsb.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable identifiers and content hashes used everywhere in the ledger. */
public final class H {
    private H() {}

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Short, stable, human-recognisable content fingerprint. */
    public static String fp(String s) {
        return sha256(s).substring(0, 16);
    }

    /** Exact binary identity of a double, so summation-order differences are visible. */
    public static String bits(double v) {
        return Long.toHexString(Double.doubleToRawLongBits(v));
    }
}
