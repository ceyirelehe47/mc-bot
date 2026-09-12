# 审计 — 范围(scope-audit)

production.patch 共 4 文件(01-diff/production.patch, 319 行):

1. `SemanticWorldRegistry.java` — 两个任务书补丁的主体 + journal 绑定生命周期修正(见
   lifecycle-order.md 修正 1)。
2. `ExternalBodyRuntime.java` — 启动对账改名(reconcileOpportunityLifecycleReceipts) +
   resourceOpportunityStale 返回 boolean。
3. `OpportunityBirthDurabilitySourceTest.java` — 新增源码契约测试(5 个, 任务书 0002 原样)。
4. `MC1CASemanticsGameTests.java` — gt7 失败消息补 cause(可观测性)。

## 未触碰(任务书 §2 保留清单逐项)

- 九状态模型/状态机: 未触碰。
- dispatch 路径 graphRunNext→submit: 未触碰(dispatch-path.md)。
- restart RUNNING→SUSPENDED/no blind replay: 未触碰; LIVE-R12-1 的原图 FAILED 正是该
  语义的正确产物(跨重启执行作废, 见 LIVE-R12-1 披露)。
- durable consumed=成功权威 / durable stale=终态负权威 / registry 缺席=UNKNOWN: 未触碰。
- R1.1 化身 id 格式(ore_12hex_16hex)与 findActiveOpportunityAt 复用: 未触碰(LIVE 三门
  实证同一格三代化身 fefc057feb30 前缀一致)。
- 树安全/重入: 未触碰。
- DSH 工具恰 29: dsh-plugin 零改动(12 显式 mc_* + 14 operation 循环 + pause/resume/cancel,
  与 R1.1 同法计数)。
- 无 Agenda/后台 Scheduler/自治 producer/Real Client/BotView/第二生命周期库/图 schema
  改动/另一种机会 id 格式: 无(production.patch 全量可核)。

## 配套修正清单(§4 允许范围核对)

| 修正 | 类别 | 核 |
|---|---|---|
| git apply 需 `--recount -C1 --ignore-space-change`(假行号窄上下文+一处 preimage 多一空格) | minimal patch-context repair | PATCH_VALIDATION 预告同风格; 空白差异字节级定位 |
| 0002 hunk2(markOpportunityStale)git apply 拒收, Edit 工具按补丁 +侧逐行落地 | minimal patch-context repair | 语义与补丁逐字一致 |
| journal 绑定生命周期(clearRuntime 不清, start 比较 server) | 超出字面允许, 如实记录 | 补丁意图补完整; 不改 §3 语义; GameTest 9 例失败为其直接根因 |
| MC1CASemanticsGameTests.java 文件名对齐 git 索引 | minimal patch-context repair | 干净重放可编译 |
| gt7 cause 打印 | 测试可观测性 | 断言未动 |
| SHA256SUMS 重生成 151 项(+新测试文件) | manifest regeneration | installer 11 复跑 OK |
