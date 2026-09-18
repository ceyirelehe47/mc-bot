# 审计 — 跨存储崩溃顺序（cross-store crash ordering)

## 问题

Bridge 图状态（TaskGraphStore）同步持久；语义机会快照异步持久。旧顺序可能：
`背包证明 → 内存机会移除 → 图 DONE 持久 → 崩溃（语义异步写未落盘）` →
重启后陈旧语义快照复活机会，而图已 DONE——状态互相矛盾。

## 修复后的顺序

1. KnownResourceTask 证明 `inventory delta > 0`。
2. `markOpportunityConsumed` 先调 `ExternalBodyRuntime.resourceOpportunityConsumed` →
   `BridgeKernel.recordOpportunityResolution` → `journal.append`（fsync 边界）。
   失败 → 返回 false → 任务 `known_resource_success_receipt_not_durable` 失败，
   机会不移除，图不可 DONE。
3. 成功后才 `OPPORTUNITIES.remove` + `persistAsync()`。
4. 图的 DONE 只能由收据背书（见 terminal-outcome-authority.md）。

## 启动修复

`ExternalBodyRuntime.start`：
journal 构造（打开+重放）→ `SemanticWorldRegistry.reconcileOpportunityTerminalReceipts(journal)`
（扫描全部 consumed/stale 收据，按 world+dim 作用域移除复活的活跃机会；
有移除时 `persistAsync().join()` **等待语义持久化完成**，失败则
`semantic_registry_reconcile_failed` fail-closed）→ 之后才创建 TaskGraphStore 与桥 HTTP。

因此 BridgeJournal 成为机会终局结果的持久化围栏，而不承担 SpatialStore 的职责
（不存几何，只存终局收据）。

## 实测

LIVE-R1-4：注入含已消费机会的旧快照重启 → 启动对账移除、图 DONE 仍由收据背书、
无物理重放、端点就绪前完成。反例护栏：畸形 journal 帧（错序号）→ `journal_sequence_gap`
fail-closed 拒绝启动。
