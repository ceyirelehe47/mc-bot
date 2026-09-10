# MC-1C-A R2.1 Reliability Repair｜交付结果

- 仓库：ceyirelehe47/mc-bot
- 分支：experiment/mc1ca-structure-semantics
- 起始 SHA：`10b5b480cda225367180043ca8868b725f071348`
- 上游冻结基线：`zoyluoblue/mc_aiplayer@a029fa6a3760fd0f83834c104051b041d986da60`
- DSH 基线：`deepseek-ai/deepseek-harness@5dda764ed3aa172535a7967b06ff95d9cbfe536a`
- 交付目录：`aibot-mc1ca-r21-delivery/`

## 1. 起始 SHA / 最终 SHA

- 起始：`10b5b480cda225367180043ca8868b725f071348`（与任务书要求的远端 HEAD 完全一致，无需 reconciliation）
- 最终：见 `00-meta/final-head.txt`（本提交自身）

## 2. 修改文件列表

`01-diff/stat.txt` 为完整清单（24 个 tracked 文件修改 + 3 个新增 GameTest +
14 个 external overlay 文件 + 1 个新增 HomeBlockEquivalence 亦在 overlay 内，overlay 合计 17 个）。
生产源码净变化：

| 文件 | 变化 | 内容 |
|---|---|---|
| `task/BuildTask.java` | +112/-13 | work-pose 语义修复、预算顺延、typed 诊断、stand 缓存 |
| `mode/ObservableWorldQuery.java` | +23 | 新增 `canObserveCellFrom`（planner 眼位严格同规则查询） |
| `action/FarmAction.java` | +19 | till/plant/harvest 接入 farm mask gate |
| `external/SemanticWorldRegistry.java` | 大改 | 状态机 v3、revalidation、pending pickup、stale tombstone、farm gate |
| `external/KnownResourceTask.java` | 大改 | PICKUP_RECOVERY 相位、UNREACHABLE 降级、stale 终态 |
| `external/HomeBlockEquivalence.java` | 新增 | dirt↔grass 窄等价 policy |
| `external/MinecraftBodyBackend.java` | 改 | mine_opportunity 状态分派 |
| `external/ExternalBodyRuntime.java` | 改 | `resource_opportunity_stale` 事件 |
| `src/test/.../PrivilegedBoundarySourceTest.java` | +7/-2 | 守卫断言更新为 R2.1 更强契约 |
| `src/gametest/.../MC1CAR21GameTests.java` | 新增 | 12 个 R2.1 GameTest |
| `src/gametest/resources/fabric.mod.json` | +1 | 注册 r21 测试类 |

## 3. HOME root cause 的精确解释

LIVE-R2-7 的 `target_timeout` 空转由**三层叠加**造成，逐层实测定位：

**层 1（主因）：work-pose 判据自举错误。**
`BuildTask.nearbyStand()` 的每个候选 stand 都要通过
`isObservableStandable`：要求 stand 的地面/脚/头三格**从 bot 当前眼位**可见。
HOME repair 是唯一 anchor 可能在远处的 BuildTask 入口（普通 build 的 anchor 永远在脚边，
上游从未暴露此缺陷）。当 missing cell 在结构另一侧/被墙遮挡时：

- `canObserveCell(target)` = false（目标不可见）；
- 所有 stand 候选（屋内、屋后）从**当前眼位**看同样被墙遮蔽 → `nearbyStand` 恒返回 null；
- `ensureObservableWorkPose` 的 fallback 分支此前对 `stand == null` **完全静默**（无 path、无日志、
  不累计 retryTicks），每 tick 空转；
- 唯一出口是 80t 预算的 `skipBuildTarget("target_timeout")` → `structure_incomplete`。

GameTest 复现（`r21HomeRepairWorkPoseSurvivesOccludedCorner` 的 fixture 前置断言）：
L 形走廊几何下目标格与全部 stand 候选的可观察性均为 false —— 与实机症状（held 不切换、
`path_idle` 恒 true、无 startPathTo 日志）逐点吻合。

**层 2（次要）：预算过紧。** 即使工作位可解，绕结构走到工作位需 10+ 格路线（≈75-90t），
固定 80t 的 target 预算会把"正常走路"误判为空转。

**层 3（诊断缺失）：静默 false。** `startPathTo` 的非 throttle 失败被丢弃、stand==null 无记录，
现场只能看到 80t 后的 `target_timeout`。

