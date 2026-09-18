# G-LIVE-7 — 取消挂起图并释放声明

判定：**PASS**

场景:graph-da95f4c9…(同 G-LIVE-6 手法:run-next → 执行暂停 → 停服 → 重启)。

## 证据链(`server-graph-b9.log` epoch)

```
recovered: SUSPENDED | execution_outcome_unknown_no_replay
POST /v1/graphs/<id>/cancel?reason=glive7-replan
  → graph CANCELLED | node CANCELLED | reason=graph_cancelled:glive7-replan
同机会(ore_7502636e…)+ 新 plan_key=glive7-iron-2
  → 201 新图 graph-b121513f9eced59b3ead31b4 READY     <- 声明已释放
POST run-next → 新执行 34483e57 RUNNING                <- 物理重启(显式决策)
POST /v1/graphs/<新图>/cancel
  → 409 graph_has_running_execution_cancel_execution_first   <- 保护闸
```

- 节点 CANCELLED、claim 释放、新 plan_key 可重新声明同一机会。
- **图取消不会取消独立运行中的物理执行**:运行中图取消被 409 拒绝,必须先走执行级
  cancel——图层不拥有物理中止权。
- DSH 实机会话同型操作:模型对 FAILED 图 `mc_graph_cancel` 后用新 plan_key 重规划成功。
