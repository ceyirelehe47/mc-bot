# MC-2A0.1｜Cognitive Evidence Boundary Repair — RESULT

## 0. 总览

| 项 | 值 |
|---|---|
| base | `6bd9d7099b72573ff42a7c952e1f407109aeb7f3`（experiment/mc2a0-cognitive-view） |
| final | `254d204…`（本分支头，完整 SHA 见 00-meta） |
| branch | `experiment/mc2a01-cognitive-evidence-boundary` |
| 外部验收 | MC-2A0 判定 PARTIAL PASS；本轮修复全部 6 项缺陷 |

## 1–2. base/final SHA、branch

- 开工时 `origin/experiment/mc2a0-cognitive-view` == `6bd9d70…` 已验证；从 exact base
  新建分支，无 merge master、无 force push。
- final SHA：本提交（交付物与组件更新随同入库；`00-meta/`）。

## 3. remote structure current-integrity policy

`StructureKnowledge` v0.1（`external/cognition/StructureKnowledge.java`，新文件）：

1. bounds 粗筛（纯几何零世界读）：bot 到 bounds 最近点 >16 格 ⇒ 不扫描任何 cell；
2. 粗筛内逐格证明（`canObserveBlock || canObserveCell`；canObserveCell 允许空目标格，
   否则被破坏的 missing cell 永不可验）——任一格不可证明即整体放弃，绝不部分读取；
3. 全部证明通过才逐格 `getBlockState` 统计（proof before read）；
4. 呈现：VERIFIED_LIVE（真实数字）/ LAST_KNOWN（上次合法验证数字 + verified_game_time，
   绝不当当前真相）/ UNKNOWN（仅 reason，missing/wrong 输出 null 不伪装 0）。
   cognition 进程内存缓存 20-tick 窗（不写 registry/世界，READ-1）。

## 4. structure 字段的 durable vs live-proof 划分

durable（注册/捕获事实，永可输出）：object_id、role、dimension、bounds、
baseline_cells（及 inspect baseline 档的 block_histogram——registry 基线快照派生）。
需 live proof：current_integrity 的 matched/missing/wrong、missing_sample
（homeRepairPlan 仅在"显式 baseline inspect + 当前可 LIVE 验证"下可达）。

## 5. proof-before-read 精确源码路径

- `CognitiveInspector.inspectLocalJson`：`canObserveBlock` 先于 `getBlockState`
  （源码顺序即契约，JUnit `cognitiveLocalScanProvesObservabilityBeforeBlockRead` 锁定）。
- `StructureKnowledge.verify`：证明循环先于读取循环（同一守卫覆盖）。

## 6. mc_view 是否仍 materialize inspect details

**否**。Snapshot 的 inspectIndex 是 `Map<String,EvidenceDescriptor>`（ref/kind/
objectId/role 轻量句柄）；`CognitiveViewBuilder.build` 不调 buildIndex/homeRepairPlan
（JUnit source-contract + BridgeCoreTest materializes==0 锁定；实机 20 次 view 零
detail 标记）。

## 7. inspect 如何 on-demand 执行

HTTP fail-fast（ref 解析 400 / descriptor 404 / 档位白名单 400 / 队列 429）→ 与
inspect-local 共享的 server 线程查询队列（每 tick ≤1，deadline 5s）→
`BodyBackend.materializeEvidence(ref, detail, snapshot.gameTime)` →
`CognitiveInspector.materialize` 单 ref+单档物化 → HTTP 无锁 future.get(5s) 包回
mc.evidence.v0。HTTP 线程从不触碰 Minecraft 状态。

## 8. local query 每 tick budget

每 tick 至多 1 个 expensive 认知查询（materialize 与 inspect-local 共享队列与预算；
选择"一 tick 一个"——比 wall-clock budget 更简单可审计）。队列上限 4。

## 9. timeout/cancel 后队列回收

