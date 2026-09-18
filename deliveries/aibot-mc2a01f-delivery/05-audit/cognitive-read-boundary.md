# Cognitive read boundary audit（MC-2A0.1F）

## 审计对象

PRODUCTION_SHA=13a490cb4ce3341aa09beeecc1e0e7b688ff89e2

## 自动路径 raw-read 消除证明（AUTO-OBS-1..4 / COG-AQ-1..4）

唯一能给 `INTEGRITY_RAW_READ_SCANS` 计数器 +1 的代码点是
`SemanticWorldRegistry.integrity()` 缓存未命中后的 baseline 全格扫描
（每格 `world.getBlockState(cell.pos())`，无观察证明）。`integrity()` 的调用方在
本轮拆分后只剩 `structureJson()`，而 `structureJson()` 的调用方只剩：

| 调用方 | 路径性质 | 自动路径可达 |
|---|---|---|
| `observe(bot)`（omniscient 入口） | 显式操作/测试直调 | 否（见下） |
| `registerHome` / `captureHome` 回执 | 显式操作 | 否 |

自动链的三个入口全部改走 bounded 入口，源码级不可达 omniscient 变体：

| 自动入口 | 修改后调用 | 锁定 |
|---|---|---|
| `MinecraftBodyBackend.refreshCaches()` | `SemanticWorldRegistry.observeBounded(bot)` | JUnit `automaticObservationUsesBoundedSemanticSnapshot` 断言 backend 源码含 `observeBounded(bot)` 且**不含** `SemanticWorldRegistry.observe(bot)` 字面量 |
| `CognitiveViewBuilder.build()` null-fallback | `observeBounded(bot)` | 同上（builder 断言） |
| `CognitiveInspector.materialize()` null-fallback | `observeBounded(bot)` | 同文件显式注释 + 行为由 GameTest mc2a01Near 覆盖 |

`observeBounded` 与 `observe` 共享同一实现（`observe(bot, includeCurrentIntegrity)`），
bounded 形态下 structure 卡由 `structureDescriptorJson()` 生成：仅
`id/kind/protected/inside/bounds/snapshot_cells`（AUTO-OBS-2 的 durable facts），
schema 标记为 `mc_spatial_semantics_v2_bounded`，**不含任何 current-integrity 字段**——
不是"扫描后隐藏"，而是扫描调用本身不存在（COG-AQ-4）。

## 行为证据

- GameTest `mc2a01fAutomaticViewNeverTriggersRemoteStructureIntegrityRead`
  （独立 mc2a01f 批）：真实 server 线程上驱动 `backend.observeJson()` +
  `backend.cognitiveSnapshot()`（BridgeKernel.tick 的同款自动入口）+ 每 tick view build
  连续 45+ tick（覆盖 10t semantic 缓存与 20t INTEGRITY 缓存过期窗，回归必然触发重扫），
  断言 `INTEGRITY_RAW_READ_SCANS` 增量恰为 0；durable 身份仍可见（LAST_KNOWN，
  baseline_cells>0，非 VERIFIED_LIVE/LIVE）；回到包络 25t 后经合法 proof-before-read
  路径重新 VERIFIED_LIVE，且该路径同样不需要 omniscient 扫描（counter 仍为 0）。
- LIVE-2A01F-4（隔离服真实长期运行）：200+ 真实 kernel tick + 重复 mc_view 期间
  `mc_observe` 输出 `semantic_world.schema=mc_spatial_semantics_v2_bounded` 且
  structures[] 无 integrity 字段（自动缓存确实走 bounded 入口）+ view 为 LAST_KNOWN
  非 LIVE + durable 仍在 + 回包络后 LIVE 恢复。见 `04-live/LIVE-2A01F-4-remote-read-zero.md`。

## cache scope（COG-AQ-6 / SPATIAL-CACHE-1）

`StructureKnowledge.CACHE` key 从 `dimension/id` 升级为
`SemanticWorldRegistry.worldId() + "/" + dimension + "/" + id`。
该 CACHE 无生命周期清理（跨 JVM 会话存活），world_id 是唯一能阻止同名结构跨
save/world 共享 LAST_KNOWN 验证的作用域；registry 未 start 时 `worldId()` 直接
fail-loud 而非静默退化。锁定：JUnit `structureKnowledgeCacheKeyIncludesWorldId`。
registry 自身的 `INTEGRITY` map（同样按 dimension+id 键控）由 `start()/clearRuntime()`
在每次 server 生命周期清空、load 时校验 world_id，不存在同类跨 world 残留，本轮不改。

## 显式操作未受影响（AUTO-OBS-4 / COG-AQ-5）

`homeRepairPlan()` 及其三个调用方（MinecraftBodyBackend repair_home/完成验证、
CognitiveInspector 显式 baseline materialize）保留各自的 current-state 读取语义；
`SemanticWorldRegistry.observe()`（omniscient）保留给 register/capture 回执与测试
直调 helper，普通 `mc_observe`（自动缓存出口）不再消费它的 integrity 字段。
