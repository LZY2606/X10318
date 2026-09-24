package gsb.fea.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Deterministic content hashing used by the dependency graph and versions. */
public final class Hashes {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Hashes() {
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String contentHash(Object value) {
        try {
            return sha256(CANONICAL.writeValueAsString(value));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
