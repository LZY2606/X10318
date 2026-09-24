package gsb.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Canonical JSON: sorted keys, no insignificant whitespace, stable across export/import. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    public static final ObjectMapper CANON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static String write(Object o) {
        try { return M.writeValueAsString(o); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static String canonical(Object o) {
        try { return CANON.writeValueAsString(o); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static JsonNode read(String s) {
        try { return M.readTree(s); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
