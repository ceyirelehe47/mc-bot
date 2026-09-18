# Audit｜query budget

## 修复前（MC-2A0 缺陷 P1-2）

```java
while (!localQueries.isEmpty()) { …backend.inspectLocalJson(…)… }  // 单 tick 全 drain
```

4 个 ~50ms 查询可在同一 server tick 连续执行（理论 200ms 停顿）。

## 修复后（BridgeKernel.tick）

```java
long now = mono.getAsLong();
while (!localQueries.isEmpty() && localQueries.peek().deadline() <= now) {
    LocalQuery expired = localQueries.poll();
    if (!expired.future().isDone())
        expired.future().completeExceptionally(new BridgeFault(503, "query_deadline_expired"));
}
if (!localQueries.isEmpty()) {          // 每 tick 至多执行一个
    LocalQuery query = localQueries.poll();
    … "inspect".equals(query.kind())
        ? backend.materializeEvidence(query.ref(), query.detail(), cognitiveGameTime())
        : backend.inspectLocalJson(query.radius(), query.detail()) …
}
```

- **PERF-1**：每 tick 至多 1 个 expensive 认知查询（inspect materialize 与
  inspect-local 共享同一队列与预算）；其余留队下一 tick。选择"一 tick 一个"而非
  wall-clock budget：更简单、稳定、可审计（任务书 §5.2 推荐）。
- **PERF-2**：deadline（mono+5000ms，与 HTTP awaitJson 的 5s get 对齐）先于执行回收；
  客户端已超时放弃的查询绝不执行。已完成的 future（HTTP 侧 get 超时后仍执行完的
  边缘）不重复完成。
- **队列上限**：4（429 too_many_local_queries），inspect/local 共享。
- **线程模型**：materialize 必然在 server 线程（HTTP 线程从不触碰 Minecraft 状态）；
  HTTP 侧 fail-fast 校验（ref 解析/白名单/容量）同步返回，等待走无锁 future.get
  （kernel 全 synchronized，持锁等待 tick 会死锁——沿用 MC-2A0 的 submitLocalQuery 模式）。

## 实测（LIVE-2A01-4）

4 并发 burst：wall 388/396ms、spread ~140ms（分散于多个 tick，非同时完成）；
burst 期间无 tick 停顿告警。FakeBackend 计数断言（BridgeCoreTest）：
单 tick 仅执行 1 个；过期查询执行数 == 0。
