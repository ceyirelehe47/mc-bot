# LIVE-R1-4 — 崩溃窗口启动对账

**结论：PASS**

## 模拟的崩溃窗口

LIVE-R1-2 的 consumed 收据已持久（journal seq 456）。停服后向
`external-semantics-bob.json` 注入该已消费机会的**旧语义快照**条目
（`injected-old-snapshot-r1c-4.json`，status=ACTIONABLE）——即"收据已 fsync、
语义异步快照滞后"的跨存储崩溃窗口。重启运行时。

## ExternalBodyRuntime.start 顺序（源码即证据）

journal 打开/重放 → `SemanticWorldRegistry.reconcileOpportunityTerminalReceipts(journal)`
（对账并 persistAsync().join() 等待语义持久化完成）→ 之后才暴露 Graph/HTTP。

## 证据（server-r1c-b.log 会话）

- 重启后注册表快照中 `ore_fed47d71…` **不存在**（启动对账已移除复活条目，端点就绪前完成）
- `r1c-iron-2` 图保持 `DONE | postcondition_satisfied:durable_inventory_gain_receipt`
  —— DONE 仍由持久收据背书，与语义快照状态解耦
- 无任何物理重放（status.active_execution=null）

## 附带护栏证据

手工以错误序号追加 journal 帧时，运行时以 `journal_sequence_gap` fail-closed 拒绝启动
（server-r1c-k.log）——单写者日志护栏在位。
