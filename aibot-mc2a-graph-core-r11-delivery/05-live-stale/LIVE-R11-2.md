# LIVE-R11-2 — 同化身执行期 stale/loss → Graph STALE

**结论: PASS**(第 1 次为装置问题,重跑即过)。

## 装置

同一隔离服(R11-1 后)。驱动:`r11_live2_stale.py`(本目录 `drivers/`)。
矿格 `(560, 68, 131)`(与 R11-1 的 129 错开),化身 id `ore_ab3babe1251c_ac15e54bac7e4fc3`
(注册表条目在第 1 次失败后被标记 UNREACHABLE 仍属活动化身,重观察按 OPP-INC-2 复用同一 id)。

## 流程

```
tp Bob 558(注册: ac15e… ACTIONABLE, iron_pickaxe 已给)
tp Bob 530(30 格外) → plan r11-stale-exec-2 → graph-973d0b7c12c54b8ce32b7716 READY
run-next(dispatch_request_id=graphd-5bc4a92b…, 普通 graphRunNext→BridgeKernel.submit 路径)
1s 后 RCON setblock 560 68 131 air(机器人尚在 ~28 格外,未到可见距离)
任务走到可见距离 → 严格观察格内容 ≠ iron_ore → markOpportunityStale(durable 收据) → 物理任务类型化失败
```

## 终态证据

图/节点(归档快照):

```json
graph-973d0b7c12c54b8ce32b7716  state=STALE
node n-mine-ac8542cce5b1  state=STALE
  reason=execution_failed_terminal_unsatisfied:…
  execution_id=05940fdc-0710-44f7-9e72-3a15d985e3f2
  subject_ref.object_id=ore_ab3babe1251c_ac15e54bac7e4fc3
```

durable stale 收据(`journal-receipt-stale-exec.txt`,journal seq **853**,与本图 execution_id 一一对应):

```json
{"kind":"resource_opportunity_stale",
 "execution_id":"05940fdc-0710-44f7-9e72-3a15d985e3f2",
 "opportunity_id":"ore_ab3babe1251c_ac15e54bac7e4fc3",
 "world_id":"80980dea-4a25-46fa-ab97-eeefcc8b4b39",
 "dimension":"minecraft:overworld",
 "payload":{"x":560,"y":68,"z":131,"block":"minecraft:iron_ore",
            "reason":"externally_consumed_or_stale",…}}
```

断言:`GRAPH_STALE_NOT_FAILED: OK` —— state==STALE、reason 前缀
`execution_failed_terminal_unsatisfied:`、无裸 `execution_failed`、非 DONE。
**同化身**(dispatch 的就是 ac15e…)执行期损失把 FAILED 改判为 STALE,即 TG-STALE-1。

## 对照:普通失败仍为 FAILED

R11-1 失败迭代轮(r11-incarnation-b/c/d/e 四张图,`aibot-mc2a-graph-core-r11-delivery/05-live-stale/ordinary-failure-contrast.txt`)
全部为 **FAILED / execution_failed** —— 无终态负向收据的机械性失败没有被改判为 STALE。
JUnit `failedExecutionWithDurableTerminalLossBecomesStaleNotFailed` 同步覆盖正/负两分支。

## 第 1 次失败(装置)

bot 站 550(矿 10.2 格,已在观察半径内),派发 tick-1 即做工位扫描,矿尚未移除即
`no_reachable_work_pose` 失败(格 (560,68,131) 四邻无可站面)。修正=30 格外派发,任务先走
seenFrom 接近分支,移矿后到达可见距离时 state 不匹配分支(在工位扫描**之前**)触发 stale。