LocalQuery 带 deadline（mono+5000ms，与 HTTP get(5s) 对齐）；tick 先回收过期项
（future.completeExceptionally(503 query_deadline_expired)，已完成的不重复完成），
已超时放弃的查询绝不执行（BridgeCoreTest：expired 后 FakeBackend 执行数不变）。

## 10. opportunity freshness 修复

`freshnessOf(lastSeen, -1L)` 的两处（summary card / evidence detail）改为传入
snapshot gameTime（kernel 把 cognitive.gameTime 交给 materialize）——与 view 卡同源。
实机：三处一致 RECENT（GameTest `mc2a01OpportunityInspectFreshnessMatchesView`；
旧机会按真实老化显示 STALE）。

## 11. IDLE/current_task 修复

`CognitiveViewBuilder.executionSection`：无 active execution 时 current_task 显式 null
（不再透传 TaskManager.status 的残留任务名）。实机：IDLE⇒null、RUNNING gather⇒
current_task=gather 且 progress_bucket 0→25 推进。范围说明（有意分叉）：
`observeJson` 的 current_task 字段为 mc_observe 的服务器权威视图，未随轮修改。

## 12. exact 测试计数

- GameTest required：**627**（623 基线全保留 + 4 新增：
  mc2a01RemoteStructureNeverClaimsLiveIntegrity、mc2a01NearObservableStructureCanClaimLiveIntegrity、
  mc2a01OpportunityInspectFreshnessMatchesView[批 mc1caR21]、mc2a01IdleViewClearsCurrentTask）
- JUnit：**359**（357 + 2 source-contract：
  cognitiveLocalScanProvesObservabilityBeforeBlockRead、cognitiveInspectIsLazyAndStaysInsideEvidenceScope；
  原 cognitiveViewStaysAReadModel 扩展 StructureKnowledge 文件与既有断言）
- Node：**37**（协议形状不变，FakeBridgeServer 适配 descriptor+materialize）
- BridgeCoreTest：**77**（70 + 7：inspect 异步化、一 tick 一个、过期不执行、
  view 刷新零物化、单次物化=1 等）
- 8 个 required 测试的 suite 分配（任务书 §7 允许）：1/2/5/6=GameTest；
  3(proof-before-read)=JUnit source-contract+GameTest 遮挡行为（既有 test6）+LIVE-2；
  4(lazy)=JUnit source-contract+BridgeCoreTest 计数+LIVE-3 行为；
  7/8(kernel 预算/取消)=BridgeCoreTest（FakeBackend 计数）。

## 13. GameTest x2

两轮全绿：round2（主仓库，`03-gametest/TEST-round2.xml` + round2-server.log）627/0；
重放轮（干净 a029fa6 应用安装器后，`TEST-replay.xml`）627/0。
（开发过程中第一轮亦 627/0，其产物目录被后续轮次覆盖，未入档。）

## 14. replay exact count

安装器重放（干净 `a029fa6`）→ 逐文件 0 diff（62 文件 LF 归一对比）→
重放产物全套测试：GameTest 627/0、JUnit 359/0（`01-diff`/`03-gametest`）。
test_installers 11/11 OK（CHANGES 保持 22，本轮 cognition 改动全部走 overlay 26 文件
+ EXTRA_CHANGES 2 规则更新）。

## 15. LIVE-2A01-1..6

全部 PASS，见 `04-live/`：

1. remote structure freshness：近 LIVE(matched=4) → 远+RCON 破坏 → LAST_KNOWN+旧值
   missing=0（真实 1 不泄漏）+verified_game_time → 回包络后 LIVE missing=1 ✓
2. proof-before-read：墙后 diamond 遮挡不输出/暴露输出 + 源码顺序守卫 ✓
3. lazy inspect：20 次 view 零 detail 泄漏 + 单次 baseline 物化 + inspect 后 hash 不变；
   计数证据=BridgeCoreTest/GameTest/JUnit（test-only instrumentation，任务书允许）✓
