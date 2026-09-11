# 审计 — 图权威边界(TG-Authority)

任务书强制审查点 1/6/8 与不变量 SCOPE/TG-PLAN。

## 图不是规划器

- 唯一创建入口 `TaskGraphStore.planOpportunity(planKey, ref)`,由
  `BridgeKernel.graphPlanOpportunity` 包装:
  - `requireLease` + `requireReady` + `identifier(planKey)`;
  - ref 必须解析为 `kind=opportunity`(`graph_plan_requires_opportunity_ref`);
  - ref 必须存在于**当前**认知视图索引(`cognitive.inspectIndex()`,
    `evidence_ref_not_in_current_view`)。
- 无任何后台事件 → 图创建路径:事件传输(events.mjs)只投递注入/转向,
  不触图 API;TaskGraphStore 无定时器/事件订阅面。
- 本轮未实现 Agenda/AutonomyScheduler/HOME/Farm 生产者(grep 无此类 producer);
  `createFragment` 为 private seam,注释明示"不作为 LLM 工具暴露"。
- 决策权在 LLM:计划/分派/取消全部为显式工具调用,G-LIVE-1/2/3/7 实机验证
  (含负路径 404/400/409)。

## DSH 工具语义(审查点 6/7)

- `mc_graph_plan_opportunity`/`mc_graph_inspect`:不设 autoWake、不 concludeTurn、
  不占执行槽——Node 测试 `f.concludes===0 && f.submissions.length===0` 锁定;
  实机会话中模型同回合连续 plan→inspect→汇报证实。
- `mc_graph_run_next`:`b.autoWake=true` 且仅当 `execution.state ∈
  {accepted,running,paused}` 才 `concludeTurn()`(plugin.mjs),Node 测试锁定
  (outcome_unknown 不结回合,模型须自查)。

## 两提交分离(审查点 8)

- Stage A = `c91b8cd`(仅树闭包:installer 规则+GameTest+契约测试+SHA256SUMS);
- Stage B = `d94e885`(图核心 13 文件),父提交即 Stage A;
  `git diff 0bff936..c91b8cd` / `c91b8cd..d94e885` 分别归档于 01-diff/。
