package io.github.zoyluo.aibot.external.cognition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MC-2A0 canonical JSON 序列化器:scene_hash 的字节来源。
 *
 * 确定性规则(与任务书 §6.3 对齐):
 * - object key 一律按字典序输出,与构建时的 Map 插入顺序无关(VIEW-6);
 * - List 保序:只有具备顺序语义的数组直接输出,集合语义(cards/uncertainty)必须先排序;
 * - 数字只接受有限值:整型输出十进制,浮点输出 Double.toString(JVM 内确定);
 * - 无空白、UTF-8、字符串转义与 JsonOutput 一致。
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeInto(out, value);
        return out.toString();
    }

    private static void writeInto(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { quote(out, s); return; }
        if (value instanceof Boolean b) { out.append(b ? "true" : "false"); return; }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            out.append(((Number) value).longValue());
            return;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("non_finite_canonical_number");
            out.append(Double.toString(d));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            // key 排序保证同内容同字节;value 递归同样规则。
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String k)) throw new IllegalArgumentException("non_string_key");
                keys.add(k);
            }
            keys.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                quote(out, keys.get(i));
                out.append(':');
                writeInto(out, map.get(keys.get(i)));
            }
            out.append('}');
            return;
        }
        if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object entry : list) {
                if (!first) out.append(',');
                first = false;
                writeInto(out, entry);
            }
            out.append(']');
            return;
        }
        throw new IllegalArgumentException("unsupported_canonical_type:" + value.getClass().getName());
    }

    /** 便于按确定字段顺序构建场景(输出仍会重新排序,这只是让调试输出也可读)。 */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
