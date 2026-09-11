# G-LIVE-4 — DONE 后置条件证明

判定：**PASS**

## 证据(接 G-LIVE-3,execution 89654840 完成后)

物理执行 `completed`(普通 mine_known_resource 链路,矿石被真实开采+拾取)后:

```
graph.state: DONE
node: DONE | reason=postcondition_satisfied:opportunity_absent_from_current_registry
      | execution 89654840 | attempt 1
```

`POST /v1/view` 复核:该机会 `ore_1d40b871…` **已不在注册表**(视野中其余 14 个历史
机会照常列出,精确销账而非清场)。

## 判定依据(TG-DONE)

- 物理任务完成仅是必要条件:节点终态由服务端线程后置条件复证
  (`MinecraftBodyBackend.verifyGraphPostcondition` → `SemanticWorldRegistry.opportunity`
  作用域查询)授予。
- "completed 但机会仍在 ⇒ 不得 DONE"的方向由确定性 JUnit
  `TaskGraphStoreTest.completedExecutionNeedsPostconditionBeforeDone`(completed+UNSATISFIED
  ⇒ STALE)锁定,见 G-LIVE-5。
- DSH 实机会话交叉:模型经事件唤醒后 inspect 汇报 `node.state DONE / attempt 1 /
  postcondition_satisfied`,与桥级一致。

附:作用域不符情形(维度切换/世界不匹配)返回 UNKNOWN ⇒ SUSPENDED 而非假 DONE,
JUnit `restartSuspendsRunningNodeAndNeverReplaysIt` 覆盖。