**关于 GameTest 的一个重要发现**：Fabric GameTest 的 fake player **实体不被 world tick**
（实测 `entity.age` 恒 0、`world.getEntity(uuid)` 为 null、`startPathTo` 成功后位置永不变化），
因此"走位"类验收必须在实机（LIVE）完成；GameTest 覆盖放置链与状态语义。测试方法若在主体内
直接 `complete()`，`runAtTick` 回调会被丢弃并静默通过 —— 本轮据此把异步断言改为
`runAtEveryTick` + 手动 `task.tick(bot)` 驱动（仓库既有 GatherPickup 模式）。

## 4. HOME 修复策略

- `isWorkPoseUsable(bot, candidate, target, reachSquared)` 取代 `isObservableStandable`：
  候选 stand 必须 (a) 可站立、(b) 站在该处眼睛到目标在交互距离内、
  (c) 经 `ObservableWorldQuery.canObserveCellFrom` 用**同样的严格 raycast 规则**证明目标格可见。
  **观测策略未放宽**（capability 矩阵、perception radius、raycast 参数全部不变），
  只是把评估眼位从"当前"换成"到达后"。
- 预算顺延：`isPathExecutorIdle() == false` 时 target/flatten 预算续期，真正空转仍由同窗口兜底。
- typed 诊断：`build_work_pose_state` / `build_target_not_observable` / `build_work_pose_blocked`
  （节流写入 L0/L1 journal，不 wake DSH），并在 40t 内无工作位时快速
  `fail("no_observable_work_pose_for_<phase>:<pos>")`。
- stand 搜索结果 20t 缓存（raycast 成本实测 60-105ms/tick 的 section profile 已消除）。
- 保留项全部复核：wrong 409 fail-closed、extra 不删、postcondition reverify、
  missing-only blueprint、strict observation policy（r21 测试 1/2/3/5 逐项断言）。

## 5. dirt/grass equivalence 策略

`HomeBlockEquivalence.equivalent(expected, actual)` —— 显式白名单
`minecraft:dirt <-> minecraft:grass_block`（仅此一对，代码注释明确禁止 tag/palette 家族匹配）。
接入 `SemanticWorldRegistry.integrity()` 与 `homeRepairPlan()` 两处判定：

- 等价格计为 matched（不计 missing / wrong），因此不会进 repair blueprint；
- baseline 保留原始 captured block id；若等价格之后真的缺失，
  repair 仍按**原始 id** 补回（LIVE-R21-2 实测：baseline=dirt 的格补回 dirt 而非 grass）；
- 已在漂移的格不被强改回（LIVE-R21-2 实测 grass 保留）。

## 6. Opportunity state machine

状态与语义（`SemanticWorldRegistry`，持久化 version 3）：

| 状态 | 触发 | 语义 |
|---|---|---|
| `ACTIONABLE` | 观测到 ore + 工具能力满足 | 可尝试（capability + 已知 geometry） |
| `BLOCKED` (insufficient_tool) | 工具能力不满足 | 工具到位后重估 |
| `UNREACHABLE` (no_reachable_work_pose) | `nearestReachableBlock == null` 实证 | 当前几何无合法工作位；非终态 |
| `MINED_PENDING_PICKUP` (pickup_recovery_pending) | ore 已破坏、inventory delta 未成立 | 恢复义务；跨 restart |

- **UNREACHABLE 不风暴**：unchanged observation 一律保持；仅当 bot 移动 > 6 格
  （相对判定位置）或 2400 game-tick 冷却后 `opportunityRevalidationReady()` 才允许重估，
  恢复 ACTIONABLE 时只推一次 `resource_opportunity_actionable`。
- **不唤醒 DSH**：降级只写 registry（持久化）+ typed failure reason，不产生事件。
- **STALE 终态**：目标位置被外部替换/消失 → `markOpportunityStale()` 移除并发布
  `resource_opportunity_stale` tombstone 事件（typed reason 如
  `externally_consumed_cell_replaced_with:<block>`），**从不当成收集成功**。

## 7. pending pickup 的 restart reconcile 逻辑

1. `KnownResourceTask.pickup()` 超时且 ore 已消失 → `markOpportunityPendingPickup()`
   （状态 + `pickup_recovery_pending` 持久化），`fail("known_resource_pickup_pending_recovery")`。
   R2 的 `markOpportunityConsumed` 早销账路径已删除。
2. 加载时状态原样恢复（version 3 字段 `state_since_game_time` / `state_x/y/z`，
   v1/v2 文件照旧可加载并在首次写入时迁移）。
