package gsb.fea.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON encode failed", e);
        }
    }

    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON encode failed", e);
        }
    }

    public static <T> T read(String text, Class<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON decode failed: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String text, TypeReference<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON decode failed: " + e.getMessage(), e);
        }
    }

    public static <T> T read(InputStream in, Class<T> type) {
        try {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON decode failed: " + e.getMessage(), e);
        }
    }

    public static <T> T convert(Object value, TypeReference<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static void writeFile(Path path, Object value) {
        try {
            Files.write(path, writeBytes(value));
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing " + path, e);
        }
    }

    public static <T> T readFile(Path path, Class<T> type) {
        try {
            return read(Files.readString(path), type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed reading " + path + ": " + e.getMessage(), e);
        }
    }
}
