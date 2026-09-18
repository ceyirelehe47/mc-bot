# MC-2A0.2 审计报告 — TREE_ACCESS / ownership / cleanup 调用点全链

审计对象：生产 commit `2359502`（合并树 = 上游 `a029fa6` + installer 产物）。
所有结论附 文件:行号 证据；grep 命令可从本仓库 `aibot-dsh-m0` 重放。

## 1. TREE_ACCESS placement 全部调用点

`BuildAction.placeBlockAt` 在 tree 相关生产代码中**只有一处**调用：

- `external/TreeHarvestWorkset.java:500`（`placeOneSupport`，由 `tickAccess:330` 经
  `:360`/`:387` 进入）。

放置前置检查（`placeOneSupport`，:470-518）：

1. `:471` 若已可 `HarvestCore.canReach` + `ObservableWorldQuery.canObserveBlock` 即
   READY，不再放支撑；
2. `:475-477` 已放置支撑数上限 `MAX_TEMP_SUPPORTS=12`；
3. `:480-481` 放置格必须为空气且无流体；
4. `:483-484` 放置格过 `BreakPolicy.decide`；
5. `:486-490` 头顶两格净空 + 无流体；
6. `:491-495` `MaterialPalette.pickPathSupportBlockSlot` 选中后 equip；
7. `:497-499` `FakePlayerMotion.jumpTo` 上跳；
8. `:500` `BuildAction.placeBlockAt` 真实放置；失败则 `:502` 回跳并 BLOCKED；
9. `:505-509` 放置后核实方块确实存在（vanilla 假成功 → DEBT）；
10. `:511-512` **成功之后**才记录 `TemporarySupport(pos, blockId, ownerExecution,
    purpose="TREE_ACCESS", originalState, placedAt, PLACED)` 收据。

grep 证明（合并树 `src/main/java`）：

```
$ grep -rn "placeBlockAt" external/ task/GatherQuotaTask.java
external/TreeHarvestWorkset.java:500:        ActionResult placed = BuildAction.placeBlockAt(bot, place);
```

## 2. owner_execution 来源链

```
BridgeKernel.java:364          backend.start(e.id, e.operation, e.arguments)
  └─ MinecraftBodyBackend.java:121  start(String executionId, String operation, String raw)
       └─ :151  case "gather" -> new GatherQuotaTask(target, count, executionId)
            └─ GatherQuotaTask.java:124  构造器保存 externalExecutionId（:70 字段，:127 判空）
                 └─ :1194-1196  TreeHarvestWorkset.acquire(bot, targetPos, externalExecutionId)
                      └─ TreeHarvestWorkset.java:175-196  acquire 校验非空 → 写入 ownerExecution
                           （:163 构造，:132 字段；receipt :511 携带同一 ownerExecution）
```

- `BodyBackend.java`（overlay）新增 3 参 `start` 为 **default** 方法（转发旧 2 参），
  Fake/legacy backend 不被强制重写（`FakeBridgeServer.java:49` 等仍只有 2 参实现）。
- origin reason 前缀 `external_dsh:<executionId>`（`MinecraftBodyBackend.java:241` 附近
  TaskOrigin 构造），日志侧 `tree_workset_acquired/tree_support_placed/tree_support_removed`
  均带 `execution=<id>`（本轮 GameTest server log 实测可见）。

## 3. automatic support removal 全部调用点

支撑移除**只存在于** `TreeHarvestWorkset.tickCleanup`（:391-468），真实挖掘经
`MiningController`（:440-451），下降经 `FakePlayerMotion.stepToStandable`（:457）。
调用入口：

- `GatherQuotaTask.java:1024-1032`（TREE_CLEANUP phase 每 tick 驱动；DEBT→fail
  `tree_cleanup_debt:*`，BLOCKED→fail `tree_cleanup_blocked:*`）。

每块移除前的复核（全部 fail → typed debt，绝不盲目删除）：

| 检查 | 位置 | 失败原因串 |
|---|---|---|
| 当前 block id 必须与 receipt 完全一致 | :407-411 | `tree_cleanup_support_conflict:<pos>`（CONFLICT 态） |
| `BreakPolicy.decide` 仍允许 | :412-413 | `tree_cleanup_protected:*` |
| bot 站在该支撑正上方，或 no-dig/no-pillar surface route 回到上方 | :415-433 | `tree_cleanup_stand_not_available` / `tree_cleanup_owned_support_unreachable` |
| 移除后安全下降（下方有支撑、无流体、落地柱净空） | :437-438 + :565-580 | `tree_cleanup_not_above_support` / `tree_cleanup_fluid_below_support` / `tree_cleanup_no_safe_landing_support` / `tree_cleanup_landing_column_blocked` |
| 挖掘真实成功且方块消失 | :445-455 | `tree_cleanup_mining_failed:*` / `tree_cleanup_block_still_present` |
| 下降一步成功 | :457-459 | `tree_cleanup_descent_failed:*` |

