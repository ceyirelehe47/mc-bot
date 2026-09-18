# 审计 — 物理分派路径与线程边界(任务书强制审查点 2/3/4/5)

## 审查点 2:graphRunNext 证明走既有 submit 台账

`BridgeKernel.graphRunNext`(源码区间锁定于 TaskGraphBridgeSourceContractTest):

```java
Optional<String> already=graphs.runningExecution(graphId);
if(already.isPresent()) return Map.of("graph",…,"execution",execution(already.get())); // 幂等重入
if(active!=null) throw new BridgeFault(409,"execution_in_progress");                    // 单操作槽
TaskGraphStore.Dispatch d=graphs.prepareDispatch(graphId);                              // 持久 RUNNING 先于提交
try {
    Map<String,Object> execution=submit(supplied,d.requestId(),d.operation(),d.arguments());
    graphs.attachExecution(d,String.valueOf(execution.get("execution_id")));
    return Map.of("graph",graphs.inspect(graphId),"execution",execution);
} catch(RuntimeException failure) {
    graphs.rejectPreparedDispatch(d,…);   // 已知失败回滚 READY,无孤儿 RUNNING
    throw failure;
}
```

- submit 即既有 `BridgeKernel.submit`(lease/ready/reconcile 门、idempotency-by-request、
  accepted-before-mutation journal、单槽、容量),图零旁路。
- 区间断言不含 `backend.start(`;`backend.start` 全仓唯一调用点在 tick() 既有路径。

## 审查点 3:transition 的图终态回调在服务端线程物理生命周期上

`transition()` 调用方枚举(源码级):`recover()`(启动线程=服务端)、`tick()`
(注释明示 never by HTTP worker threads,由 ExternalBodyRuntime.tick 驱动)、
`applyControl()`(tick 内控制队列排空)、`publish()`(游戏事件入口,服务端线程)、
`shutdown()`(server stopping)。`submit()`/HTTP 路径不触发 terminal transition。
故 `graphs.executionTerminal(e.id,state,backend::verifyGraphPostcondition)` 仅在
服务端线程执行;kernel 构造器先赋 graphs 字段再 recover(),无空指针窗口。

## 审查点 4:verifyGraphPostcondition 无变异

`MinecraftBodyBackend.verifyGraphPostcondition`:onThread() 断言 + 只读链
(bot.isAlive / SemanticWorldRegistry.worldId / getRegistryKey / registry.opportunity 查询),
返回三元 SATISFIED/UNSATISFIED/UNKNOWN;无任何 setBlock/start/cancel 调用。
默认实现(BodyBackend)返回 UNKNOWN("backend_postcondition_not_supported")。

## 审查点 5:HTTP worker 永不触 Minecraft

BridgeHttpServer 仅 import `com.sun.net.httpserver.*`+JDK;worker 只调 kernel
synchronized 方法与锁外 future 等待(awaitJson/awaitLocal,由服务端线程 tick
complete——既有防死锁设计)。图路由沿用同一模式(kernel.graph* synchronized)。

## 附:唯一 submit 路径申明(RESULT 必答)

**是否有任何物理图路径绕过 BridgeKernel.submit:否。**
图节点唯一物理出口 = graphRunNext→submit;G-LIVE-3 实机(普通 execution id +
mine_known_resource 任务 + 服务器日志 origin_reason=external_dsh:<execution>)证实。
