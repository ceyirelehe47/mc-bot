# LIVE-2A01-3｜lazy inspect

环境同 LIVE-2A01-1（HOME `live2a01`，baseline=4 cells，其中 1 missing）。

## 实机行为验证（脚本采样）

连续 20 次 `mc_view`（间隔 200ms，覆盖 ~80 server tick，模拟 200-tick 刷新窗口）：

- **scene 全文从不携带任何 inspect detail 标记**：`missing_sample` / `block_histogram` /
  `cells_sample` / `seen_from` / `pickup_baseline` 全部 0 命中；
- structure 卡是 compact 形态：卡片键 = evidence_ref/kind/object_id/role/knowledge/
  freshness/summary，summary 仅 baseline_cells + current_integrity；
- 20 次 view 的 distinct scene_hash = 2（世界真实语义变化——远处实体计数波动所致，
  时钟不改变 hash）。

随后**显式 inspect baseline 一次**：

```
baseline materialized: baseline_cells=4 has_histogram=True missing_count=1
```

inspect 之后继续采 view：scene_hash 不变（inspect 物化不污染 view）。

## 计数器（instrumentation）证据——test-only，引用测试套件

`CognitiveInspector.BASELINE_MATERIALIZED` / `HOME_REPAIR_PLAN_CALLS` 为 test-only
静态计数器（生产零输出，无法从实机 JVM 外部读取），其计数断言在：

- **BridgeCoreTest（离线内核套件，70→77 checks）**：5 次 kernel tick + view 后
  `materializes` 增量 == 0（view 刷新零物化）；一次 `submitInspectQuery(baseline)` 后
  增量 == 1（单次物化）；
- **JUnit source-contract**：`CognitiveViewBuilder` 不含 `CognitiveInspector.buildIndex`
  调用、不含 `homeRepairPlan`（周期快照绝不触发，LAZY-2）；
  `homeRepairPlan` 仅出现在 `structureBaselineDetail` 之后（显式 baseline 路径独占）；
- **GameTest**：`mc2a01InspectIsLazy…` 系列由 mc2a0/mc2a01 批次的编译期与行为断言共同覆盖。

（任务书 §8 LIVE-3 允许 instrumentation test-only；实机为行为级验证 + 引用上述计数断言。）

## 结论：PASS
