package com.aegisos.core.util;

import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON builder to safely construct JSON payloads without string formatting errors.
 */
public class JsonBuilder {

    public static String escape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static String object(Map<String, Object> fields) {
        if (fields == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(entry.getKey())).append("\":").append(value(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    public static String array(List<?> items) {
        if (items == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object item : items) {
            if (!first) sb.append(",");
            sb.append(value(item));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static String value(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) return "\"" + escape(s) + "\"";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map) return object((Map<String, Object>) val);
        if (val instanceof List) return array((List<?>) val);
        // Fallback for custom objects that already provide JSON or toString.
        return "\"" + escape(val.toString()) + "\"";
    }
}
