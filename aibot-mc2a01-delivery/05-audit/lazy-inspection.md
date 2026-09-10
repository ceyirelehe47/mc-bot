# Audit｜lazy inspection

## 修复前（MC-2A0 缺陷 P1-1）

`CognitiveViewBuilder.build` 每 5 tick 调 `CognitiveInspector.buildIndex`，
预计算**全部** structure（summary/integrity/baseline 三档）+ farm（summary/cells）+
opportunity（summary/evidence）的 canonical JSON——其中 structure baseline 还会经
homeRepairPlan 读取全部 baseline cells。

## 修复后

- `CognitiveSnapshot.Snapshot.inspectIndex` 类型从 `Map<String,Map<String,String>>`
  （ref→{档位→JSON}）改为 `Map<String,EvidenceDescriptor>`（ref→轻量身份句柄：
  evidenceRef/kind/objectId/role）。descriptor 不含任何 detail JSON。
- `CognitiveViewBuilder.build` 不再调用 buildIndex / homeRepairPlan；
  `descriptorIndex` 只遍历 semantic 快照（零世界读）。
- `CognitiveInspector.materialize(bot, semantic, gameTime, ref, detail)` 是唯一 detail
  展开入口：kind 白名单（INSPECT_DETAILS）→ semantic 成员校验（404 fail-closed）→
  按 kind+detail 分派单档物化。
- `homeRepairPlan` 仅在"显式 inspect baseline + 当前可 LIVE 验证"组合下可达。
- freshness 用 snapshot gameTime（与 view 卡同源，三处一致）。

## 性能对比（实机）

| 查询 | MC-2A0 | MC-2A0.1 |
|---|---|---|
| mc_view p50 | ~15ms（10.3–10.9KB） | **10.0ms**（p90 22.1，max 48.2） |
| mc_inspect p50 | ~15.4ms（同步读缓存） | 50.1ms（p90 52.9） |
| mc_inspect_local(8) p50 | 47.8ms | 85.1ms（p90 116.6，max 123.3） |
| mc_inspect_local(4) p50 | — | 53.1ms |

- view 变快且更稳：不再预计算 inspect detail。
- inspect 的 ~50ms p50 主体是**排队到下一 server tick 的固定延迟**（每 tick ≤1 的
  预算代价），不是物化本身昂贵。
- local 变慢 ~1.8×：proof-before-read 后空气格也要过严格射线（旧代码先 isAir 短路）。
  按任务书 §9 处置：明确记录；**MC-2A1 不得自动高频调用 local inspect**；后续优先
  复用 resident perception evidence。

## 锁定

- JUnit source-contract：builder 无 buildIndex/homeRepairPlan；descriptor 存在；
  homeRepairPlan 仅 baseline 路径；backend materialize 复用 semantic 缓存。
- BridgeCoreTest：5×tick+view 后 materializes 增量 0；单次 inspect 增量 1。
- 实机：LIVE-2A01-3（20 次 view 零 detail 泄漏 + 单次物化）。
