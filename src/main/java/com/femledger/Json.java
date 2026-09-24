package com.femledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Shared Jackson helpers. Canonical JSON (sorted map keys) is used for hashing. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String canonical(Object value) {
        try { return CANONICAL.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static JsonNode read(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Stable content hash for any JSON-serializable object. */
    public static String hashOf(Object value) {
        return sha256(canonical(value));
    }
}