3. `mc_mine_opportunity(id)` 遇到 MINED_PENDING_PICKUP → `KnownResourceTask(spec, true)`，
   进入 `PICKUP_RECOVERY` 相位：**绝不重新挖矿**，只做 forcePickup / chaseDrop /
   inventory delta 判定。
4. 完成条件：inventory delta > 0 → 销账 + `known_resource_collected(mode=pickup_recovery)`。
5. 保守失败：掉落物确认不在（连续 60 tick 无可拾取 ItemEntity 且无 delta）→
   `markOpportunityStale(...)` + `known_resource_pickup_lost_drop_despawned_or_taken`；
   超预算则 `known_resource_pickup_recovery_timeout`（pending 保留，可重试）。
6. 观测期保护：非矿石物块覆盖该格不会删除 pending 条目；`refreshOpportunityCapabilities`
   跳过 UNREACHABLE / MINED_PENDING_PICKUP 两个非工具态。

## 8. world mutation surface audit 结论

见 `05-audit/world-mutation-surface.txt`（逐调用点分类）与 `direct-mutation-review.md`。

- reserved external body 的可达直接 mutation 收敛为三类：
  ① MiningController 物理挖掘（BreakPolicy 每 tick 硬门，含 HOME/farm 保护）；
  ② FarmAction till/plant/harvest（**R2.1 新增 registered farm mask gate**）；
  ③ SAFETY origin 显式豁免（R1 生存网未动）。
- `placeWater`/`fillBucket`/`IrrigateTask`/`SleepTask`/`BuildAction.directPlaceFallback`
  在 reserved 模式**不可达**（创建点被 checkTool/checkAssignment 闸死，或 profile 排除），
  按任务书"不为想象路径过度修改"只在 audit 记录，不加 gate。
- R2.1 未新增任何裸 world 写入：HOME 放置仍走 BuildTask→BuildAction.placeBlockAt（原版交互）。

## 9. JUnit / compile 结果

```
./gradlew clean compileJava test   → BUILD SUCCESSFUL（351 JUnit，0 失败）
```
（一条既有 source-guard 单测 `PrivilegedBoundarySourceTest` 因 work-pose 契约升级同步更新，
新断言比旧断言更严：要求 work-pose 判定必须经过 `ObservableWorldQuery.canObserveCellFrom`。
原始输出：`02-build/gradle-clean-test.log`）

## 10. 两轮 GameTest required 数量与 0 fail 证据

```
old_required = 600
new_required = 14
total_required = 614
pass = 614
fail = 0
```

| 轮次 | 结果 | 原始日志 | XML |
|---|---|---|---|
| run1 | All 614 required tests passed | `03-gametest/run1.log` | `03-gametest/TEST-aibot-gametest-run1.xml` |
| run2 | All 614 required tests passed | `03-gametest/run2.log` | `03-gametest/TEST-aibot-gametest-run2.xml` |

新增 14 个（全部 r21 前缀，XML 中逐一列出；第 13/14 个是两轮独立复核后补的回归测试）：

| # | GameTest | 矩阵项 |
|---|---|---|
| 1 | `r21HomeRepairPlacesOneMissingBlockPhysically` | R21-A1 |
| 2 | `r21HomeRepairPlacesMultipleMissingBlocksPhysically` | R21-A2 |
| 3 | `r21HomeRepairStillRejectsWrongNonEquivalentCellAtomically` | R21-A3 |
| 4 | `r21HomeDirtGrassNaturalDriftIsEquivalentButMissingStillRepairable` | R21-A5 |
| 5 | `r21HomeRepairNeverDeletesExtraBlock` | R21-A4 |
| 6 | `r21HomeRepairWorkPoseSurvivesOccludedCorner` | R21-A1/A2（几何对抗） |
| 7 | `r21OpportunityBecomesUnreachableAfterNoWorkPose` | R21-B1/B2/B3 |
| 8 | `r21MinedWithoutPickupPersistsPendingRecovery` | R21-B4 |
| 9 | `r21PendingPickupCanRecoverWithoutRemining` | R21-B4/B5 |
| 10 | `r21StaleOpportunityDoesNotClaimInventorySuccess` | R21-B6 |
| 11 | `r21ReservedFarmMutationOutsideMaskIsRejected` | R21-C2 |
| 12 | `r21RegisteredFarmMutationInsideMaskStillWorks` | R21-C1 |
| 13 | `r21PickupRecoveryHonoursDropCollectedBeforeRecoveryStart` | R21-B5（第一轮复核缺陷回归） |
| 14 | `r21LegacyPendingWithoutBaselineNeverOverClaims` | R21-B4/B5（第二轮复核缺陷回归） |

