package femledger.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable content fingerprints used by the dependency graph. */
final class Fingerprints {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Fingerprints() {}

    static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stable 12-hex-char fingerprint prefix for an object. */
    static String of(Object value) {
        return sha256(json(value)).substring(0, 12);
    }
}
