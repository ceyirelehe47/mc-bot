# 审计 — 生命周期顺序(lifecycle-order)

## 出生侧(birth durable-before-exposure)

`SemanticWorldRegistry.observeVisibleBlock` 的 prior==null 分支实际代码顺序
(源码契约 `OpportunityBirthDurabilitySourceTest.newIncarnationBirthMustBeDurableBeforeRegistryExposure`):

```
appendOpportunityBirth(opportunityLifecycleJournal,next)   // BridgeJournal 同步 fsync
OPPORTUNITIES.put(key, next); dirty = true;                 // 活动注册表
resourceOpportunityActionable(...)                          // actionable 事件暴露
```

`appendOpportunityBirth` 内 `journal.append(fields)` 为同步调用; 失败即抛
`semantic_registry_failed_closed:opportunity_birth_receipt_failed:*`, 化身不进注册表。

birth 收据字段(kind=resource_opportunity_birth): execution_id=""/world_id/dimension/
opportunity_id/block_id/x|y|z/seen_x|seen_y|seen_z/status/blocked_reason/required_tool/
last_seen_game_time/state_since_game_time/state_x|state_y|state_z/pickup_baseline——
18 字段完整覆盖 ResourceOpportunity 的恢复需求(18 参构造逐字段对应)。

## 启动顺序(reconcile 先于 Graph/HTTP)

`ExternalBodyRuntime.start` 实际顺序(契约测试 `startupLifecycleReconcilePrecedesGraphAndHttpExposure`):

```
SemanticWorldRegistry.start(server, BOT)
journal = new BridgeJournal(...)
SemanticWorldRegistry.reconcileOpportunityLifecycleReceipts(journal)   // 绑定+对账
graphs = new TaskGraphStore(...)
kernel = new BridgeKernel(...)
kernel.tick()   // fence restored legacy work
http  = new BridgeHttpServer(...); http.start()
```

## 终态侧(stale durable-before-removal)

- `markOpportunityStale`: `OPPORTUNITIES.get` → `resourceOpportunityStale`(kernel→journal fsync,
  返回 boolean) → 成功才 `OPPORTUNITIES.remove(key)` → persistAsync。失败时条目保留、返回 false。
- `observeVisibleBlock` 的 staled 循环同构: 收据成功 → `OPPORTUNITIES.remove(scoped(...))`。
  (注: 循环外仍留有只写不读的 `remove` 列表局部变量——任务书补丁原样形态, 移除职责已由
  staled 循环承接, 无行为影响, 本轮未清理以保持补丁忠实。)
- consumed 先行(R1 已有)本轮未触碰, JUnit/契约与 R1.1 交付保持绿。

## 重放规则(LIVE 实证)

- birth → activeBirths; consumed/stale → terminal 集合并关闭同 key 的 activeBirth。
- terminal 主导: LIVE-R12-2 注入含 C 旧快照重启后 C 不复活。
- birth 主导恢复: LIVE-R12-1 回滚掉 A 的快照重启后精确 A 恢复(纯启动即停形态)。
- 冲突 fail-closed: birth_after_terminal / conflicting_opportunity_birth_receipt /
  birth_cell_conflict / lifecycle_capacity_exceeded 四类护栏在位(本轮 LIVE 未触发, 由源码+编译保证)。

## 本轮必要配套修正(超出纯上下文修复, 如实记录)

1. **journal 绑定生命周期**: 任务书补丁把 `opportunityLifecycleJournal` 的清空放在
   `clearRuntime()`, 导致同服务器进程内的 registry reload(fail-closed reload 等)后绑定丢失,
   后续铸化身全部 fail-closed(GameTest 9 例复现)。修正: `clearRuntime()` 不再触碰绑定;
   绑定记录来源 server 实例(`opportunityLifecycleJournalServer`), 仅当 `start()` 检测到
   **不同 server 实例**时解绑。同 server reload 保留绑定(journal 文件不变), 跨 server 必然
   由新实例的 ExternalBodyRuntime.start→reconcile 重绑。该修正不改变任务书 §3 的任何
   出生/终态/收养/容量语义, 仅把补丁意图(铸化身必有 durable birth)在 reload 路径上补完整。
2. **gt7 失败消息补 cause**(测试可观测性, 断言语义未动)。
3. **MC1CASemanticsGameTests.java 文件名大小写**: overlay 工作区混合大小写与 git 索引
   (全大写)不一致, 干净重放的 javac 公共类名校验失败; 两步改名对齐索引。
