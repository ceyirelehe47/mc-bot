# LIVE-2A01-4｜query budget

环境同 LIVE-2A01-1。驱动：`mc2a01_live.py burst 4`（4 线程并发 radius=8 blocks 查询）。

## 实测

```
burst n=4 completion_ms=['379', '236', '285', '330'] wall=388.1ms spread=143.4ms
burst n=4 completion_ms=['389', '252', '339', '304'] wall=396.0ms spread=136.8ms
```

## 判定

- **不在一个 server tick 全部执行**：4 个查询完成时间分散在 ~236–389ms 区间
  （spread ~140ms）。若仍是旧的 while-drain 语义（单 tick 连续执行 4 个 ~90ms 查询），
  4 个 future 会几乎同时完成、wall ≈ 单个查询耗时；实测 wall ≈ 388–396ms ≈
  4 × (排队到下一 tick + 执行)，符合"每 tick 至多 1 个"（PERF-1）。
- **server tick 阻塞**：burst 期间无任何 "Can't keep up"（>2s 阈值）；日志中唯一一条
  出现在 23:43:06（服务器启动后 2 秒的世界加载抖动，早于全部认知查询）。
  旧理论 burst 4×~50ms 的单 tick 阻塞已消除——单查询执行 ~85–120ms 分摊到 4 个 tick。
- **队列顺序/取消可解释**：FIFO（ArrayDeque），队列上限 4（429 too_many_local_queries），
  deadline=mono+5000ms，过期查询在 tick 首先被 fail 回收（503 query_deadline_expired）
  且绝不执行——由 BridgeCoreTest `one expensive local query per tick` /
  `expired query never executes`（FakeBackend 计数）锁定。
- **ordinary task 仍有 tick 进展**：LIVE-2A01-5 中 gather 与本查询共存正常推进。

## 结论：PASS
