package io.github.zoyluo.aibot.external.cognition;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.HomeBlockEquivalence;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MC-2A0.1 structure live-verification policy v0.1(BOUND-1..4):
 *
 * server authority != LLM current perception——registry/服务器内部可以读世界,
 * 不代表认知视图可以把远程结构标成 VERIFIED_LIVE。判定链:
 *
 * 1. bounds 粗筛(纯几何,不读世界): bot 到结构 bounds 最近点超出本地验证包络
 *    {@link #VERIFY_RADIUS} → 绝不扫描当前 structure cells(BOUND-2);
 * 2. 粗筛通过后逐格证明: 每个 baseline cell 必须先过与 perception 同界的
 *    canObserveBlock 严格射线;任何一个 cell 不可证明 → 整体放弃(不部分扫描);
 * 3. 全部 cell 证明通过才逐格 getBlockState 统计 matched/missing/wrong(proof
 *    before read,BOUND-1),此时才允许 VERIFIED_LIVE;
 * 4. 无法 LIVE 时输出 durable facts + LAST_KNOWN(上次合法验证的数字,绝不当
 *    当前真相)或 UNKNOWN(BOUND-3/4);不确定绝不伪装成 repairable=false 之类
 *    的确定事实。
 *
 * LAST_KNOWN 缓存是 cognition 进程内存(READ-1:不写 registry/世界/任务,重启即丢),
 * 与 registry 自身的远程 integrity 缓存完全独立——后者是服务器权威路径(mc_observe),
 * 认知侧绝不透传(那正是本轮封住的洞)。
 */
final class StructureKnowledge {
    private StructureKnowledge() {}

    /** 本地验证包络:与 inspect_local/perception 的观察半径上界一致。 */
    static final int VERIFY_RADIUS = 16;
    /** 验证结果缓存窗(tick):粗筛内的重复 view build 不重复逐格证明(与 registry INTEGRITY 缓存同界)。 */
    static final long VERIFY_CACHE_TICKS = 20;

    /** 一次合法逐格验证的完整结果(期望/匹配/缺失/错块 + 验证时刻)。 */
    record Verified(long atTick, long atGameTime, long expected, long matched, long missing, long wrong) {}

    /** assess 的三态结果:knowledge/freshness + LIVE 或 LAST_KNOWN 的数字(LIVE 时不为 null)。 */
    record Assessment(String knowledge, String freshness, Verified live, String unknownReason) {
        static Assessment live(Verified v) { return new Assessment("VERIFIED_LIVE", "LIVE", v, ""); }
        static Assessment lastKnown(Verified v, long nowGameTime) {
            return new Assessment("LAST_KNOWN", CognitiveInspector.freshnessOf(v.atGameTime(), nowGameTime), v, "");
        }
        static Assessment unknown(String reason) { return new Assessment("UNKNOWN", "UNKNOWN", null, reason); }
    }

    private static final class Entry {
        volatile Verified verified;
        volatile long verifiedTick = -1;
        volatile long failedTick = -1;
    }

    /**
     * key = world_id + "/" + dimension + "/" + structureId;只在 server 线程读写(view build / materialize)。
     * MC-2A0.1F (COG-AQ-6/SPATIAL-CACHE-1):LAST_KNOWN 验证身份必须绑定 save/world——
     * 同名结构在不同 world/save 中绝不能共享验证缓存(本 CACHE 无生命周期清理,跨 JVM 会话
     * 尤其如此)。world_id 缺失(registry 未 start)直接 fail-loud,绝不静默退化为可跨 world 串扰的窄 key。
     */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** test/live-only instrumentation(逐格证明路径计数;LIVE-2A01 证据,生产零输出)。 */
    public static final java.util.concurrent.atomic.AtomicLong VERIFY_CALLS = new java.util.concurrent.atomic.AtomicLong();

    /** bot 到结构 bounds 最近点的水平+垂直距离是否落在本地验证包络内(纯几何,零世界读)。 */
    static boolean withinEnvelope(AIPlayerEntity bot, SemanticWorldRegistry.StructureEvidence structure) {
        BlockPos feet = bot.getBlockPos();
        int nearestX = Math.max(structure.minX(), Math.min(feet.getX(), structure.maxX()));
        int nearestY = Math.max(structure.minY(), Math.min(feet.getY(), structure.maxY()));
        int nearestZ = Math.max(structure.minZ(), Math.min(feet.getZ(), structure.maxZ()));
        long dx = feet.getX() - nearestX, dy = feet.getY() - nearestY, dz = feet.getZ() - nearestZ;
        return dx * dx + dy * dy + dz * dz <= (long) VERIFY_RADIUS * VERIFY_RADIUS;
    }

    /**
     * view/inspect 共用的当前知识判定(只读,带短窗缓存)。
     * 任何路径都绝不读取粗筛外的 structure cell(BOUND-2)。
     */
    static Assessment assess(AIPlayerEntity bot, SemanticWorldRegistry.StructureEvidence structure,
                             long nowGameTime, long nowTick) {
        String key = SemanticWorldRegistry.worldId() + "/" + structure.dimension() + "/" + structure.id();
        Entry entry = CACHE.get(key);
        if (!withinEnvelope(bot, structure)) {
            // 远程:不扫描。只有"曾经的合法验证"可以 LAST_KNOWN 呈现,否则 UNKNOWN。
            Verified known = entry == null ? null : entry.verified;
            return known != null ? Assessment.lastKnown(known, nowGameTime)
                    : Assessment.unknown("not_currently_verifiable");
        }
        if (entry != null) {
            if (nowTick - entry.verifiedTick >= 0 && nowTick - entry.verifiedTick < VERIFY_CACHE_TICKS && entry.verified != null)
                return Assessment.live(entry.verified);
            if (nowTick - entry.failedTick >= 0 && nowTick - entry.failedTick < VERIFY_CACHE_TICKS)
                return Assessment.unknown("cells_not_currently_observable");
        }
        Verified fresh = verify(bot, structure, nowGameTime, nowTick);
        if (entry == null) { entry = new Entry(); CACHE.put(key, entry); }
        if (fresh != null) {
            entry.verified = fresh; entry.verifiedTick = nowTick;
            return Assessment.live(fresh);
        }
        entry.failedTick = nowTick;
        return Assessment.unknown("cells_not_currently_observable");
    }

    /**
     * 完整逐格验证:先全部证明(canObserveBlock),全部通过才逐格读取。
     * 任一 cell 不可证明即返回 null——绝不部分读取(BOUND-1/2)。
     */
    /**
     * 完整逐格验证:先全部证明,全部通过才逐格读取。
     * 证明语义是"该位置当前可见"(canObserveCell 允许空目标格),而不是"该处实心方块可见"
     * (canObserveBlock 对 AIR 格恒 false)——否则任何被破坏的 baseline cell(missing)都永远
     * 无法进入 LIVE 验证,完整性数字就失去了意义。任一格不可证明即返回 null,绝不部分读取。
     */
    private static Verified verify(AIPlayerEntity bot, SemanticWorldRegistry.StructureEvidence structure,
                                   long gameTime, long tick) {
        VERIFY_CALLS.incrementAndGet();
        var world = bot.getServerWorld();
        for (SemanticWorldRegistry.SnapshotCellEvidence cell : structure.cells()) {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!ObservableWorldQuery.canObserveBlock(bot, pos) && !ObservableWorldQuery.canObserveCell(bot, pos))
                return null;
        }
        long matched = 0, missing = 0, wrong = 0;
        for (SemanticWorldRegistry.SnapshotCellEvidence cell : structure.cells()) {
            Identifier expectedId = Identifier.tryParse(cell.blockId());
            Block expected = expectedId == null ? null : Registries.BLOCK.getOptionalValue(expectedId).orElse(null);
            BlockState actual = world.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z()));
            if (expected == null) { wrong++; continue; }
            if (actual.isOf(expected) || HomeBlockEquivalence.equivalent(expected, actual.getBlock())) matched++;
            else if (actual.isAir() || actual.isReplaceable()) missing++;
            else wrong++;
        }
        return new Verified(tick, gameTime, structure.cells().size(), matched, missing, wrong);
    }
}
