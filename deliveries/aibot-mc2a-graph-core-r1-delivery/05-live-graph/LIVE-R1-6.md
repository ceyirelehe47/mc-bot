# LIVE-R1-6 — 畸形图存储精确读取

**结论：PASS**

## 装置

对 LIVE-R1-5 的 DONE fixture bin（已备份 `.r1c5-done-backup`）截断最后 1 字节
（686→685）。该 bin 的最末字段恰为依赖串——JUnit 回归
`truncatedFinalDependencyStringFailsClosedEvenWhenPrefixIsAnotherValidNode` 证明的
"短读会让 aX 变 a"歧义场景的同构复刻。

## 结果（server-r1c-m.log）

```
Caused by: io.github.zoyluo.aibot.external.BridgeFault: task_graph_store_invalid
```

- 启动 fail-closed，**不会**静默加载被改变的 DAG ✓
- `read()` 强制 `readNBytes(n).length == n`（`EOFException("truncated_string")` →
  load() 捕获后以 503 task_graph_store_invalid 拒绝）✓
- 测试副本操作，生产存储未损坏（已恢复 pre-r1c5 备份）✓
