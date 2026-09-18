# 派发路径审计(dispatch-path)

## 唯一物理派发路径保持 graphRunNext → BridgeKernel.submit

- R1.1 两补丁未触及 `BridgeHttpServer` 图端点与 `BridgeKernel.submit`;本轮回调/日志中的
  `dispatch_request_id=graphd-…`(LIVE-R11-1 graph-59da783e…、LIVE-R11-2 graph-973d0b7c…)
  即图派发走 submit 台账的直接证据。
- 无任何 `backend.start` 直调:全仓 grep(gametest/main/test)无新绕过点;
  源码契约测试 TaskGraphBridgeSourceContractTest 在 380 JUnit 内全绿。

## executionTerminal 的 failed 分支(0002 号补丁)

- 仅当 `verifier.apply(postcondition)` 返回 **TERMINAL_UNSATISFIED**(即 durable
  stale/loss 收据已落盘)才把节点置 STALE,reason=`execution_failed_terminal_unsatisfied:<bound reason>`;
  其余(UNSATISFIED/UNKNOWN/无收据)保持 `FAILED / execution_failed`。
- verifier 由 BridgeKernel.transition 注入 = `verifyGraphPostcondition`(服务端线程,
  R1 语义);FAILED→STALE 与 refreshReadiness/isClaimHolding/graphState 兼容
  (STALE 与 FAILED 同为非持权终态)。
- RUNNING 重启→SUSPENDED 不盲放(R1 保持):LIVE-R11-1 重启后
  `execution_outcome_unknown_no_replay`;DONE 仍只经 durable consumed 收据
  (LIVE-R1-2 语义未动,R11 仅新增 FAILED→STALE 一条终态映射)。

## 自动化锚点

JUnit `failedExecutionWithDurableTerminalLossBecomesStaleNotFailed` 同一测试内覆盖
正分支(STALE+reason 前缀)与负分支(普通失败 FAILED);LIVE 对照见
05-live-stale/ordinary-failure-contrast.txt(5 张真实 FAILED 图)。
