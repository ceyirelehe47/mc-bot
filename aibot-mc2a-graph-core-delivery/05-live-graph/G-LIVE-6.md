# G-LIVE-6 — 重启零重放

判定：**PASS**

手法:受控近矿 + `run-next` 后**立即桥控制暂停执行**(消除停服时序竞争)→
优雅停服 → 重启。共 6 次停/启(b..b9 日志),有效场景为 6h(graph-e6f49a…)。

## 证据链

停服前:`node RUNNING`(执行 paused 时图节点保持 RUNNING,快照确认)。

重启后(`server-graph-b8.log` epoch):

```
RECOVERED graph: SUSPENDED | node: SUSPENDED
             | reason=execution_outcome_unknown_no_replay | exec 959f71a3
active_execution: None
run-next → 409 graph_suspended_reconcile_or_cancel   <- 挂起节点阻断再分派(TG-15)
```

- **零自动重放**:新 epoch 日志中旧 execution id `959f71a3` 0 次出现、
  `task_assigned` 0 次——无任何物理变异被重放。
- **reconcile 只证不改**:机会仍在注册表时连续 observe×5,节点保持 SUSPENDED
  (外部移除的矿因空气格不可严格观察而不销账,注册表保留 LAST_KNWN——fail-closed)。
- **世界已证 ⇒ 无重放转 DONE**:随后普通 `mine_opportunity` 执行(模拟 LLM 重规划)
  触发注册表销账后一次 observe:

```
node: DONE | reason=reconciled_postcondition_satisfied:opportunity_absent_from_current_registry
```

- **不会静默改选新 READY**:整个挂起期间 run-next 一律 409,只有显式 cancel/世界证明
  两条出路(后者转入 G-LIVE-7 的取消路径验证)。

附:持久化 RUNNING 字节的 load()-转换(RUNNING→SUSPENDED)由确定性 JUnit
`restartSuspendsRunningNodeAndNeverReplaysIt`(真实文件重载)覆盖;实机优雅停服
在落盘前经 shutdown 的 outcome_unknown 转换达到同一终态,两层互补。
前置尝试 6b/6c/6d/6e 的 FAILED 均为场景工位/时序问题(路径节流、浮空矿无工位、
近矿完成过快),图语义映射全程正确,详见对应 server-graph-b*.log。