未删除/禁用任何既有测试：594 旧 + 6 R2 = 600，全部继续通过（XML 可核）。

## 11. Node tests

```
node --test test/*.test.mjs   → # tests 34 / # pass 34 / # fail 0
```
原始输出：`02-build/node-tests.log`（口径与 R2 基线 34/34 一致，未减少）。
（互操作子测试需要 `dsh/.build` 装配生产 class + `bridge-tests/FakeBridgeServer.java`；
仓库内该桩未改动。）

## 12. LIVE-R21-1..5 逐项判定

| 项 | 判定 | 证据文件 |
|---|---|---|
| LIVE-R21-1 HOME repair 真实闭环（单格 + 3 格复验） | **PASS** | `04-live/LIVE-R21-1-home-repair.md` |
| LIVE-R21-2 dirt→grass 等价 | **PASS** | `04-live/LIVE-R21-2-home-equivalence.md` |
| LIVE-R21-3 Zombie opportunity 降级 + revalidation（复验轮含真实挖矿） | **PASS** | `04-live/LIVE-R21-3-unreachable-opportunity.md` |
| LIVE-R21-4 MINED_PENDING_PICKUP + restart recovery（复验轮含成功销账原始行） | **PASS** | `04-live/LIVE-R21-4-pending-pickup-restart.md` |
| LIVE-R21-5 Farm exact-mask gate（复验轮） | **PASS** | `04-live/LIVE-R21-5-farm-domain-gate.md` |

实机原始日志：`04-live/server-r21-live.log`（首轮）、
`04-live/server-r21-live-rerun.log`（复验轮，含多格 repair / R21-3 / R21-4 / R21-5）；
registry 快照：`04-live/registry-final-state.json`。

要点摘录：

- **R21-1**：单格与 3 格两场景各跑一次 `mc_repair_home` → 均
  `home_missing_only_repair_verified`；
  3 格场景逐格 RCON 复核命中（chest / oak_log / oak_log），integrity 1.0 / missing 0 / wrong 0，
  chest 4→3、oak_log 16→14（真实材料消耗），保护盒外 extra chest 完好；
  另实测材料不足 `missing_material: minecraft:chest` 与冲突
  `home_repair_v1_conflicting_cells:1` 两条 fail-closed 路径。
- **R21-2**：全结构 wrong=0（R2 时同世界因草蔓延持续 409）；
  补回 baseline 原始 id（dirt），已漂移的 grass 格保留不回写。
- **R21-3**：`known_resource_unreachable:no_reachable_work_pose` →
  registry UNREACHABLE + typed reason → 重复调用 409 拒绝（无僵尸）→
  几何改变（放工作位 + 移动 > 6 格）后同 id revalidation → **真实破坏 coal_ore**
  （`execute if block … air` 命中）；拾取阶段按 R2.1 语义进入 pending，
  掉落物消失后以 typed `known_resource_pickup_lost_drop_despawned_or_taken` 保守终态。
- **R21-4**：`known_resource_pickup_pending_recovery` + `MINED_PENDING_PICKUP`
  （含 `pickup_baseline`）持久化 → 完整 stop/restart → 状态存活 →
  **recovery 成功销账**（原始行：
  `ACTION event=known_resource_collected bot=Bob {mode=pickup_recovery, …, count=3}`，
  且全程 `mine_start`=0 证明未重挖）；另实测掉落物缺失时
  `known_resource_pickup_lost_drop_despawned_or_taken` 保守终态（不伪装 collected）。
- **R21-5**：`mc_tend_farm` 在注册 5 格 mask 内真实 harvest+补种；mask 外成熟小麦
  零改动（`farm_mutation_outside_registered_mask` gate 生效）。

## 12b. 独立验收与其后的修复（重要）

本轮交付先经 subagent 独立验收，验收给出 **PARTIAL PASS** 并指出若干问题；
其中一条是真实正确性缺陷，已修复并重新完成全套验证：

