package io.github.zoyluo.aibot.external;

import java.util.*;

/** Small output-only encoder. Input JSON is parsed by AIBot's existing Gson, not here. */
public final class JsonOutput {
    private JsonOutput() {}
    /** Only use for JSON produced by a trusted serializer (the Minecraft backend uses Gson). */
    public record Raw(String json) {
        public Raw { Objects.requireNonNull(json); }
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Raw r) return r.json();
        if (value instanceof String s) return quote(s);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("non_finite_json");
            return n.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            for (var e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) throw new IllegalArgumentException("non_string_key");
                out.add(quote(key) + ":" + encode(e.getValue()));
            }
            return out.toString();
        }
        if (value instanceof Collection<?> list) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            for (var entry : list) out.add(encode(entry));
            return out.toString();
        }
        throw new IllegalArgumentException("unsupported_json_type:" + value.getClass().getName());
    }
    public static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
