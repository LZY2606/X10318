package org.fealedger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic JSON parser/printer.
 * Numbers are parsed as Double, booleans as Boolean, null as null.
 * Object members preserve insertion order (LinkedHashMap) which is essential
 * for stable identity/hashing and for review output stability.
 */
public final class Json {
    private Json() {}

    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, Object> parseObject(String s) {
        return (LinkedHashMap<String, Object>) new Parser(s).parse();
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    /** Pretty writer with fixed 2-space indentation - stable byte-for-byte output. */
    public static String writePretty(Object v) {
        StringBuilder sb = new StringBuilder();
        writeIndented(sb, v, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeIndented(StringBuilder sb, Object v, int depth) {
        String pad = "  ".repeat(depth);
        String childPad = "  ".repeat(depth + 1);
        if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(childPad).append(quote(e.getKey().toString())).append(": ");
                writeIndented(sb, e.getValue(), depth + 1);
            }
            sb.append('\n').append(pad).append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(childPad);
                writeIndented(sb, item, depth + 1);
            }
            sb.append('\n').append(pad).append(']');
        } else {
            writeValue(sb, v);
        }
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b.booleanValue());
        else if (v instanceof Double d) sb.append(num(d));
        else if (v instanceof Integer i) sb.append(i.intValue());
        else if (v instanceof Long l) sb.append(l.longValue());
        else if (v instanceof Number n) sb.append(n.toString());
        else sb.append(quote(v.toString()));
    }

    /** Format doubles canonically (no trailing .0 noise), but integer-valued stay integer if originally int. */
    private static String num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s; this.i = 0; }

        Object parse() {
            skipWs();
            Object v = readValue();
            skipWs();
            if (i != s.length()) throw new IllegalArgumentException("trailing content at " + i);
            return v;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object readValue() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') { expect("null"); return null; }
            return readNumber();
        }

        private LinkedHashMap<String, Object> readObject() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            i++; skipWs();
            if (s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs(); consume(':');
                Object val = readValue();
                map.put(key, val);
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + i);
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            i++; skipWs();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + i);
            }
        }

        private String readString() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        default -> throw new IllegalArgumentException("bad escape at " + i);
                    }
                } else sb.append(c);
            }
        }

        private Boolean readBool() {
            if (s.charAt(i) == 't') { expect("true"); return Boolean.TRUE; }
            expect("false"); return Boolean.FALSE;
        }

        private Double readNumber() {
            int start = i;
            if (s.charAt(i) == '-' || s.charAt(i) == '+') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            return Double.valueOf(s.substring(start, i));
        }

        private void consume(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        private void expect(String word) {
            if (!s.regionMatches(i, word, 0, word.length()))
                throw new IllegalArgumentException("expected " + word + " at " + i);
            i += word.length();
        }
    }
}
