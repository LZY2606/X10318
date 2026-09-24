package org.gsb.fem.rules;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

public final class Jsons {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Jsons() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pretty(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical hash: keys sorted, whitespace free — stable across JVM runs. */
    public static String hash(Object o) {
        try {
            Object tree = MAPPER.convertValue(o, Object.class);
            Object canonical = canon(tree);
            byte[] bytes = MAPPER.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object canon(Object o) {
        if (o instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canon(e.getValue()));
            }
            return sorted;
        }
        if (o instanceof Iterable<?> it) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object item : it) {
                list.add(canon(item));
            }
            return list;
        }
        return o;
    }
}
