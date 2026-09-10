# MC-1C-A R2.1｜direct world mutation 审查说明

## 审查方法

1. 对冻结上游 a029fa6 + R2 overlay + R2.1 修复后的 `src/main/java` 全量 grep 五类调用：
   `breakBlock(`、`setBlockState(`、`processBlockBreakingAction(`、`startMining(`、`startPathTo(`；
2. 对每个命中点人工回溯调用链至 external reserved body 的可达性（谁创建任务、经过哪些闸）；
3. 结论落在 `world-mutation-surface.txt`，每个调用点一个分类。

## 分类口径

| 分类 | 含义 |
|---|---|
| PHYSICAL_MINING_GATE | 经 MiningController/BreakPolicy 的物理破坏通道，每 tick 硬门 |
| REGISTERED_FARM_DOMAIN | R2.1 新增 farm mask gate 后的授权域内 mutation |
| SAFETY_EXCEPTION | SAFETY origin 显式豁免（R1 生存安全网） |
| LEGACY_ONLY_NOT_REACHABLE_IN_RESERVED_MODE | 创建点被 reserved 闸死（checkTool/checkAssignment/profile），external 不可达 |
| TEST_ONLY | 仅测试 fixture（本轮无命中） |
| FIXED_THIS_ROUND | 本轮修复的绕过面 |

## 为什么 FarmAction 只 gate 三处

`till/plant/harvest` 是 reserved external 身体经 `tend_farm` 真实可达的三条路径（R2 实机
LIVE-R2-2 已证明闭环），R2.1 在 mutation 时刻（FarmAction 方法入口，而非仅 survey 过滤层）
加 `SemanticWorldRegistry.farmMutationDenial`：reserved 身体 + mask 外 → typed
`farm_mutation_outside_registered_mask:<kind>@<pos>` + BotLog `farm_mutation_denied` + 零世界改动。

`placeWater/fillBucket` 的调用链（IrrigateTask/无调用者）在 reserved 模式不可达——按任务书
7.2"不要为未来想象路径过度修改"，不加 gate，仅在 audit 中证明不可达。若未来 MC-2A 把灌溉
接入 external route，必须先补 gate。

## BuildAction.directPlaceFallback 为何保留

`BuildAction.java:205` 的裸 setBlockState 仅在 `profile != STRICT_SURVIVAL` 分支
（BuildAction 103-105 显式排除），而 external 模式强制 strict_survival
（MinecraftBodyBackend.start() 403 strict_survival_required）。它属于 operator 模式的
既有放置回退，不是 reserved 身体的绕过面。

## R2.1 改动的 mutation 面净变化

- 新增 gate 面：FarmAction till/plant/harvest 三处（收敛绕过）。
- 无新增 mutation 面：R2.1 的 HOME 修复走 BuildTask→BuildAction.placeBlockAt（原版交互
  放置），不新增任何直接 world 写入；ObservableWorldQuery.canObserveCellFrom 为纯读。
- 无放宽：strict_survival capability 矩阵、BreakPolicy、perception radius 全部未动
  （PrivilegedBoundarySourceTest 守卫同步更新为 R2.1 更强的断言：work-pose 必须经
  ObservableWorldQuery.canObserveCellFrom 证明目标格可见）。
