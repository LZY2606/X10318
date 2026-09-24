package org.fealedger.store;

import org.fealedger.model.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Canonical content hashing for dependency tracking and identity checks. */
public final class Hashing {
    private Hashing() {}

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash12(String s) { return sha256(s).substring(0, 12); }

    /** Stable hash of any JSON-able object via deterministic pretty printing. */
    public static String hashJson(Object o) { return hash12(Json.writePretty(o)); }
}