4. query budget：4 并发 burst wall 388/396ms、spread ~140ms（跨 tick 分散，非单 tick
   drain）；burst 期无 tick 停顿告警 ✓
5. execution+freshness：IDLE⇒null / RUNNING⇒gather / 三处 freshness=RECENT 一致 ✓
6. evidence hygiene：sanitize 幂等 + 全树扫描 CLEAN + token 轮换验证 ✓

## 16. 性能数据（实机，world_play）

| 查询 | p50 | p90 | max |
|---|---|---|---|
| mc_view | 10.0ms | 22.1ms | 48.2ms |
| mc_inspect | 50.1ms | 52.9ms | 196.8ms |
| mc_inspect_local r=8 | 85.1ms | 116.6ms | 123.3ms |
| mc_inspect_local r=4 | 53.1ms | 61.3ms | 64.4ms |
| 4 并发 local burst | — | — | wall 388–396ms（分散于 4+ tick） |

说明：view 比MC-2A0（~15ms）更快（不再预计算）；inspect 的 ~50ms 主体是排队到下一
tick 的预算代价；**local 因 proof 覆盖空气格比 MC-2A0 的 47.8ms 慢 ~1.8×**——按任务书
§9.4 处置：已明确记录，MC-2A1 不得自动高频调用 local inspect，后续优先复用 resident
perception evidence。

## 17. evidence secret scan

`05-audit/secret-scan.txt`：delivery + installer 树 230 处命中全部为
OK-REDACTED / OK-CODE/DOC（源码标识符、日志字段名 api_key_missing、文档说明），
REAL-VALUE 命中 0。sanitize 脚本幂等（复跑 0 redaction）。

## 18. 旧 DSH web token

**VERIFIED-ROTATION**：MC-2A0 交付 log 中的 token 属于已终止实例（DSH web token 为
每实例内存态随机值，无持久化）；本轮启动新实例取得不同 token 后立即终止，证明
per-instance 轮换语义。不重写历史；旧交付文件在本轮提交中已 redact（历史提交保留）。

## 19. 是否引入 Agenda/TaskGraph/Scheduler

**否**。cognition 六文件 symbol 扫描为 JUnit 守卫之一（cognitiveInspectIsLazyAndStaysInsideEvidenceScope）。

## 20. known defects / deferred（scope_delta）

- **local inspect 变慢**（见 §16）：strict proof 覆盖空气格的代价；后续方案=复用
  resident perception（契约 §3.2 的推荐路径），留待 MC-2A1 之前的性能轮。
- **observeJson 的 current_task**（P1-4 范围外，有意分叉）：view 已修，observe 的
  同名字段保持服务器权威语义，未动（任务书"不要顺手增加复杂 execution history"）。
- **LAST_KNOWN 为进程内存态**：重启后降级为 UNKNOWN（保守方向，符合 v0.1）。
- **大结构 LIVE 验证成本**：全部 baseline cells 逐格证明对大建筑较贵（20-tick 缓存
  摊薄；粗筛外的结构零成本）。strict 六面射线对斜角格的天然不可见使宽墙难以一次
  全证——这本身是 v0.1 保守策略的一部分（测试 fixture 用正对石柱验证正路径）。
- **MC-2A0 遗留**（非本轮范围）：机会 seen_from 不随 re-observe 更新；provenance
  三渠道实测待真人开服；GameTest 时钟稳定性测试的并行环境限制（等价断言+LIVE 覆盖）。
- 下一大轮 MC-2A1（Agenda/TaskGraph/Scheduler）需遵守 §16 的 local-inspect 频率约束。

## 附加事实

- 上游 a029fa6 tracked 文件零改动（全部 cognition 改动走 overlay/EXTRA_CHANGES；
  重放 0 diff 证明）。
- master 未动；分支无 merge。
- 测试期间的调试输出已全部移除（源码 grep 零残留）。
