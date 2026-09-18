# G-LIVE-5 — 后置条件不满足 ⇒ STALE(非 DONE)

判定：**PASS(确定性测试装置路径,任务书明示许可)**

任务书原文:"Use a controlled fixture/fake or test path where physical completion is
reported but opportunity remains. **Do not corrupt production world to manufacture this
if a deterministic test fixture is safer.**"

## 采用路径

JUnit `TaskGraphStoreTest.completedExecutionNeedsPostconditionBeforeDone`
(开发树与回放树均执行,372/0 内):

```java
store.executionTerminal("execution-1","completed",
    pc -> GraphPostconditionResult.unsatisfied("still_present"));
assertEquals("STALE", store.inspect(id).get("state"));   // 非 DONE
```

物理 `completed` + 机会仍在(UNSATISFIED)⇒ 节点 **STALE**,reason
`postcondition_unsatisfied:still_present`。

## 为什么不走实机制造

实机上制造"物理 completed 且机会仍在"需要破坏生产世界或竞态干预终态时序;
本轮实机已覆盖该状态机的另两个分支:

- SATISFIED ⇒ DONE(G-LIVE-4 实机);
- UNKNOWN(维度/世界不匹配)⇒ SUSPENDED(JUnit 重启用例 + G-LIVE-6 实机)。

STALE 分支与二者共用同一 `executionTerminal` switch(`TaskGraphStore.java`
`case "completed"` 三分支),确定性装置已足够。

## 实机邻接证据

DSH 实机会话第一轮:坏矿(地面平嵌无工位)⇒ 物理执行 failed ⇒ 节点 FAILED
(reason `execution_failed`)——失败方向的真实映射,与 STALE 方向互补。
