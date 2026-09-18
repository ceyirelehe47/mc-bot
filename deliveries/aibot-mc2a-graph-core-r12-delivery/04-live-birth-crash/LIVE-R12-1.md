# LIVE-R12-1 — 出生持久 + 语义快照陈旧 + Graph 精确身份存活

判定: **PASS**(含一次如实披露的语义裁决, 见下)

装置: 装置矿格 (562,68,127) iron_ore, bot 站位 (561,68,127) 同层邻格, 派发/遣远点 (532,68,127)。
生产 jar sha256 `97eb1fa9…`(与最终源码一致, 重建可复现)。隔离服 mc-server-mc1ca / bot=Bob。

## 时间线(r12_state.json + journal + 语义快照)

1. **R1.2 代码启动**: 遗留 21 个活动机会含 R11-1 的 B 化身(无 birth 收据), 首启 reconcile
   全部收养(journal 875-879 区间可见一批 `resource_opportunity_birth`), 与 R12-3 机制同源。
2. **before-A 快照**(bot 遣远 30 格外, 未观察装置格): 21 机会, 装置格无机会
   (`r12-semantic-before-A.json`, sha256 见 snapshot-hashes.txt)。
3. **化身 A 诞生**: tp 矿旁观察 → `A = ore_fefc057feb30_24947ca16ae04bb2`
   (定位前缀 `fefc057feb30` 与同格前身一致, 16hex 化身后缀全新)。birth 收据 seq **909**
   durable(先于 `OPPORTUNITIES.put` 与 actionable 事件——源码契约
   `OpportunityBirthDurabilitySourceTest.newIncarnationBirthMustBeDurableBeforeRegistryExposure` 锁定)。
4. **Graph**: plan(r12-iron-1) → `graph-4f8553847a1b2ae206e5d6fa` READY → run-next → RUNNING(bot 走路中) → 优雅停服。
5. **精确 crash 窗口**: 语义文件被回滚为 before-A(A 不在); journal(birth 909)与图存储原样保留; 物理矿原样。
6. **纯启动即停**(无任何 observe/tp/HTTP 查询): 重启 → 就绪 → 立即停服 → **持久化注册表恢复精确
   同 id A**(22 机会, 装置格唯一条目 = A, 无新铸 B)。A 只能来自 journal 重放——
   这是"lifecycle reconcile 先于端点暴露"的最强 LIVE 形式(源码顺序另由契约测试锁定:
   reconcile → new TaskGraphStore → new BridgeKernel → new BridgeHttpServer)。
7. **再观察同一物理矿**: id 仍为 A, 未铸 B(阶段 C 实测)。

## 如实披露 — 原图的终态与"重新成图"裁决

重启后原图 `graph-4f855384…` 终态 **FAILED | reason=execution_failed**(inspect 实测)。
这不是语义快照陈旧所致, 而是 R1/R1.1 既定语义: 执行跨重启作废(no blind replay +
restart invalidates in-flight execution), 无 durable TERMINAL_UNSATISFIED 收据时按 R1.1
规则保持 FAILED(`failedExecutionWithDurableTerminalLossBecomesStaleNotFailed` 负分支)。
runbook 第 8 步"Graph is still nonterminal"未考虑执行跨重启作废路径; 本轮按 R1-3 先例
("同一机会重新成图")达成第 10-11 步的"existing Graph executes successfully to DONE"——
object 身份连续性是本门的核心, 已由 5-7 步证明。

## DONE 收尾(r12_live1_d_redo.py, attempt1)

- 重新成图 `graph-f332a00f0e91dca5…`(同 object_id=A, plan_key=r12-iron-1e1)
  → run-next → BridgeKernel.submit → bot 挖矿拾取 →
  **DONE | postcondition_satisfied:durable_inventory_gain_receipt**。
- attempt0 因 `pathfinding_throttled` 失败(bot 恢复初期寻路节流, 装置性失败非生产缺陷),
  8 秒后重试成功——失败与成功两图均留档于服务器日志。
- journal 收据链: **birth(A) seq=909 < consumed(A) seq=955**(drivers/journal-receipts-A.txt 全文)。

## 失败迭代记录(诚实披露)

1. 首跑 before-A 快照在放矿**之后**获取, bot 常驻观察先铸化身污染快照 → 改为"遣远→放矿→快照→回观察"。
2. 第一轮 A(0e040388…) 因阶段 B 脚本断言错(observe 视角≠注册表全量)导致服务器长跑, 图被恢复收尾判 FAILED;
   该轮装置随后以 stone 替换正常终结(stale seq=905 留档)。
3. 一次 `graph_has_no_ready_node`(plan_key 幂等命中已 FAILED 图)与两次
   `pathfinding_throttled`/`control_lease_invalid` 均为装置层问题, 重试机制吸收。

drivers/: r12_live1_a_birth.py / r12_live1_b2_restore_clean.py / r12_live1_c_done.py /
r12_live1_d_redo.py / r12_common.py。