| # | 验收发现 | 处置 |
|---|---|---|
| 1 | **[正确性] pickup recovery 的 inventory 基线取在 recovery 启动时刻**：vanilla 自动拾取常发生在"pending 判定 → recovery 启动"之间，该基线下 delta 被吞掉，资源已入包却被误报 `lost_drop_despawned_or_taken` 并错误终态化（实测命中） | **已修复**：pending 持久化 `pickup_baseline`（矿石被破坏时刻的 accepted 计数），恢复改用该基线；drop-absence 窗口 60t→200t、搜索半径 8→16 格，并加"走向可见掉落物"步骤；新增 GameTest `r21PickupRecoveryHonoursDropCollectedBeforeRecoveryStart` 锁定；实机复验通过（见 LIVE-R21-4 第 5 步） |
| 2 | **[证据] 首轮 LIVE-R21-1 未真正覆盖多格**（`0/3` 记录仅存在于更早的 R2 轮） | **已补测**：复验轮真实构造 3 格 missing 并完成修复（逐格 RCON 复核 + 材料消耗），文档与本节同步更正 |
| 3 | **[证据] 首轮 LIVE-R21-4 无 recovery 成功销账证据**（两次均在 61t 报 lost_drop） | **已补测**：复验轮取得 `mode=pickup_recovery` 成功原始行（见上）；缺陷 1 修复前不可能产生该证据 |
| 4 | **[可维护性] `build_target_not_observable` 等诊断在 retryTicks 不增时逐 tick 刷屏** | **已修复**：诊断节流改用独立 tick 计数器（40t 窗口），与 retryTicks 解耦 |
| 5 | **[可维护性] 预算顺延可能放大空转**（path 长期不 idle 时不断顺延） | **已修复**：顺延累计上限 600t，超限仍按 `target_timeout` 结束 |
| 6 | **[边界] `isWorkPoseUsable` 先做原始 Standability 读、后做观察证明** | **已修复**：改为先 `canObserveCellFrom` 证明可见、再做原始 standability 读（保持上游"观察在前"的顺序约束） |
| 7 | **[边界] 容量淘汰可能淘汰 MINED_PENDING_PICKUP 恢复义务** | **已修复**：`evictOpportunityIfNeeded` 排除 pending 条目 |
| 8 | **[文档] overlay 文件计数口径小差** | **已更正**：§2 与本文 12c 使用准确口径（overlay 17 个 = 14 external + 3 gametest） |

修复后重跑全部验证：`clean compileJava test` 全绿、`runGameTest` 两轮 **614/614**、
Node **34/34**、安装器在干净 a029fa6 上**重放内容 0 diff**、LIVE-R21-1..5 全部复验 PASS。

## 12c. 第二轮独立复核与其后的跟进修复

第二轮复核（针对 12b 的修复）给出 **PASS（附必办跟进）**，指出 3 个新代码问题与 4 个文档/证据问题，已全部处理：

| # | 复核发现 | 处置 |
|---|---|---|
| 1 | **[代码] 恢复接近路线越权**：新增的"走向掉落物"用裸 `startPathTo(dropPos)`（可挖/可搭、无终点校验），与仓库既有的保守掉落物接近实现相悖 | **已修复**：改用 `HarvestCore.chaseDropAnyOf`（exact surface movement，不挖不搭，只接近物理支撑的掉落物） |
| 2 | **[代码] "不可观察"被当作"已消失"**：200t 内 strict LOS 看不到即终态化并删条目，而 vanilla 掉落物存续约 6000t，被地形遮住的掉落物会被永久放弃 | **已修复**：改为先返回可重试的 `known_resource_pickup_recovery_drop_not_found`（pending 保留），仅当 pending 年龄超过 6000t 才 `markOpportunityStale` |
| 3 | **[代码] 未知基线可能虚报成功**：9f942a4 写出的 version=3 文件没有 `pickup_baseline` 字段，回退 0 时若背包已有同族物品会立即算出 delta>0 | **已修复**：缺字段加载为 `-1`（未知哨兵），恢复时回退"当前计数"基线——只会少认、绝不虚报；新增 GameTest `r21LegacyPendingWithoutBaselineNeverOverClaims` |
| 4 | **[证据] LIVE-R21-1 多格引用错文件**（rerun log 窗口不含 repair 段） | **已修复**：从服务器归档 `logs/2026-09-10-5.log.gz` 提取 `server-1708-multicell-repair.log` 并交付（含 3 条 `event=place` + `task_completed elapsed_ticks=65`） |
| 5 | **[证据] LIVE-R21-2 引用的日志无对应命中** | **已修复**：文档改为指向正确文件（单格轮 `server-r21-live.log`、多格轮 `server-1708-multicell-repair.log`）与 journal executions |
| 6 | **[表述] "mine_start 计数 0" 与全场次事实不符** | **已修复**：改为"该恢复 execution 内 0 次"，并说明同场次另一颗 ore 的 APPROACH 会产生独立事件 |
| 7 | **[低] flatten 相位顺延无上限 / `withState` 死代码 / audit 行号过期 / overlay 与工作区 EOL 不一致** | 前两项已修（flatten 加同一 600t 上限、删除死代码）；audit 行号与 EOL 口径已更新说明 |

