package io.github.zoyluo.aibot.external.cognition;

import java.util.Map;
import java.util.Set;

/**
 * 认知查询的纯 JDK 契约类型:kernel/HTTP/桥测试(test.sh 的纯 JDK 编译集)与
 * Minecraft 侧构建器之间唯一共享的类型边界。
 * 不 import 任何 Minecraft 类——重实现必须保持这一点,否则纯 JDK 桥测试编译集被破坏。
 */
public final class CognitiveSnapshot {
    private CognitiveSnapshot() {}

    /** 每次查询请求的硬上限(任务书 §7;超限 fail-closed 而非静默截断)。 */
    public static final int VIEW_MAX_BYTES = 32768;
    public static final int LOCAL_MAX_BYTES = 65536;
    public static final int INSPECT_MAX_BYTES = 65536;

    /** mc_inspect_local 的 detail 白名单(协议层约定,HTTP/kernel 与实现共用同一常量防漂移)。 */
    public static final Set<String> LOCAL_DETAILS = Set.of("summary", "blocks", "entities", "all");

    /**
     * MC-2A0.1: mc_inspect 的 detail 档位按 evidence kind 白名单化(LAZY-3:
     * 只 materialize 被请求的 ref+detail,未知档位在协议层即 400 fail-closed)。
     */
    public static final Map<String, Set<String>> INSPECT_DETAILS = Map.of(
            "structure", Set.of("summary", "integrity", "baseline"),
            "farm", Set.of("summary", "cells"),
            "opportunity", Set.of("summary", "evidence"));

    /**
     * 轻量 evidence 句柄(LAZY-1):view 快照只携带 ref 与 durable 身份字段,
     * 不携带 baseline/cells/evidence 等 detail JSON——那些由 mc_inspect 按需 materialize。
     */
    public record EvidenceDescriptor(String evidenceRef, String kind, String objectId, String role) {}

    /**
     * server 线程构建、kernel 缓存的只读快照。
     *
     * @param sceneJson    canonical scene 字节(meta 不含)
     * @param sceneHash    sceneJson 的 SHA-256 hex(小写);kernel 以 "sha256:" 前缀对外
     * @param gameTime     构建时刻的 world.getTime(),只进 meta 不进 scene;materialize 的
     *                     freshness 也以此为准(三处一致:view 卡/inspect summary/evidence)
     * @param inspectIndex evidence_ref -> 轻量 descriptor(不是 detail JSON)
     */
    public record Snapshot(String sceneJson, String sceneHash, long gameTime,
                           Map<String, EvidenceDescriptor> inspectIndex) {}
}
