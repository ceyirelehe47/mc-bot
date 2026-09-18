# Audit｜cognition read boundary

## 数据流（修复后）

```
kernel.tick (server thread, every <=5 ticks)
→ MinecraftBodyBackend.cognitiveSnapshot(journal)
   → refreshCaches()（与 observeJson 共用 10-tick semantic 缓存）
   → CognitiveViewBuilder.build(bot, journal, semanticSnapshot)
      ├─ scene：compact cards
      │   ├─ structure：semantic 快照成员 + registry StructureEvidence(durable)
      │   │   + StructureKnowledge.assess（bounds 粗筛 → 逐格证明 → 全证才读）
      │   ├─ farm / opportunity：semantic 快照（registry 持久化数据，无远程世界读）
      │   └─ execution（IDLE⇒current_task=null）
      └─ inspectIndex：EvidenceDescriptor（ref/kind/objectId/role，零 detail）

mc_inspect(ref, detail)
→ kernel.submitInspectQuery（HTTP 线程 fail-fast：400/404/429/503 同步返回）
→ 队列（与 inspect-local 共享，每 tick ≤1，deadline 5s）
→ server thread: BodyBackend.materializeEvidence(ref, detail, snapshot.gameTime)
   → CognitiveInspector.materialize（单 ref+单 detail，按需展开）
      └─ structure 档位全部经由 StructureKnowledge.assess（绝不透传 observe integrity）
```

## StructureKnowledge v0.1 policy（BOUND-1..4）

1. **bounds 粗筛**（纯几何，零世界读）：bot 到结构 bounds 最近点 >16 格 ⇒ 绝不扫描
   cells；输出 durable facts + LAST_KNOWN（若有合法历史）或 UNKNOWN。
2. **逐格证明**：粗筛内对全部 baseline cells 跑 `canObserveBlock || canObserveCell`
   （证明"该位置可见"；canObserveCell 允许空目标格，否则 missing cell 永不可验）。
   任一格不可证明 ⇒ 整体放弃，绝不部分读取。
3. **全证才读**：证明全过后才逐格 getBlockState 统计 matched/missing/wrong，
   此时才允许 VERIFIED_LIVE。
4. **呈现分层**（BOUND-3/4）：durable（id/role/bounds/baseline_cells）与
   current_integrity 分离；UNKNOWN 时 missing/wrong 输出 null（绝不伪装 0）；
   LAST_KNOWN 附 verified_game_time 标注数字来源时刻。

缓存：cognition 进程内存（20-tick 窗，server 线程私有），不写 registry/世界/任务
（READ-1），重启即丢。registry 自身的 observe/integrity 路径未动（mc_observe 的
服务器权威语义保持，认知与 observe 在结构 integrity 上**有意不一致**——view 更严）。

## 守卫锁定

- GameTest：`mc2a01RemoteStructureNeverClaimsLiveIntegrity`（远→改→不泄漏→回→重验证）、
  `mc2a01NearObservableStructureCanClaimLiveIntegrity`（近+破坏=真实 missing）。
- JUnit source-contract：cognition 六文件无 setBlockState/breakBlock/insertStack/assign；
  StructureKnowledge 证明先于读取；无 Agenda/TaskGraph/Scheduler 符号。
- 实机：LIVE-2A01-1 全流程 PASS。
