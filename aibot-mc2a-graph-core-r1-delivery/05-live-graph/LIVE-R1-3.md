# LIVE-R1-3 — 终局 stale/lost 永不 DONE

**结论：PASS**

## 流程（server-r1c-a/e 两段）

1. 机会 `ore_d9b0920e…`（564,68,129）经图 r1c-iron-3 派发；派发后立即 RCON 拆除矿石。
2. KnownResourceTask 到达 seen_from，发现方块已非注册 blockId →
   `markOpportunityStale` → **journal seq 468 `resource_opportunity_stale`**
   `{"reason":"externally_consumed_or_stale", …精确 world/dim/id…}`（结构化持久收据）。
3. 任务类型化失败 `known_resource_stale_externally_consumed`。
4. 同一机会重新成图（r1c-iron-3d，同位置派生同 id）→ run-next 后优雅停服 → 节点
   SUSPENDED（outcome_unknown，无物理重放，active_execution=null）。
5. 重启后首就绪 tick `reconcileSuspended` 读到持久 stale 收据：

```
图状态: STALE
 节点: STALE | reconciled_terminal_unsatisfied:durable_stale_or_loss_receipt
```

## 判定

- 结构化 stale 收据持久 ✓（seq 468）
- reconcile → STALE ✓（TERMINAL_UNSATISFIED 分支）
- 绝不 DONE ✓（该 id 全程无 consumed 收据，DONE 不可能成立）
- claim 按既有 STALE 语义释放 ✓（STALE 非 claim-holding 态，rebuildClaims 剔除）

同语义的确定性装置由 JUnit `suspendedTerminalLossBecomesStaleNeverDone` 双树覆盖。
