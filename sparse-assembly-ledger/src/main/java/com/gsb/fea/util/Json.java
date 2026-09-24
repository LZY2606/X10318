package com.gsb.fea.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Small Jackson facade so the rest of the code never touches mapper config. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json write failed", e);
        }
    }

    public static void write(Path p, Object o) {
        try {
            Files.writeString(p, write(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("json write failed: " + p, e);
        }
    }

    public static <T> T read(String s, Class<T> type) {
        try {
            return M.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException("json read failed", e);
        }
    }

    public static <T> T read(Path p, Class<T> type) {
        try {
            return read(Files.readString(p, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalStateException("json read failed: " + p, e);
        }
    }

    public static <T> T read(InputStream in, Class<T> type) {
        try {
            return M.readValue(in, type);
        } catch (Exception e) {
            throw new IllegalStateException("json read failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String s) {
        return read(s, Map.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(Path p) {
        return read(p, Map.class);
    }

    public static void copy(InputStream in, OutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
