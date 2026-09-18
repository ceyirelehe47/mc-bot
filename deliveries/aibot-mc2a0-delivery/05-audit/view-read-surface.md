# MC-2A0 审计：认知视图读取面（view-read-surface）

## 结论
`mc_view` / `mc_inspect` / `mc_inspect_local` 三个查询全部为只读 read-model：
- 不写入任何 authority（世界 / SemanticWorldRegistry / TaskManager / journal / 身体状态）；
- 不产生 execution receipt、不消耗 lease、不被 body-busy 拒绝、不 pause/cancel/replace 执行；
- 与 `mc_observe` 的关键语义差异：`observe()` 会清 `needs_reconcile`（"观察即和解"），
  三个认知查询一律不清——`BridgeCoreTest` 以内核断言锁定
  （body 变更后 `view()` 成功且 `needs_reconcile` 仍为 true，`submit` 仍 409）。

## 读取面清单（生产代码逐调用点）
| 数据 | 来源 | 缓存 | 写风险 |
|---|---|---|---|
| scene.world | `SemanticWorldRegistry.worldId()` + bot 维度 | 无（恒等值） | 无 |
| scene.self | bot 实体只读访问（pos/health/food/inventory 计数） | 每次构建现算 | 无 |
| scene.environment | `world.getTimeOfDay/isRaining/getLightLevel` + `ObservableWorldQuery.canObserveEntity` 过滤的实体查询 | 无 | 无 |
| semantic_objects 卡片 | `MinecraftBodyBackend` 的 semantic 缓存（`SemanticWorldRegistry.observe(bot)`，10-tick，与 `observeJson` 共用同一份） | 复用 observe 缓存 | 无（PERF-1：不二次全扫，守卫断言锁定） |
| structure inspect baseline | 新增 `SemanticWorldRegistry.structureEvidences(bot)`（不可变快照拷贝）+ `homeRepairPlan`（既有只读 API） | 无 | 无 |
| farm inspect cells | 新增 `SemanticWorldRegistry.farmEvidences(bot)`（不可变快照拷贝） | 无 | 无 |
| opportunity inspect evidence | 既有 `SemanticWorldRegistry.opportunity(bot,id)` | 无 | 无 |
| recent_significant_events | `BridgeJournal.replay()`（synchronized 只读 List.copyOf，非消费，不推进任何游标；游标全部在客户端） | 无 | 无（VIEW-13） |
| inspect_local 块/实体 | `world.getBlockState` 逐格 + `ObservableWorldQuery.canObserveBlock/canObserveEntity` 过滤 | 无（显式请求才扫，不进 view 每 tick 路径） | 无 |

## 源码级守卫（可重放验证）
`PrivilegedBoundarySourceTest.cognitiveViewStaysAReadModel`（随 installer EXTRA_CHANGES 进入重放产物）：
- cognition 包 5 个生产文件不得出现 `.setBlockState(`/`.breakBlock(`/`.insertStack(`/`.assign(`；
- `MinecraftBodyBackend` 的视图构建必须经 `CognitiveViewBuilder.build(bot,journal,semanticSnapshot)`
  （证明复用 observe 语义缓存而非独立全扫）。

## kernel 侧 fail-closed 语义
- malformed ref → 400 `invalid_evidence_ref`（`EvidenceRef.parse`，段数/前缀/kind 白名单/空段全检）；
- foreign world / dimension / kind mismatch / unknown object → 404 `evidence_ref_not_in_current_view`（索引查找，绝不回退猜测）；
- 不支持的 detail 档 → 400 `unsupported_detail_level`；
- radius 1..16 之外 → 400；并发局部查询 >4 → 429；server tick 5s 未消费 → 503 `query_timeout`；
- view 超过 32 KiB / local 超过 64 KiB → 500 fail-closed（拒绝服务而非静默截断语义内容）。

## 与 DSH 的边界
三工具在 plugin 层不 `concludeTurn`、不 `autoWake`、不传 request-id（node 测试断言
`submissions.length===0 && concludes===0`）；事件通道未新增任何第二 truth store。
