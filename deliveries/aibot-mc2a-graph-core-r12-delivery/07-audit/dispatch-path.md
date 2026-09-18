# 审计 — Graph 派发路径(dispatch-path)

结论: 与 R1/R1.1 完全一致, 本轮零改动。

- 唯一物理派发路径仍是 `graphRunNext → BridgeKernel.submit → MinecraftBodyBackend`。
  R1.2 两个补丁只触碰 SemanticWorldRegistry/ExternalBodyRuntime 的机会生命周期与
  resourceOpportunityStale 返回值, 不经过也不修改 dispatch 链。
- `resourceOpportunityStale` void→boolean 仅为传播 journal 结果供移除决策; 全部调用方
  (KnownResourceTask ×2、GameTest 清场、registry 内部)为语句式调用, 无破坏(影响面审查
  逐一列过)。
- LIVE-R12-1 的 DONE 图回调带 `dispatch_request_id=graphd-…`(r12_live1_d_redo.py 的
  X-Request-Id 链); 无 backend.start 直调; 源码契约 `TaskGraphBridgeSourceContractTest` 绿。
- 九状态模型、restart RUNNING→SUSPENDED/no blind replay、durable consumed=成功权威、
  registry 缺席=UNKNOWN: JUnit/契约全绿(385/0 ×2), GameTest 636/0 ×3。