跟进轮新增实机证据：`04-live/server-r21-live-followup.log`
（pending `pickup_baseline=4` 持久化 → restart → recovery
`known_resource_collected {count=2, mode=pickup_recovery}`、该轮 `mine_start`=0）。

## 13. 新发现缺陷（如实记录）

1. **[环境/测试] GameTest fake player 不被 world tick**：`entity.age` 恒 0、
   `startPathTo` 后位置不变、`world.getEntity(uuid)` 为 null —— 走位类语义无法在
   GameTest 内验证；本轮以"GameTest 覆盖放置链/状态机 + LIVE 覆盖走位"分工，
   并在测试注释与 RESULT 中显式说明。
2. **[测试框架] runAtTick 回调可被静默丢弃**：测试方法主体若直接 `complete()`，
   已注册的 `runAtTick` 断言不会执行、测试仍报 PASS（本轮一度产生 5 个假 PASS，
   经 spawn/despawn 时间戳与探针打印定位）。已改为 `runAtEveryTick` 内断言+收尾。
3. **[性能] BuildTask stand 搜索的 raycast 成本**：修复前每 tick 60-105ms
   section profile（`task_tick` 慢段）；已加 20t 结果缓存，实机 profile 恢复。
4. **[语义观察] 机会的 `seen_from` 不会随 re-observe 更新**（沿用 R2 行为）：
   远处机会被感知刷新时 last_seen 更新但 seen_from 保持首次，可能让
   `known_resource_far_use_mc_goto_seen_from` 指向旧位置。本轮未改（超出 R2.1 范围），
   记录待 MC-2A 或后续轮次评估。
5. **[实机环境] 隔离服自然地形**：Bob 曾在测试区外死亡一次（冒险性 tp 导致摔落），
   背包清空后重新配装；该事件与 R2.1 代码无关，已从场景复盘中排除。
6. **[记录] 首轮交付的日志指向不精确**：`server-r21-live.log` 只覆盖 16:05–16:10 段，
   而单格 repair 的证据落在服务器滚动归档中；复验轮已把多格 repair 与
   R21-3/4/5 的完整原始日志写入 `server-r21-live-rerun.log`（验收后问题 2/3 的修复动作）。

## 14. 明确 deferred 到 MC-2A 的内容

按任务书第 2.3 节，本轮**未实现**（也未提前铺设运行时）：
TaskGraphStore、GraphFragment/GraphProducer、AutonomyScheduler、resource reservation ledger、
scheduler priority classes、automatic HOME/Farm/Opportunity producer、Body→DSH 新 Attention Policy。
仅保留干净 seam：UNREACHABLE 的 bounded revalidation 与 pending pickup 的恢复义务
都已具备可被上层调度消费的 typed 状态。

## 附：交付物与可复现性

- `00-meta/`：base-head、environment、git-status-final
- `01-diff/`：stat.txt、patch.diff、replay-verify.log、replay-gametest.log
  （**安装器重放验证**：干净 a029fa6 上 `apply_to_aibot.py --apply` 重放，
  与本轮工作区逐文件内容 0 diff（EOL 归一化后即完全一致）；
  重放后 `clean compileJava test` + `runGameTest` 614/614 再次通过）
- `02-build/`：gradle-clean-test.log、node-tests.log
- `03-gametest/`：run1/run2.log + TEST-*.xml
- `04-live/`：LIVE-R21-1..5 + 原始服务器日志 + registry 快照
- `05-audit/`：world-mutation-surface.txt、direct-mutation-review.md
- 安装器/overlay 更新：`aibot-dsh-m0/scripts/apply_to_aibot.py`（新增 4 文件锚点规则）、
  `aibot-dsh-m0/aibot-overlay/`（新增 HomeBlockEquivalence.java、MC1CAR21GameTests.java，
  刷新 external 4 文件）

密钥零泄漏：本目录与提交不含任何 token/key（桥 token 仅存在于隔离服本地
bridge-token.txt，DSH/CommandCode key 不在交付物内）。
