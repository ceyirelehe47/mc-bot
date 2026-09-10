package io.github.zoyluo.aibot.external.cognition;

import io.github.zoyluo.aibot.external.BridgeFault;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MC-2A0 canonicalization/hash 基础件单测(纯 JDK 部分):
 * scene_hash 的全部确定性性质最终都落到 CanonicalJson 的字节稳定性上。
 * 依赖 Minecraft classpath 的 hash 语义(inventory/state 变化改 hash、时钟噪声不改)
 * 由 MC2A0CognitiveViewGameTests 在真实 GameTest 环境覆盖。
 */
final class CognitiveCanonicalizationTest {

    @Test
    void mapInsertionOrderNeverChangesCanonicalBytes() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1L); a.put("a", 2L); a.put("m", Map.of("q", true));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", 2L); b.put("m", Map.of("q", true)); b.put("z", 1L);
        Map<String, Object> c = new TreeMap<>(a);
        assertEquals(CanonicalJson.write(a), CanonicalJson.write(b));
        assertEquals(CanonicalJson.write(b), CanonicalJson.write(c));
    }

    @Test
    void numberEncodingIsDeterministicAndFinite() {
        assertEquals("{\"h\":20.0,\"n\":3,\"t\":true,\"x\":null}",
                CanonicalJson.write(ordered("h", 20.0D, "n", 3, "t", true, "x", null)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("bad", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("bad", Double.POSITIVE_INFINITY)));
        // 同一浮点值经 Float/Double 路径必须同字节,health(float)与 distance(double) 不产生格式漂移
        assertEquals(CanonicalJson.write(Map.of("v", 19.5D)), CanonicalJson.write(Map.of("v", 19.5F)));
    }

    @Test
    void listOrderIsPreservedWhileSetLikeCollectionsMustBeSortedByBuilder() {
        assertEquals("[1,2,3]", CanonicalJson.write(List.of(1L, 2L, 3L)));
        assertEquals("[3,2,1]", CanonicalJson.write(List.of(3L, 2L, 1L)));
        // 同一卡片集合按确定键排序后输出,迭代来源无关
        Map<String, Object> sceneA = Map.of("cards", sortedCards("a", "b"));
        Map<String, Object> sceneB = Map.of("cards", sortedCards("b", "a"));
        assertEquals(CanonicalJson.write(sceneA), CanonicalJson.write(sceneB));
    }

    @Test
    void stringEscapingMatchesJsonOutput() {
        assertEquals("{\"s\":\"quote\\\"slash\\\\nl\\n\"}",
                CanonicalJson.write(Map.of("s", "quote\"slash\\nl\n")));
    }

    @Test
    void evidenceRefRoundTripsAndFailsClosed() {
        String ref = EvidenceRef.format("2eadb4ef-61bb", "minecraft:overworld", "farm", "r2 farm");
        assertEquals("mc://2eadb4ef-61bb/minecraft%3Aoverworld/farm/r2%20farm", ref);
        EvidenceRef.Parsed parsed = EvidenceRef.parse(ref);
        assertEquals("2eadb4ef-61bb", parsed.worldId());
        assertEquals("minecraft:overworld", parsed.dimension());
        assertEquals("farm", parsed.kind());
        assertEquals("r2 farm", parsed.objectId());
        for (String malformed : new String[]{
                "not-a-ref", "mc://only-three", "mc://w/d/k", "mc://w/d/k/i/extra",
                "mc://w/d/vehicle/i", "mc://w/d/structure/", "mc:///d/structure/x", ""}) {
            BridgeFault fault = assertThrows(BridgeFault.class, () -> EvidenceRef.parse(malformed),
                    "expected fail-closed for: " + malformed);
            assertEquals(400, fault.status);
        }
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    private static List<Map<String, Object>> sortedCards(String... ids) {
        return java.util.Arrays.stream(ids).sorted()
                .map(id -> Map.<String, Object>of("object_id", id))
                .toList();
    }
}