外部已移除的 receipt（空气）→ `REMOVED_EXTERNALLY` 记账（:402-405），不误删。

## 4. 无 "nearby dirt/cobble cleanup" 推断路径（grep 证明）

```
$ grep -rn "nearby.*dirt\|nearby.*cobble" external/ task/GatherQuotaTask.java
external/TreeHarvestWorkset.java:41: * and never scans nearby dirt/cobble to infer ownership.</p>   ← 仅 Javadoc 声明
$ grep -n "isOf(Blocks.DIRT)\|isOf(Blocks.COBBLESTONE)\|BlockTags.DIRT" external/TreeHarvestWorkset.java
（0 命中 — cleanup 按_receipt 精确坐标+blockId_对账，从不按材质/邻域扫描）
```

## 5. generic `PathExecutor.PILLAR_UP` 未被 tree cleanup 接管（grep/diff 证明）

```
$ git diff a029fa6 --stat -- src/main/java/io/github/zoyluo/aibot/pathfinding/PathExecutor.java
（空 — 0 改动）
$ grep -c "TreeHarvestWorkset\|TREE_ACCESS" src/main/java/io/github/zoyluo/aibot/pathfinding/PathExecutor.java
0
$ grep -n "PathExecutor" external/TreeHarvestWorkset.java
（0 命中 — workset 不引用、不修改、不清理 generic path 支撑）
```

JUnit 契约锁定：`TreeHarvestSourceContractTest.treeAccessIsExplicitlyOwnedAndDoesNotHijackGenericPillarInfrastructure`
断言 `PathExecutor.java` 源码同时不含 `TreeHarvestWorkset` 与 `TREE_ACCESS`。

## 6. quota / TREE_COMPLETE / pickup 关键语义锚点

- quota guard：`GatherQuotaTask.java:219` `countSoFar >= targetCount && treeWorkset == null`
  （JUnit 契约锁定 :26-27）；progress 上限 0.99（:148）。
- TREE_COMPLETE：`TreeHarvestWorkset.treeComplete():226-229` =
  `logsResolved() && !hasTemporarySupports() && pickupsReconciled() && cleanupDebit 空 && abandonReason 空`。
- pickup obligation 只在 `noteHarvested`（:297-306，注释明确 "only after the gather task
  has already entered HARVEST"）建立；GatherQuotaTask 侧调用点 `:822`（真实 HARVEST
  路径内）；两种 proof（inventory delta / `Stats.PICKED_UP`）沿用 R2 语义（:950-951）；
  bounded loss 走 `resolvePickupLoss`（:897-901），显式 LOST 不静默。
- 冻结候选集：reconcile（:257-277）只对 frozen cells 对账；FREEZE 由 GameTest
  `mc2a02WorksetNeverAbsorbsNewOrNeighbourTreeLogs` 锁定。
- Safety pause/resume 原任务实例保留 workset（`onResume:242-247` 只清 transient），
  GameTest `mc2a02SafetyPauseResumeKeepsSameTreeWorkset` 锁定。
- cancel：owned support 存在时 terminal reason 带
  `tree_cleanup_debt_supports` + bounded summary（GatherQuotaTask onAbort 织入段；
  GameTest debt 用例锁定 conflict → 不删 + DEBT + 非 TREE_COMPLETE）。

## 7. 本轮补丁应用过程中修正的偏差（全部最小修正，不改 TREE-1..6 语义）

1. `TreeHarvestWorkset.java:548` 比较器 lambda 显式 `(BlockPos p)`（javac 嵌套推断
   限制，真实编译暴露）。
2. 契约测试断言匹配 installer 生成的两行调用形状（生成源中无连续子串
   `TreeHarvestWorkset.acquire`）。
3. installer EXTRA_CHANGES 注册 `MC2A02TreeHarvestGameTests`（fabric.mod.json
   gametest entrypoint；否则 5 个新 GameTest 编译通过但静默不执行 — 沿用 MC-1C-A
   轮同位修复惯例）。
4. GatherQuotaTask transform 恢复无条件 `if (waitForDryGround(bot))`（补丁原文本在
   TREE_CLEANUP 旁路水救援暂停，违反既有
   `SurfaceExpeditionWaterRecoverySourceContractTest` 锁定的共享水救援契约；pause
   不丢 workset，TREE-5 语义覆盖此场景）。
5. `MC2A02TreeHarvestGameTests.mc2a02QuotaOneDoesNotTruncateCommittedTree` 在 assign
   前等待外部 body respawn fence（`external_mode_enter` cancelAll）先完成（R1 fence
   在每 fresh body 实例触发一次；spawn 同 tick assign 会被 fence 杀任务。其余 4 个
   新测试断言在同步块内完成不受影响）。
