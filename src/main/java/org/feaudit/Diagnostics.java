package org.feaudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Diagnostics {
    private Diagnostics() {}

    static Map<String, Object> diagnostic(String code, String severity, String status, String message, List<String> affected, Map<String, Object> evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", code + ":" + String.join("|", affected));
        map.put("code", code);
        map.put("severity", severity);
        map.put("status", status);
        map.put("message", message);
        map.put("affectedObjects", affected);
        map.put("evidence", evidence == null ? Map.of() : evidence);
        return map;
    }
}
