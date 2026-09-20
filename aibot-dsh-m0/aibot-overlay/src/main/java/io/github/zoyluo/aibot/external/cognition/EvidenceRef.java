package io.github.zoyluo.aibot.external.cognition;

import io.github.zoyluo.aibot.external.BridgeFault;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * MC-2A0 EvidenceRef:把 drill-down 引用绑定到 world_id + dimension + kind + object_id 四元组。
 *
 * 形如 mc://&lt;world_id&gt;/&lt;urlencoded-dimension&gt;/&lt;kind&gt;/&lt;urlencoded-object_id&gt;。
 * parse 严格 fail-closed:段数、前缀、kind 白名单、空段任何一处不合法即拒绝;
 * foreign world/dimension/kind mismatch 由调用方索引查找兜底(404),绝不静默回退(VIEW-8/9)。
 */
public final class EvidenceRef {
    public static final Set<String> KINDS = Set.of("structure", "farm", "opportunity");

    private EvidenceRef() {}

    public record Parsed(String worldId, String dimension, String kind, String objectId) {}

    public static String format(String worldId, String dimension, String kind, String objectId) {
        if (worldId == null || worldId.isBlank()) throw new BridgeFault(500, "evidence_ref_missing_world_id");
        if (dimension == null || dimension.isBlank()) throw new BridgeFault(500, "evidence_ref_missing_dimension");
        if (!KINDS.contains(kind)) throw new BridgeFault(500, "evidence_ref_invalid_kind");
        if (objectId == null || objectId.isBlank()) throw new BridgeFault(500, "evidence_ref_missing_object_id");
        return "mc://" + encode(worldId) + "/" + encode(dimension) + "/" + kind + "/" + encode(objectId);
    }

    /** malformed 一律 400 invalid_evidence_ref;解析成功不代表对象存在或属于当前作用域。 */
    public static Parsed parse(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 512) throw invalid();
        if (!ref.startsWith("mc://")) throw invalid();
        // 剥离 mc:// 后恰好四段:world/dimension/kind/object_id;id 内未编码的斜杠会多出段即拒绝
        String[] segments = ref.substring(5).split("/", -1);
        if (segments.length != 4) throw invalid();
        String worldId = decode(segments[0]);
        String dimension = decode(segments[1]);
        String kind = segments[2];
        String objectId = decode(segments[3]);
        if (worldId.isBlank() || dimension.isBlank() || objectId.isBlank() || !KINDS.contains(kind)) throw invalid();
        return new Parsed(worldId, dimension, kind, objectId);
    }

    /** URLEncoder 把空格编码为 +,URI 语义应为 %20,统一替换避免 round-trip 歧义。 */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String decode(String value) {
        String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        return decoded.isBlank() ? "" : decoded;
    }

    private static BridgeFault invalid() {
        return new BridgeFault(400, "invalid_evidence_ref");
    }
}
