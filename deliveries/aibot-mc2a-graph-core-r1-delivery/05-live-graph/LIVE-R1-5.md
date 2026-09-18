# LIVE-R1-5 — 遗留 Graph v0 DONE 迁移

**结论：PASS**

## 装置

用可移植 `TaskGraphStore`（`MakeLegacyDone.java`，纯 JDK 编译集）在真实 bin 格式上
构造 pre-fix v0 DONE fixture：`planOpportunity(r1c5-legacy-done, world/overworld/ore_legacyr1c5fixture0001)`
→ dispatch → `executionTerminal(completed, satisfied("opportunity_absent_from_current_registry"))`
—— 即旧"注册表缺席=成功"规则下入场的 DONE 节点，**无持久成功收据**。
备份真实存储后换入 fixture 重启（server-r1c-j.log）。

## 第一段：首就绪 tick 审计降级

```
R1-5 迁移后图状态: SUSPENDED
 节点: SUSPENDED | done_audit_success_not_durably_proven:opportunity_absent_without_durable_resolution_receipt
活跃执行(无重放应为None): None
```

- 旧 DONE → SUSPENDED ✓（审计：缺席无收据 = UNKNOWN，绝不认成功）
- 无自动物理重放 ✓

## 第二段：后到的有效持久收据可再对账回 DONE

停服后按帧格式（[len][body][crc32]）以正确序号向 journal 追加一条
`resource_opportunity_consumed`（opportunity_id=ore_legacyr1c5fixture0001，
resolution=inventory_gain_proven）→ 重启（server-r1c-l.log）：

```
R1-5 补收据后图状态: DONE
 节点: DONE | reconciled_postcondition_satisfied:durable_inventory_gain_receipt
```

同链路由 JUnit `legacyDoneWithoutDurableSuccessReceiptIsDowngraded`（SUSPENDED→reconcile→DONE）确定性覆盖。

## 清理

真实存储已从 `task-graphs-bob.bin.pre-r1c5` 恢复；生产世界未被破坏。
